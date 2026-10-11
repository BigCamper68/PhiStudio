package com.xpe.mobile.preview;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.opengl.GLES20;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Standalone Android runtime test. Never installs or changes the user's application. */
public final class ShaderDeviceProbe {
    private static final int W = 192, H = 128;
    private static final String HEADER = "#version 100\nprecision mediump float;\n"
            + "varying vec2 uv; uniform sampler2D screenTexture;\n";
    private static final RectF FULL = new RectF(0,0,W,H), FITTED = new RectF(0,10,W,H-10);
    private static final List<String> errors = new ArrayList<>();
    private static String utf8(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static ShaderTimeline timeline(String effects, String source) throws Exception {
        return ShaderTimeline.parse("{\"bpm\":60,\"effects\":" + effects + "}", name -> source);
    }
    private static String effect(boolean global, String vars) {
        return "{\"shader\":\"probe\",\"start\":[0,0,1],\"end\":[10,0,1],\"global\":" + global + ",\"vars\":" + vars + "}";
    }
    private static int pixel(int x, int y) {
        ByteBuffer result = ByteBuffer.allocateDirect(4);
        GLES20.glReadPixels(x,H-y-1,1,1,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,result);
        return Color.rgb(result.get(0)&255,result.get(1)&255,result.get(2)&255);
    }
    private static void near(int expected, int actual, String label) {
        require(Math.abs(Color.red(expected)-Color.red(actual)) <= 3
                && Math.abs(Color.green(expected)-Color.green(actual)) <= 3
                && Math.abs(Color.blue(expected)-Color.blue(actual)) <= 3,
                label + " expected=" + Integer.toHexString(expected) + " got=" + Integer.toHexString(actual));
    }
    public static void main(String[] args) {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out)));
        System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err)));
        try { run(args); }
        catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
        android.os.Looper.prepare();
        android.graphics.Typeface.class.getMethod("loadPreinstalledSystemFontMap").invoke(null);
        Bitmap gameplay = Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);
        Bitmap overlay = Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);
        try (ShaderEglSession egl = new ShaderEglSession(null,W,H);
             ShaderGlRenderer gl = new ShaderGlRenderer(errors::add)) {
            System.out.println("GPU: " + GLES20.glGetString(GLES20.GL_RENDERER));
            System.out.println("GLES: " + GLES20.glGetString(GLES20.GL_VERSION));
            gameplay.eraseColor(Color.RED); overlay.eraseColor(Color.TRANSPARENT);
            gameplay.setPixel(0,0,Color.BLUE); gameplay.setPixel(0,H-1,Color.GREEN);
            gl.render(gameplay,overlay,ShaderTimeline.EMPTY,0,FULL,W,H);
            near(Color.BLUE,pixel(0,0),"top-left upload orientation");
            near(Color.GREEN,pixel(0,H-1),"bottom-left upload orientation");
            near(Color.RED,pixel(W/2,H/2),"identity");
            System.out.println("PASS bitmap orientation and identity");

            gameplay.eraseColor(Color.RED); overlay.setPixel(W/2,H/2,Color.GREEN);
            String gray = HEADER + "void main(){ vec4 c=texture2D(screenTexture,uv); float g=dot(c.rgb,vec3(.299,.587,.114));gl_FragColor=vec4(g,g,g,1.0);}";
            gl.render(gameplay,overlay,timeline("["+effect(false,"{}")+"]",gray),1,FITTED,W,H);
            near(Color.GREEN,pixel(W/2,H/2),"local HUD exclusion");
            near(Color.RED,pixel(W/2,2),"local letterbox exclusion");
            near(Color.rgb(76,76,76),pixel(W/2,20),"local effect");
            gl.render(gameplay,overlay,timeline("["+effect(true,"{}")+"]",gray),1,FITTED,W,H);
            near(Color.rgb(150,150,150),pixel(W/2,H/2),"global HUD inclusion");
            near(Color.rgb(76,76,76),pixel(W/2,2),"global letterbox inclusion");
            System.out.println("PASS local/global ordering and margins");

            overlay.eraseColor(Color.TRANSPARENT);
            String invert = HEADER+"void main(){gl_FragColor=vec4(vec3(1.0)-texture2D(screenTexture,uv).rgb,1.0);}";
            gl.render(gameplay,overlay,timeline("["+effect(true,"{}")+","+effect(true,"{}")+"]",invert),1,FULL,W,H);
            near(Color.RED,pixel(W/2,H/2),"two-pass ping-pong");
            gl.render(gameplay,overlay,timeline("["+effect(true,"{}")+"]",invert),10,FULL,W,H);
            near(Color.RED,pixel(W/2,H/2),"exclusive end");
            System.out.println("PASS chained effects and exclusive end");

            String power = HEADER+"uniform float power; void main(){gl_FragColor=vec4(texture2D(screenTexture,uv).rgb*power,1.0);}";
            ShaderTimeline edited=timeline("["+effect(true,"{\"power\":0.25}")+"]",power);
            gl.render(gameplay,overlay,edited,1,FULL,W,H);
            java.lang.reflect.Field cacheField=ShaderGlRenderer.class.getDeclaredField("programs");cacheField.setAccessible(true);
            java.util.Map<?,?> cache=(java.util.Map<?,?>)cacheField.get(gl);Object retained=cache.get(power);
            for(int i=0;i<50;i++) {
                double value=i/49.0;
                gl.render(gameplay,overlay,timeline("["+effect(true,"{\"power\":"+value+"}")+"]",power),1,FULL,W,H);
                require(cache.get(power)==retained,"parameter edits must retain the compiled GL program");
                near(Color.rgb((int)Math.round(255*value),0,0),pixel(W/2,H/2),"live uniform value "+i);
            }
            gl.prepare(ShaderTimeline.EMPTY);require(cache.isEmpty(),"removed shader programs released");
            System.out.println("PASS 50 live parameter snapshots reuse the same GL program, update pixels and release obsolete shaders");
            gl.render(gameplay,overlay,timeline("["+effect(true,"{\"power\":1}")+","+effect(true,"{}")+"]",power),1,FULL,W,H);
            near(Color.BLACK,pixel(W/2,H/2),"no leaked uniforms");
            String values = HEADER+"uniform float power; // %0.25%\n uniform vec2 offset; // %0.0,0.0%\n uniform vec4 color; // %0.0,0.0,0.0,1.0%\n"
                    + "uniform float time; uniform vec2 screenSize; void main(){gl_FragColor=vec4(power+offset.x,color.g,time/10.0,1.0);}";
            gl.render(gameplay,overlay,timeline("["+effect(true,"{\"offset\":[0.25,0],\"color\":[0,128,0,255]}")+"]",values),5,FULL,W,H);
            near(Color.rgb(128,128,128),pixel(W/2,H/2),"typed uniforms, defaults and chart time");
            System.out.println("PASS uniform isolation, float/vec2/color and defaults");
            String customName = HEADER+"uniform float uploadFlip; void main(){gl_FragColor=vec4(uploadFlip,0.0,0.0,1.0);}";
            gl.render(gameplay,overlay,timeline("["+effect(true,"{\"uploadFlip\":0.5}")+"]",customName),1,FULL,W,H);
            near(Color.rgb(128,0,0),pixel(W/2,H/2),"custom uniform namespace");
            System.out.println("PASS custom uniforms have no renderer-specific reserved names");

            String uv = HEADER+"void main(){gl_FragColor=vec4(uv,0.0,1.0);}";
            gl.render(gameplay,overlay,timeline("["+effect(false,"{}")+"]",uv),1,FITTED,W,H);
            near(Color.rgb(128,231,0),pixel(W/2,12),"local centered UVScale");
            System.out.println("PASS centered viewport UVScale");

            String[] presets={"chromatic","circle_blur","fisheye","glitch","grayscale","noise","pixel","radial_blur","shockwave","vignette"};
            for (String preset:presets) {
                String source=utf8(Files.readAllBytes(new File(args[0],preset+".glsl").toPath()));
                gl.render(gameplay,overlay,timeline("["+effect(true,"{}")+"]",source),1,FULL,W,H);
            }
            require(errors.isEmpty(),"preset compilation: "+errors);
            System.out.println("PASS all 10 original prpr preset shaders compile and render");
            for (String name:new String[]{"★ Ascension To Heaven.zip","ANTINEUTRINO WITCHCRAFT.zip","Hello (BPM) 2025.zip"}) {
                try (ZipFile zip=new ZipFile(new File(args[1],name))) {
                    ShaderTimeline chart=ShaderTimeline.parse(utf8(read(zip,"extra.json")), shader -> {
                        if(shader.startsWith("/")) return utf8(read(zip,shader.substring(1)));
                        String asset=shader.equals("circleBlur")?"circle_blur":shader.equals("radialBlur")?"radial_blur":shader;
                        return utf8(Files.readAllBytes(new File(args[0],asset+".glsl").toPath()));
                    });
                    gl.prepare(chart);
                    require(errors.isEmpty(),name+" compilation: "+errors);
                    for(double seconds:new double[]{0,1,10,30,60,90,120,180,240,300}) {
                        gl.render(gameplay,overlay,chart,seconds,FITTED,W,H);
                    }
                    System.out.println("PASS "+name+" effects="+chart.effects.size()+" shaders compile; 10 playback/seek frames");
                }
            }
            gl.render(gameplay,overlay,timeline("["+effect(true,"{}")+"]",HEADER+"invalid syntax"),1,FULL,W,H);
            require(errors.size()==1,"invalid GLSL must be reported once");
            near(Color.RED,pixel(W/2,H/2),"invalid shader fallback");
            System.out.println("PASS invalid shader isolation and package switching");
            hardware(gl, gray);
            System.out.println("ALL DEVICE SHADER TESTS PASSED");
        } finally { gameplay.recycle(); overlay.recycle(); }
    }
    private static void hardware(ShaderGlRenderer gl, String gray) throws Exception {
        android.os.HandlerThread callbacks=new android.os.HandlerThread("ShaderProbeCallbacks");
        callbacks.start(); android.os.Handler handler=new android.os.Handler(callbacks.getLooper());
        Semaphore gameFrame=new Semaphore(0), hudFrame=new Semaphore(0);
        try(ShaderCanvasLayer game=new ShaderCanvasLayer(W,H,handler,gameFrame::release);
            ShaderCanvasLayer hud=new ShaderCanvasLayer(W,H,handler,hudFrame::release)) {
            Paint paint=new Paint();
            Canvas canvas=game.lock(true); require(canvas.isHardwareAccelerated(),"hardware gameplay Canvas");
            canvas.drawColor(Color.RED); paint.setColor(Color.BLUE); canvas.drawRect(0,0,4,4,paint);
            paint.setColor(Color.GREEN); canvas.drawRect(0,H-4,4,H,paint); game.post(canvas);
            canvas=hud.lock(false); require(canvas.isHardwareAccelerated(),"hardware HUD Canvas");
            paint.setColor(Color.GREEN); canvas.drawRect(W/2-2,H/2-2,W/2+2,H/2+2,paint); hud.post(canvas);
            update(game,hud,gameFrame,hudFrame);
            gl.renderLayers(game,hud,W,H,ShaderTimeline.EMPTY,0,FULL,W,H);
            near(Color.BLUE,pixel(0,0),"external top-left orientation");
            near(Color.GREEN,pixel(0,H-1),"external bottom-left orientation");
            near(Color.GREEN,pixel(W/2,H/2),"external HUD");
            near(Color.RED,pixel(W/2,20),"external identity");
            gl.renderLayers(game,hud,W,H,timeline("["+effect(false,"{}")+"]",gray),1,FITTED,W,H);
            near(Color.GREEN,pixel(W/2,H/2),"hardware local HUD exclusion");
            near(Color.RED,pixel(W/2,2),"hardware local margin exclusion");
            near(Color.rgb(76,76,76),pixel(W/2,20),"hardware local grayscale");
            gl.renderLayers(game,hud,W,H,timeline("["+effect(true,"{}")+"]",gray),1,FITTED,W,H);
            near(Color.rgb(150,150,150),pixel(W/2,H/2),"hardware global HUD inclusion");
            System.out.println("PASS hardware Canvas/external OES orientation, alpha and local/global order");

            // Verify buffers clear completely rather than retaining HUD pixels from earlier posts.
            for(int i=0;i<30;i++) {
                canvas=game.lock(true); canvas.drawColor(i%2==0?Color.RED:Color.BLUE); game.post(canvas);
                canvas=hud.lock(false);
                if(i%2==0) {paint.setColor(0x800000ff); canvas.drawRect(W/2-2,H/2-2,W/2+2,H/2+2,paint);}
                hud.post(canvas); update(game,hud,gameFrame,hudFrame);
                gl.renderLayers(game,hud,W,H,ShaderTimeline.EMPTY,0,FULL,W,H);
                near(i%2==0?Color.rgb(127,0,128):Color.BLUE,pixel(W/2,H/2),"paired/cleared hardware frame "+i);
            }
            canvas=game.lock(true); canvas.drawColor(Color.RED); game.post(canvas);
            canvas=hud.lock(false); hud.post(canvas); update(game,hud,gameFrame,hudFrame);
            String discard=HEADER+"void main(){if(uv.x<0.5)discard;gl_FragColor=vec4(0.0,0.0,1.0,1.0);}";
            gl.renderLayers(game,hud,W,H,timeline("["+effect(true,"{}")+"]",discard),1,FULL,W,H);
            near(Color.RED,pixel(W/4,H/2),"custom discard preserves original frame");
            near(Color.BLUE,pixel(3*W/4,H/2),"custom discard writes remaining pixels");
            System.out.println("PASS repeated hardware frames, premultiplied alpha, HUD clearing and GLSL discard");
        } finally {callbacks.quitSafely(); callbacks.join();}
    }
    private static void update(ShaderCanvasLayer game,ShaderCanvasLayer hud,Semaphore gameFrame,Semaphore hudFrame) throws Exception {
        require(gameFrame.tryAcquire(5,TimeUnit.SECONDS),"gameplay buffer timeout");
        require(hudFrame.tryAcquire(5,TimeUnit.SECONDS),"HUD buffer timeout");
        game.update(); hud.update();
    }
    private static byte[] read(ZipFile zip,String path) throws java.io.IOException {
        try(java.io.InputStream stream=zip.getInputStream(zip.getEntry(path)); java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int count;
            while((count=stream.read(buffer))!=-1)bytes.write(buffer,0,count);
            return bytes.toByteArray();
        }
    }
}
