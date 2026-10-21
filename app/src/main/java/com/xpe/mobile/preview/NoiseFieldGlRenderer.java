package com.xpe.mobile.preview;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONObject;

/** Native BlockRender passes, on the compositor's EGL thread. No frame readback. */
final class NoiseFieldGlRenderer implements AutoCloseable {
    private static final int NORMAL=0, SUBTRACT=1, DISABLED=2, DISABLED_SUB=3,
            READY=4, READY_SUB=5, COMPOSE=6, DISABLED_COMPOSE=7, EFFECT=8,
            PING=9, PONG=10, HOVER=11, SNAPSHOT=12, TEMP=13;
    private static final float[] IDENTITY={1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    private static final String BLIT_VERTEX="attribute vec4 in_POSITION0; attribute vec2 in_TEXCOORD0;"
            +"varying highp vec2 uv; uniform vec4 sourceRect; void main(){gl_Position=in_POSITION0;uv=sourceRect.xy+in_TEXCOORD0*sourceRect.zw;}";
    private static final String BLIT_FRAGMENT="precision mediump float; varying highp vec2 uv; uniform sampler2D source; void main(){gl_FragColor=texture2D(source,uv);}";
    private final int[] textures=new int[14], fbos=new int[14];
    private final Map<String,P> programs=new HashMap<>();
    private final FloatBuffer vertices=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final JSONObject parameters;
    private final int block, displace, spark, noise, hover;
    private final P blit;
    private final AssetManager assets;
    private final NoiseFieldPalette palette = new NoiseFieldPalette();
    private final int[] paletteTextures = new int[2], paletteFbo = new int[1];
    private int paletteWidth, paletteHeight;
    private long uploadedPalette = -1;
    private boolean colored;
    private float paletteClock = Float.NaN;
    private int width, height, maskWidth, maskHeight, effectWidth, effectHeight;
    private NoiseFieldFrame frame=NoiseFieldFrame.EMPTY;

    NoiseFieldGlRenderer(AssetManager assets) {
        this.assets = assets;
        try {
            parameters=new JSONObject(read(assets,"parameters.json"));
            for(String kind:new String[]{"BlockSprite","BlockCompose","SubtractBlockBlender","EdgeMask","GlowMask","DisabledBlock","ReadyBlock","ActiveBlock"}) {
                int count=kind.equals("BlockCompose")||kind.equals("SubtractBlockBlender")||kind.equals("EdgeMask")||kind.equals("GlowMask")?2:1;
                for(int i=0;i<count;i++) programs.put(kind+i,new P(read(assets,kind+"-"+i+".vert"),read(assets,kind+"-"+i+".frag")));
            }
            blit=new P(BLIT_VERTEX,BLIT_FRAGMENT);
            block=texture(assets,"Block.png",GLES20.GL_CLAMP_TO_EDGE,false);
            displace=texture(assets,"BlockNoise1.png",GLES20.GL_MIRRORED_REPEAT,false);
            spark=texture(assets,"PointNoise.png",GLES20.GL_REPEAT,false);
            noise=texture(assets,"FD_Noise.png",GLES20.GL_MIRRORED_REPEAT,false);
            hover=texture(assets,"TouchHover.png",GLES20.GL_CLAMP_TO_EDGE,true);
            GLES20.glGenTextures(textures.length,textures,0);
            GLES20.glGenFramebuffers(fbos.length,fbos,0);
        } catch(Exception ex) { close(); throw new IllegalStateException("Noise field resources: "+ex.getMessage(),ex); }
    }

    void prepare(NoiseFieldFrame next, int w, int h) {
        frame=next; resize(w,h);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST); GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glColorMask(true,true,true,true);
        P sprite=program("BlockSprite",0,0);
        sprite.tex("_MainTex",block,0); sprite.set("_MainTex_ST",1,1,0,0);sprite.set("_Color",1,1,1,1);
        for(int channel=0;channel<6;channel++) {
            target(channel,true); GLES20.glEnable(GLES20.GL_BLEND);GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE);
            GLES20.glUseProgram(sprite.id);
            for(NoiseFieldFrame.Zone zone:frame.zones) {
                if(zone.active!=(channel<2) || zone.subtract!=((channel&1)!=0) || channel>=4&&!zone.ready)continue;
                sprite.color(1,zone.subtract?zone.opacity:0,0,zone.subtract?.1f:zone.opacity);
                polygon(sprite,zone.corners(),frame.aspect);
            }
        }
        GLES20.glDisable(GLES20.GL_BLEND);
        // Native SubtractBlockPostProcessor: R8 threshold window for enabled;
        // two-channel ownership/coverage for Disabled and Ready.
        for(int channel:new int[]{SUBTRACT,DISABLED_SUB,READY_SUB}) {
            P subtract=program("SubtractBlockBlender",channel==SUBTRACT?0:1,12);
            target(TEMP,true);subtract.tex("_MainTex",textures[channel],0);full(subtract);
            target(channel,true);copy(textures[TEMP],0,0,1,1);
        }
        P compose=program("BlockCompose",0,6);target(COMPOSE,true);
        compose.tex("_NormalBlockRT",textures[NORMAL],0);compose.tex("_SubtractBlockRT",textures[SUBTRACT],1);
        compose.tex("_DisplaceMap",displace,2);compose.set("_DisplaceMap_ST",2.13f,1.02f,0,0);full(compose);
        compose=program("BlockCompose",1,6);target(DISABLED_COMPOSE,true);
        compose.tex("_DisabledNormalBlockRT",textures[DISABLED],0);compose.tex("_DisabledSubtractBlockRT",textures[DISABLED_SUB],1);full(compose);
        target(EFFECT,true);
        P edge=program("EdgeMask",1,9);edge.tex("_MainTex",textures[COMPOSE],0);edge.tex("_ComposeRT",textures[COMPOSE],1);
        GLES20.glColorMask(true,false,false,false);full(edge);GLES20.glColorMask(true,true,true,true);
        // Six weighted 3x3 dilations, retaining the original RG8 intermediate
        // quantization and first-pass flag. Final pass writes effect.G only.
        float sum=0; for(int k=6;k>0;k--)sum+=(float)Math.pow(k,2.65f);
        int previous=COMPOSE, current=PING;
        for(int pass=0;pass<6;pass++) {
            float weight=(float)Math.pow(6-pass,2.65f)/sum;
            if(weight<.01f)break;
            P glow=program("GlowMask",0,10);target(current,true);
            glow.tex("_MainTex",textures[previous],0);glow.tex("_ComposeRT",textures[COMPOSE],1);
            glow.set("_PassWeight",weight);glow.set("_GlowFirstPass",pass==0?1:0);full(glow);
            previous=current;current=current==PING?PONG:PING;
        }
        P glow=program("GlowMask",1,10);target(EFFECT,false);glow.tex("_MainTex",textures[previous],0);
        GLES20.glColorMask(false,true,false,false);full(glow);GLES20.glColorMask(true,true,true,true);
        target(HOVER,true);GLES20.glEnable(GLES20.GL_BLEND);GLES20.glBlendFunc(GLES20.GL_ONE,GLES20.GL_ONE_MINUS_SRC_ALPHA);
        for(NoiseFieldFrame.Touch touch:frame.touches) {
            // Native 44 px sprite, 100 pixels/unit, prefab scale 11.5, camera height 10.
            float size=(44f/100f*11.5f/10f)*2/frame.aspect*touch.scale;
            blit.use();blit.set("sourceRect",0,0,1,1);blit.tex("source",hover,0);
            rectangle(blit,touch.x-size/2,touch.y-size/2,touch.x+size/2,touch.y+size/2,frame.aspect);
        }
        GLES20.glDisable(GLES20.GL_BLEND);
        preparePalette();
    }

    private void preparePalette() {
        colored = palette.update(frame, maskWidth, maskHeight);
        if (!colored) return;
        if (paletteTextures[0] == 0) {
            int[] units = new int[1]; GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS, units, 0);
            if (units[0] < 11) throw new IllegalStateException("Colored noise fields require 11 fragment texture units");
            try {
                programs.put("ColoredActiveBlock0", new P(read(assets,"ActiveBlock-0.vert"),
                        NoiseFieldColorShaders.material(read(assets,"ActiveBlock-0.frag"), true)));
                programs.put("ColoredDisabledBlock0", new P(read(assets,"DisabledBlock-0.vert"),
                        NoiseFieldColorShaders.material(read(assets,"DisabledBlock-0.frag"), false)));
                programs.put("Palette0", new P(read(assets,"BlockCompose-0.vert"),
                        NoiseFieldColorShaders.compose(read(assets,"BlockCompose-0.frag"))));
            } catch (Exception ex) { throw new IllegalStateException("Noise field color resources", ex); }
            GLES20.glGenTextures(2, paletteTextures, 0); GLES20.glGenFramebuffers(1, paletteFbo, 0);
        }
        boolean resized = paletteWidth != maskWidth || paletteHeight != maskHeight;
        if (resized) {
            paletteWidth = maskWidth; paletteHeight = maskHeight;
            for (int texture : paletteTextures) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,maskWidth,maskHeight*2,0,
                        GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,paletteFbo[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,paletteTextures[1],0);
            if(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)!=GLES20.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Noise field palette framebuffer unavailable");
        }
        boolean changed = resized || uploadedPalette != palette.revision();
        if (changed) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,paletteTextures[0]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,maskWidth,maskHeight*2,0,
                    GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,palette.pixels());
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,paletteFbo[0]);
            GLES20.glViewport(0,maskHeight,maskWidth,maskHeight);
            copy(paletteTextures[0],0,.5f,1,.5f);
        }
        if (changed || paletteClock != frame.seconds) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,paletteFbo[0]);
            GLES20.glViewport(0,0,maskWidth,maskHeight);
            P p=program("Palette",0,6); p.tex("phiPalette",paletteTextures[0],0);
            p.tex("_DisplaceMap",displace,1); p.set("_DisplaceMap_ST",2.13f,1.02f,0,0);
            p.set("phiMaskSize",maskWidth,maskHeight); full(p);
        }
        uploadedPalette=palette.revision(); paletteClock=frame.seconds;
    }

    void disabled(int destination, RectF viewport, int outputHeight) {
        destination(destination,viewport,outputHeight);
        P p=program(colored?"ColoredDisabledBlock":"DisabledBlock",0,8);p.tex("_ComposeRT",textures[DISABLED_COMPOSE],0);
        p.tex("_DisplaceMap",displace,1);p.tex("_SparkMap",spark,2);
        p.set("_DisplaceMap_ST",.5f,.2f,0,0);p.set("_SparkMap_ST",3,1.2f,0,0);
        colorMaterial(p);
        GLES20.glEnable(GLES20.GL_BLEND);GLES20.glBlendFunc(GLES20.GL_ONE,GLES20.GL_ONE);full(p);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    void active(int scene, int destination, RectF viewport, int outputWidth, int outputHeight) {
        target(SNAPSHOT,true);
        copy(scene,viewport.left/outputWidth,(outputHeight-viewport.bottom)/outputHeight,
                viewport.width()/outputWidth,viewport.height()/outputHeight);
        destination(destination,viewport,outputHeight);
        P p=program(colored?"ColoredActiveBlock":"ActiveBlock",0,5);
        p.tex("_ComposeRT",textures[COMPOSE],0);p.tex("_EffectRT",textures[EFFECT],1);
        p.tex("_SceneColor",textures[SNAPSHOT],2);p.tex("_DisabledNormalBlockRT",textures[READY],3);
        p.tex("_DisabledSubtractBlockRT",textures[READY_SUB],4);p.tex("_ReadyComposeRT",textures[DISABLED_COMPOSE],5);
        p.tex("_TouchHoverRT",textures[HOVER],6);p.tex("_DisplaceMap",displace,7);
        p.tex("_TouchDisplaceMap",displace,7);p.tex("_SparkMap",spark,8);p.tex("_NoiseMap",noise,9);
        p.set("_DisplaceMap_ST",.8f,.3f,0,0);p.set("_SparkMap_ST",3,1.2f,0,0);
        p.set("_TouchDisplaceMap_ST",.55f,.3f,0,0);p.set("_NoiseMap_ST",1.5f,1.46f,0,0);
        int heldTouches=0;
        for(NoiseFieldFrame.Touch touch:frame.touches) {
            if(!touch.held || heldTouches>=10)continue;
            p.set("_TouchPos["+heldTouches+"]",(touch.x+1)*.5f*frame.aspect,(touch.y*frame.aspect+1)*.5f);
            heldTouches++;
        }
        colorMaterial(p);
        p.integer("_TouchPosCount",heldTouches);
        p.set("_TouchPosShine",(.63f+.37f*((float)Math.sin(frame.seconds*43)*.5f+.5f))*2);
        GLES20.glEnable(GLES20.GL_BLEND);GLES20.glBlendFunc(GLES20.GL_ONE,GLES20.GL_ONE_MINUS_SRC_ALPHA);full(p);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void colorMaterial(P p) {
        if (!colored) return;
        p.tex("phiPalette",paletteTextures[1],10); p.set("phiMaskSize",maskWidth,maskHeight);
        p.set("phiHasInactive",palette.inactiveVisible()?1:0);
    }

    private P program(String name,int pass,int material) {
        P p=programs.get(name+pass);p.use();
        if(material!=0)try {
            JSONObject m=parameters.getJSONObject(Integer.toString(material));
            JSONObject values=m.getJSONObject("floats");Iterator<String> names=values.keys();
            while(names.hasNext()){String key=names.next();p.set(key,(float)values.getDouble(key));}
            values=m.getJSONObject("colors");names=values.keys();
            while(names.hasNext()){
                String key=names.next();JSONObject c=values.getJSONObject(key);
                p.set(key,(float)c.getDouble("r"),(float)c.getDouble("g"),(float)c.getDouble("b"),(float)c.getDouble("a"));
            }
        }catch(Exception ex){throw new IllegalStateException("Noise field parameters",ex);}
        p.vectors("hlslcc_mtx4x4unity_ObjectToWorld[0]",IDENTITY);p.vectors("hlslcc_mtx4x4unity_MatrixVP[0]",IDENTITY);
        p.set("_ProjectionParams",1,0,0,0);p.set("_ScreenParams",width,height,1+1f/width,1+1f/height);
        p.set("_Time",frame.seconds/20,frame.seconds,frame.seconds*2,frame.seconds*3);
        p.set("_EffectRT_TexelSize",1f/effectWidth,1f/effectHeight,effectWidth,effectHeight);
        p.set("_DilateTexelSize",1f/effectWidth,1f/effectHeight,effectWidth,effectHeight);
        p.color(1,1,1,1);return p;
    }
    private void full(P p){rectangle(p,-1,-1,1,1,1);}
    private void rectangle(P p,float left,float bottom,float right,float top,float aspect){polygon(p,new float[]{left,bottom,right,bottom,right,top,left,top},aspect);}
    private void polygon(P p,float[] xy,float aspect) {
        vertices.clear();for(int i:new int[]{0,1,3,2})vertices.put(xy[i*2]).put(xy[i*2+1]*aspect).put(i==1||i==2?1:0).put(i>=2?1:0);vertices.flip();
        int pos=p.location("in_POSITION0"),uv=p.location("in_TEXCOORD0");
        vertices.position(0);GLES20.glEnableVertexAttribArray(pos);GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,16,vertices);
        if(uv>=0){vertices.position(2);GLES20.glEnableVertexAttribArray(uv);GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices);}
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        GLES20.glDisableVertexAttribArray(pos);if(uv>=0)GLES20.glDisableVertexAttribArray(uv);
    }
    private void copy(int texture,float x,float y,float w,float h){blit.use();blit.tex("source",texture,0);blit.set("sourceRect",x,y,w,h);full(blit);}
    private void destination(int fbo,RectF viewport,int outputHeight){GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbo);GLES20.glViewport(Math.round(viewport.left),Math.round(outputHeight-viewport.bottom),Math.round(viewport.width()),Math.round(viewport.height()));GLES20.glColorMask(true,true,true,true);}
    private int targetWidth(int index){return index==SNAPSHOT?Math.max(1,width/6):index>=EFFECT&&index<=PONG?effectWidth:maskWidth;}
    private int targetHeight(int index){return index==SNAPSHOT?Math.max(1,height/6):index>=EFFECT&&index<=PONG?effectHeight:maskHeight;}
    private void target(int index,boolean clear){GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbos[index]);GLES20.glViewport(0,0,targetWidth(index),targetHeight(index));if(clear){GLES20.glColorMask(true,true,true,true);GLES20.glClearColor(0,0,0,0);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);}}
    private void resize(int w,int h){
        if(width==w&&height==h)return;width=w;height=h;maskWidth=Math.max(1,w/8);maskHeight=Math.max(1,h/8);effectWidth=Math.max(1,w/4);effectHeight=Math.max(1,h/4);
        for(int i=0;i<textures.length;i++){
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[i]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,i==EFFECT?GLES20.GL_LINEAR:GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,i==EFFECT?GLES20.GL_LINEAR:GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,targetWidth(i),targetHeight(i),0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbos[i]);GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,textures[i],0);
            if(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)!=GLES20.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Noise field framebuffer unavailable");
        }
    }
    private static String read(AssetManager assets,String name)throws Exception{try(InputStream input=assets.open("noise-field/"+name)){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int read;while((read=input.read(buffer))!=-1)bytes.write(buffer,0,read);return new String(bytes.toByteArray(),StandardCharsets.UTF_8);}}
    private static int texture(AssetManager assets,String name,int wrap,boolean linear)throws Exception{
        Bitmap image;try(InputStream input=assets.open("noise-field/"+name)){BitmapFactory.Options o=new BitmapFactory.Options();o.inScaled=false;image=BitmapFactory.decodeStream(input,null,o);}
        if(image==null)throw new IllegalStateException("Cannot decode "+name);
        Matrix flip=new Matrix();flip.setScale(1,-1);Bitmap reversed=Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),flip,false);
        int[] id=new int[1];GLES20.glGenTextures(1,id,0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,id[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,linear?GLES20.GL_LINEAR:GLES20.GL_NEAREST);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,linear?GLES20.GL_LINEAR:GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,wrap);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,wrap);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,reversed,0);if(reversed!=image)reversed.recycle();image.recycle();return id[0];
    }
    @Override public void close(){for(P p:programs.values())p.close();if(blit!=null)blit.close();GLES20.glDeleteTextures(2,paletteTextures,0);GLES20.glDeleteFramebuffers(1,paletteFbo,0);GLES20.glDeleteFramebuffers(fbos.length,fbos,0);GLES20.glDeleteTextures(textures.length,textures,0);GLES20.glDeleteTextures(5,new int[]{block,displace,spark,noise,hover},0);}

    private static final class P implements AutoCloseable {
        final int id;final Map<String,Integer> locations=new HashMap<>(),types=new HashMap<>();
        P(String vertex,String fragment){
            int v=compile(GLES20.GL_VERTEX_SHADER,vertex),f=0,linked=0;
            int[] ok=new int[1];
            try {
                f=compile(GLES20.GL_FRAGMENT_SHADER,fragment);linked=GLES20.glCreateProgram();
                GLES20.glAttachShader(linked,v);GLES20.glAttachShader(linked,f);GLES20.glLinkProgram(linked);
                GLES20.glGetProgramiv(linked,GLES20.GL_LINK_STATUS,ok,0);
                if(ok[0]==0)throw new IllegalStateException(GLES20.glGetProgramInfoLog(linked));
            } catch(RuntimeException ex) {
                if(linked!=0)GLES20.glDeleteProgram(linked);throw ex;
            } finally { GLES20.glDeleteShader(v);if(f!=0)GLES20.glDeleteShader(f); }
            id=linked;
            GLES20.glGetProgramiv(id,GLES20.GL_ACTIVE_UNIFORMS,ok,0);int[] size=new int[1],type=new int[1];
            for(int i=0;i<ok[0];i++){String name=GLES20.glGetActiveUniform(id,i,size,0,type,0);types.put(name,type[0]);}
        }
        void use(){GLES20.glUseProgram(id);}
        int location(String name){return locations.computeIfAbsent(name,n->n.startsWith("in_")?GLES20.glGetAttribLocation(id,n):GLES20.glGetUniformLocation(id,n));}
        void integer(String name,int value){int l=location(name);if(l>=0)GLES20.glUniform1i(l,value);}
        void set(String name,float... v){int l=location(name);if(l<0)return;int type=types.getOrDefault(name,types.getOrDefault(name.replaceAll("\\[\\d+\\]","[0]"),0));
            if(type==GLES20.GL_FLOAT_VEC4)GLES20.glUniform4fv(l,1,v,0);else if(type==GLES20.GL_FLOAT_VEC3)GLES20.glUniform3fv(l,1,v,0);else if(type==GLES20.GL_FLOAT_VEC2)GLES20.glUniform2fv(l,1,v,0);else GLES20.glUniform1f(l,v[0]);}
        void vectors(String name,float[] values){int l=location(name);if(l>=0)GLES20.glUniform4fv(l,4,values,0);}
        void tex(String name,int texture,int unit){int l=location(name);if(l<0)return;GLES20.glActiveTexture(GLES20.GL_TEXTURE0+unit);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);GLES20.glUniform1i(l,unit);}
        void color(float r,float g,float b,float a){int l=location("in_COLOR0");if(l>=0){GLES20.glDisableVertexAttribArray(l);GLES20.glVertexAttrib4f(l,r,g,b,a);}}
        private static int compile(int kind,String source){int shader=GLES20.glCreateShader(kind);GLES20.glShaderSource(shader,source);GLES20.glCompileShader(shader);int[] ok=new int[1];GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,ok,0);if(ok[0]==0){String error=GLES20.glGetShaderInfoLog(shader);GLES20.glDeleteShader(shader);throw new IllegalStateException(error);}return shader;}
        @Override public void close(){GLES20.glDeleteProgram(id);}
    }
}
