package com.xpe.mobile.preview;

import android.content.res.AssetManager;
import android.graphics.RectF;
import android.opengl.GLES20;
import com.xpe.mobile.model.NoiseField;
import com.xpe.mobile.noiseeditor.NoiseFieldEdits;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.json.*;

/** Actual GLES regressions: independent native Compose oracle, colored holes and under-grid opacity. */
public final class NoiseFieldColorsHost {
    private static final int WHITE=0xffffffff, RED=NoiseField.DEFAULT_COLOR;
    private static final int W=480,H=320;
    private static final JSONArray checks=new JSONArray();
    public static void main(String[] args) throws Exception {
        GLES20.init(); Path output=Path.of(args[1]); Files.createDirectories(output);
        try {
            AssetManager assets=new AssetManager(args[0]);
            try(NoiseFieldGlRenderer renderer=new NoiseFieldGlRenderer(assets);
                NoiseFieldGlRenderer oracle=new NoiseFieldGlRenderer(assets)) {
                // The oracle is native BlockCompose for a half-screen mask, not our color-warp code.
                int cases=0;
                for(int[] size:new int[][]{{480,320},{640,360},{504,344}})for(float clock:new float[]{1,9,32,1}) {
                    int w=size[0],h=size[1],mw=w/8,mh=h/8;
                    var left=area(0,0,.5,1,false,WHITE,0,0,100,100);
                    var right=area(.5,0,1,1,false,RED,0,0,100,100);
                    renderer.prepare(NoiseFieldEvaluator.evaluate(List.of(left,right),clock,w/(double)h),w,h);
                    ByteBuffer palette=read(privateArray(renderer,"paletteFbo")[0],mw,mh);
                    oracle.prepare(NoiseFieldEvaluator.evaluate(List.of(left),clock,w/(double)h),w,h);
                    ByteBuffer mask=read(privateArray(oracle,"fbos")[6],mw,mh);
                    for(int i=0;i<mw*mh;i++) {
                        int expected=(mask.get(i*4)&255)>128?255:84;
                        if((palette.get(i*4)&255)!=255||(palette.get(i*4+1)&255)!=expected||(palette.get(i*4+2)&255)!=expected)
                            throw new AssertionError("Palette does not follow native Compose at "+i+" / "+w+"x"+h+" / "+clock);
                    }
                    assertGl();cases++;
                }
                checks.put(new JSONObject().put("native_compose_palette_cases",cases));
                for(int phase=0;phase<3;phase++) {
                    boolean active=phase==2;
                    double enable=active?0:phase==1?2.25:5, disable=active?100:7;
                    var background=area(-1,-1,2,2,true,WHITE,0,enable,disable,100);
                    var redHole=area(.3,.25,.7,.75,false,RED,0,enable,disable,100);
                    var whiteHole=area(.3,.25,.7,.75,false,WHITE,0,enable,disable,100);
                    ByteBuffer actual=render(renderer,List.of(background,redHole),2,W,H);
                    ByteBuffer expected=render(renderer,List.of(background,whiteHole),2,W,H);
                    equal(actual,expected,0,"Carving color leaked into a white inverted field");
                    save(actual,W,H,output.resolve(active?"white-active-hole.png":phase==1?"white-ready-hole.png":"white-inactive-hole.png"));
                    checks.put(new JSONObject().put("white_inverted_hole_phase",phase).put("bytes_identical",true));
                }
                int max=0,count=0;
                for(int phase=0;phase<3;phase++)for(int color:new int[]{WHITE,0xff808080}) {
                    double enable=phase==2?0:phase==1?2.25:5;
                    var neutralField=area(.15,.15,.85,.85,false,color,0,enable,100,100);
                    ByteBuffer neutral=render(renderer,List.of(neutralField),2,W,H);
                    for(int y=H/4;y<H*3/4;y++)for(int x=W/4;x<W*3/4;x++) {
                        int i=(y*W+x)*4,r=neutral.get(i)&255,g=neutral.get(i+1)&255,b=neutral.get(i+2)&255;
                        max=Math.max(max,Math.max(Math.abs(r-g),Math.abs(g-b)));if(r+g+b>30)count++;
                    }
                }
                if(max>3||count<600)throw new AssertionError("Neutral color acquired a tint: max="+max+", lit="+count);
                checks.put(new JSONObject().put("white_max_channel_difference",max).put("white_lit_pixels",count)
                        .put("neutral_colors_and_phases",6));
                var green=area(.1,.15,.45,.85,false,0xff40d060,0,0,100,100);
                var blue=area(.55,.15,.9,.85,false,0xff4080ff,0,0,100,100);
                save(render(renderer,List.of(green,blue),2,W,H),W,H,output.resolve("independent-green-blue.png"));
                // Switching to native red must fully bypass the optional palette, including after resize.
                var nativeField=area(.15,.15,.85,.85,false,RED,0,0,100,100);
                ByteBuffer restored=render(renderer,List.of(nativeField),2,W,H);
                ByteBuffer untouched=render(oracle,List.of(nativeField),2,W,H);
                equal(restored,untouched,0,"Native material changed after colored fields");
                checks.put(new JSONObject().put("return_to_native_bytes_identical",true));
            }
            underGrid(assets);
        } finally { GLES20.shutdown(); }
        Files.writeString(output.resolve("colors.json"),new JSONObject().put("checks",checks)
                .put("scope","Production Java renderers on Mesa GLES, including EGLImage-backed external layers. No phone HWUI capture.").toString(2)+"\n");
        System.out.println("Color GLES checks passed: "+checks.length());
    }
    private static void underGrid(AssetManager assets) throws Exception {
        try(ShaderCanvasLayer back=layer(W,H,20,60,100,255);ShaderCanvasLayer game=layer(W,H,0,0,0,0);
            ShaderCanvasLayer hud=layer(W,H,0,0,0,0);
            ShaderGlRenderer gl=new ShaderGlRenderer(message->{throw new AssertionError(message);},assets)) {
            var field=area(.1,.1,.9,.9,false,0xff40cfff,0,0,100,100);
            var frame=NoiseFieldEvaluator.evaluate(List.of(field),2,W/(double)H);
            gl.renderLayers(back,game,hud,W,H,ShaderTimeline.EMPTY,2,new RectF(0,0,W,H),W,H,frame,1);
            ByteBuffer full=read(0,W,H);
            float alpha=128/255f;
            gl.renderLayers(back,game,hud,W,H,ShaderTimeline.EMPTY,2,new RectF(0,0,W,H),W,H,frame,alpha);
            ByteBuffer faded=read(0,W,H);int max=0;int[] base={20,60,100};
            for(int i=0;i<W*H;i++)for(int c=0;c<3;c++) {
                int expected=Math.round((full.get(i*4+c)&255)*alpha+base[c]*(1-alpha));
                max=Math.max(max,Math.abs(expected-(faded.get(i*4+c)&255)));
            }
            if(max>1)throw new AssertionError("Colored under-grid opacity: "+max);
            checks.put(new JSONObject().put("colored_under_grid_max_channel_error",max));assertGl();
        }
    }
    private static NoiseField area(double l,double b,double r,double t,boolean subtract,int color,
                                   double appear,double enable,double disable,double disappear) throws Exception {
        var json=NoiseFieldEdits.copy(NoiseFieldEdits.create(appear,disappear-appear,subtract).toJson());
        json.put("enableTime",enable).put("disableTime",disable);
        json.getJSONObject("bottomLeftPercentage").put("x",l).put("y",b);
        json.getJSONObject("topRightPercentage").put("x",r).put("y",t);
        if(color!=RED)json.put(NoiseField.COLOR,new JSONArray(new int[]{(color>>16)&255,(color>>8)&255,color&255}));
        return NoiseField.fromJson(json);
    }
    private static ByteBuffer render(NoiseFieldGlRenderer renderer,List<NoiseField> fields,double clock,int w,int h) {
        int[] texture=new int[1],fbo=new int[1];GLES20.glGenTextures(1,texture,0);GLES20.glGenFramebuffers(1,fbo,0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,w,h,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbo[0]);GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,texture[0],0);
        GLES20.glClearColor(0,0,0,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        var frame=NoiseFieldEvaluator.evaluate(fields,clock,w/(double)h);
        frame=new NoiseFieldFrame(frame.seconds,frame.aspect,frame.zones,List.of(new NoiseFieldFrame.Touch(.2f,-.1f,1)));
        renderer.prepare(frame,w,h);renderer.disabled(fbo[0],new RectF(0,0,w,h),h);renderer.active(texture[0],fbo[0],new RectF(0,0,w,h),w,h);
        ByteBuffer pixels=read(fbo[0],w,h);GLES20.glDeleteTextures(1,texture,0);GLES20.glDeleteFramebuffers(1,fbo,0);assertGl();return pixels;
    }
    private static ByteBuffer read(int fbo,int w,int h) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbo);GLES20.glFinish();ByteBuffer pixels=ByteBuffer.allocateDirect(w*h*4);
        GLES20.glReadPixels(0,0,w,h,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);return pixels;
    }
    private static int[] privateArray(Object object,String name) throws Exception {
        var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return (int[])f.get(object);
    }
    private static void equal(ByteBuffer a,ByteBuffer b,int tolerance,String message) {
        int max=0;for(int i=0;i<a.capacity();i++)max=Math.max(max,Math.abs((a.get(i)&255)-(b.get(i)&255)));
        if(max>tolerance)throw new AssertionError(message+": "+max);
    }
    private static void save(ByteBuffer pixels,int w,int h,Path path) throws Exception {
        BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {int i=(y*w+x)*4;
            image.setRGB(x,h-1-y,0xff000000|((pixels.get(i)&255)<<16)|((pixels.get(i+1)&255)<<8)|(pixels.get(i+2)&255));}
        ImageIO.write(image,"png",path.toFile());
    }
    private static ShaderCanvasLayer layer(int w,int h,int r,int g,int b,int a) {
        ByteBuffer data=ByteBuffer.allocateDirect(w*h*4);
        for(int i=0;i<w*h;i++)data.put((byte)r).put((byte)g).put((byte)b).put((byte)a);
        data.flip();return new ShaderCanvasLayer(w,h,data);
    }
    private static void assertGl() {int error=GLES20.glGetError();if(error!=0)throw new AssertionError("GL error "+error);}
}
