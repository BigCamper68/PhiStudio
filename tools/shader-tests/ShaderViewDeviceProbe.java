package com.xpe.mobile.preview;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.NoteTextureSet;
import com.xpe.mobile.model.ChartDocument;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Drives the actual TextureView worker with Android Surface buffers, without installing the APK. */
public final class ShaderViewDeviceProbe {
    private static final int W=192,H=128;
    public static void main(String[] args) {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out)));
        System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err)));
        try {run(args);System.exit(0);} catch(Throwable ex){ex.printStackTrace();System.exit(1);}
    }
    static void run(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> activityThread=Class.forName("android.app.ActivityThread");
        Object activity=activityThread.getMethod("systemMain").invoke(null);
        Context system=(Context)activityThread.getMethod("getSystemContext").invoke(activity);
        android.graphics.Typeface.class.getMethod("loadPreinstalledSystemFontMap").invoke(null);
        AssetManager assets=AssetManager.class.getDeclaredConstructor().newInstance();
        AssetManager.class.getMethod("addAssetPath",String.class).invoke(assets,args[2]);
        DisplayMetrics metrics=new DisplayMetrics();metrics.setToDefaults();
        Resources resources=new Resources(assets,metrics,new Configuration());
        Context context=new ContextWrapper(system){
            @Override public Resources getResources(){return resources;}
            @Override public AssetManager getAssets(){return assets;}
        };
        PreviewRenderer renderer=new PreviewRenderer(new NoteTextureSet(resources),1,"AUTOPLAY",assets);
        ChartDocument chart=new ChartDocument();
        RenderScene scene=ChartEvaluator.evaluate(chart,0,true);
        EditorSettings settings=new EditorSettings();
        String source="#version 100\nprecision mediump float; varying vec2 uv;uniform float time;void main(){gl_FragColor=vec4(time/10.0,uv.y,uv.x,1.0);}";
        ShaderTimeline timeline=ShaderTimeline.parse("{\"bpm\":60,\"effects\":[{\"shader\":\"probe\",\"start\":[0,0,1],\"end\":[10,0,1],\"global\":true}]}",name->source);
        HandlerThread callbacks=new HandlerThread("ShaderViewProbeCallbacks");callbacks.start();
        Handler handler=new Handler(callbacks.getLooper());Semaphore frames=new Semaphore(0);
        Semaphore emptyFrame=new Semaphore(0);
        PreviewShaderView view=new PreviewShaderView(context);
        try(ShaderEglSession egl=new ShaderEglSession(null,W,H);
            ShaderGlRenderer compositor=new ShaderGlRenderer(message->{throw new AssertionError(message);});
            ShaderCanvasLayer output=new ShaderCanvasLayer(W,H,handler,frames::release);
            ShaderCanvasLayer empty=new ShaderCanvasLayer(W,H,handler,emptyFrame::release)) {
            android.graphics.Canvas canvas=empty.lock(false);empty.post(canvas);
            require(emptyFrame.tryAcquire(5,TimeUnit.SECONDS),"empty HUD buffer");empty.update();
            Field consumer=ShaderCanvasLayer.class.getDeclaredField("consumer");consumer.setAccessible(true);
            SurfaceTexture surface=(SurfaceTexture)consumer.get(output);
            view.layout(0,0,W,H);view.onSurfaceTextureAvailable(surface,W,H);
            Object worker=get(view,"worker");
            idle(worker);
            for(int i=0;i<50;i++) {
                idle(worker);
                double seconds=1+(i%7);
                RectF area=new RectF(0,0,view.getWidth(),view.getHeight());
                require(view.submit(renderer,scene,area,area,null,settings,0,0,timeline,seconds),"accepted production frame");
                require(frames.tryAcquire(5,TimeUnit.SECONDS),"production output frame timeout "+i);
                output.update();
                compositor.renderLayers(output,empty,(int)area.width(),(int)area.height(),ShaderTimeline.EMPTY,0,area,W,H);
                ByteBuffer pixel=ByteBuffer.allocateDirect(4);
                GLES20.glReadPixels(W/2,H/2,1,1,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixel);
                require(Math.abs((pixel.get(0)&255)-Math.round(seconds*25.5))<=3,"frame/time pairing "+i);
                require(Math.abs((pixel.get(1)&255)-128)<=3,"output orientation "+i+" green="+(pixel.get(1)&255));
                if(i==10||i==20||i==30) {
                    idle(worker);int w=i==20?W:W*2,h=i==20?H:H*2;
                    surface.setDefaultBufferSize(w,h);view.layout(0,0,w,h);
                    view.onSurfaceTextureSizeChanged(surface,w,h);idle(worker);
                }
                if(i==15||i==35) {view.hidePreview();view.resetFailure();}
            }
            idle(worker);
            // Cancel a posted pair, then verify the next seek is accepted and not stuck busy.
            RectF area=new RectF(0,0,view.getWidth(),view.getHeight());
            view.submit(renderer,scene,area,area,null,settings,0,0,timeline,1);
            view.hidePreview();idle(worker);frames.drainPermits();
            view.resetFailure();idle(worker);
            view.submit(renderer,scene,area,area,null,settings,0,0,timeline,2);
            require(frames.tryAcquire(5,TimeUnit.SECONDS),"seek after cancelled frame");idle(worker);
            view.onSurfaceTextureDestroyed(surface);
            HandlerThread workerThread=(HandlerThread)get(worker,"thread");workerThread.join(5000);
            require(!workerThread.isAlive(),"GL worker closes on surface destruction");
            System.out.println("PASS actual PreviewShaderView: 50 frame/time pairs, three resizes, hide/reset, cancelled seek, EGL/Surface shutdown");
        } finally {callbacks.quitSafely();callbacks.join();}
    }
    static Object get(Object value,String name) throws Exception {Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);}
    static void idle(Object worker) throws Exception {
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<limit) {
            synchronized(worker) {if((Boolean)get(worker,"ready")&&!(Boolean)get(worker,"inFlight")
                    &&get(worker,"gameplay")!=null&&((Integer)get(worker,"width")).equals(get(worker,"requestedWidth"))
                    &&((Integer)get(worker,"height")).equals(get(worker,"requestedHeight")))return;}
            Thread.sleep(2);
        }
        throw new AssertionError("Worker failed/busy after timeout");
    }
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
