package com.xpe.mobile.preview;

import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.opengl.GLES20;
import android.util.DisplayMetrics;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.EditorBackgroundDecoder;
import com.xpe.mobile.editor.NoteTextureSet;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.packageio.PackageImporter;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Full-resolution actual chart draw, CPU/GPU completion, no install and no display FPS claim. */
public final class ShaderPerformanceProbe {
    static final int W=1920, H=1080, WARMUP=12, FRAMES=48;
    static final RectF AREA=new RectF(0,0,W,H), VIEWPORT=new RectF(150,0,1770,H);
    public static void main(String[] args) {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out)));
        System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err)));
        try { run(args); System.exit(0); }
        catch(Throwable ex) { ex.printStackTrace(); System.exit(1); }
    }
    static void run(String[] args) throws Exception {
        // app_process bypasses ActivityThread's font/looper setup; production activities already do it.
        android.os.Looper.prepare();
        android.graphics.Typeface.class.getMethod("loadPreinstalledSystemFontMap").invoke(null);
        AssetManager assets=AssetManager.class.getDeclaredConstructor().newInstance();
        AssetManager.class.getMethod("addAssetPath",String.class).invoke(assets,args[2]);
        DisplayMetrics metrics=new DisplayMetrics(); metrics.setToDefaults();
        Resources resources=new Resources(assets,metrics,new Configuration());
        PreviewRenderer renderer=new PreviewRenderer(new NoteTextureSet(resources),1,"AUTOPLAY",assets);
        EditorSettings settings=new EditorSettings();
        File workspaces=new File(args[3]); workspaces.mkdirs();
        android.os.HandlerThread callbacks=new android.os.HandlerThread("ShaderBenchCallbacks"); callbacks.start();
        android.os.Handler handler=new android.os.Handler(callbacks.getLooper());
        Semaphore gameFrame=new Semaphore(0), hudFrame=new Semaphore(0);
        try(ShaderEglSession egl=new ShaderEglSession(null,W,H);
            BitmapShaderBaseline baseline=new BitmapShaderBaseline(message->{throw new AssertionError(message);});
            ShaderGlRenderer gl=new ShaderGlRenderer(message->{throw new AssertionError(message);});
            ShaderCanvasLayer game=new ShaderCanvasLayer(W,H,handler,gameFrame::release);
            ShaderCanvasLayer hud=new ShaderCanvasLayer(W,H,handler,hudFrame::release)) {
            System.out.println("GPU "+GLES20.glGetString(GLES20.GL_RENDERER));
            System.out.println("Resolution "+W+"x"+H+"; "+FRAMES+" measured frames per chart/time after "+WARMUP+" warmup frames; glFinish included");
            for(String name:new String[]{"★ Ascension To Heaven.zip","ANTINEUTRINO WITCHCRAFT.zip","Hello (BPM) 2025.zip"}) {
                File workspace=new File(workspaces,name.replace(".zip",""));
                // Importer explicitly requires a new empty directory.
                if(workspace.exists()) delete(workspace);
                ChartPackage pack;
                try(FileInputStream stream=new FileInputStream(new File(args[1],name))) {
                    pack=new PackageImporter().importPackage(stream,workspace,name);
                }
                ChartDocument chart=pack.getChart();
                ChartEvaluator.prepare(chart,pack.isUseRpe170Speed());
                ShaderTimeline timeline=PreviewShaderLoader.load(pack,assets);
                baseline.prepare(timeline);
                gl.prepare(timeline);
                List<String> textureNames=new ArrayList<>();
                for(JudgeLine line:chart.judgeLines) textureNames.add(line.texture);
                Map<String,PreviewTextureDecoder.Texture> textures=PreviewTextureDecoder.decode(workspace,textureNames);
                renderer.setLineTextures(textures);
                EditorBackgroundDecoder.Background bg=EditorBackgroundDecoder.decodeForPreview(pack.getIllustrationFile(),W,H);
                Bitmap gameplay=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888), overlay=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);
                Canvas gameCanvas=new Canvas(gameplay), overlayCanvas=new Canvas(overlay);
                try {
                    double peak=0; int maximum=0;
                    for(ShaderTimeline.Effect candidate:timeline.effects) {
                        double time=(candidate.start+candidate.end)/2;
                        int count=0; for(ShaderTimeline.Effect effect:timeline.effects)if(effect.active(time))count++;
                        if(count>maximum) {maximum=count;peak=time;}
                    }
                    for(double seconds:new double[]{30,90,180,peak}) {
                        int active=0; for(ShaderTimeline.Effect effect:timeline.effects) if(effect.active(seconds)) active++;
                        for(boolean hardware:new boolean[]{false,true}) {
                        double[] millis=new double[FRAMES]; double drawTotal=0;
                        for(int frame=-WARMUP;frame<FRAMES;frame++) {
                            double time=seconds+Math.max(0,frame)/60.0;
                            long start=System.nanoTime();
                            RenderScene scene=ChartEvaluator.evaluate(chart,chart.millisToBeat(Math.round(time*1000)),true,-1,pack.isUseRpe170Speed(),1.5);
                            Canvas gc=gameCanvas, hc=overlayCanvas;
                            if(hardware) {gc=game.lock(true);hc=hud.lock(false);}
                            else {gameplay.eraseColor(Color.BLACK); overlay.eraseColor(Color.TRANSPARENT);}
                            renderer.drawShaderLayers(gc,hc,scene,VIEWPORT,AREA,bg==null?null:bg.blurred,settings,0,pack.getBackgroundDim());
                            if(hardware) {hud.post(hc);game.post(gc);}
                            long drawn=System.nanoTime();
                            if(hardware) {
                                if(!gameFrame.tryAcquire(5,TimeUnit.SECONDS)||!hudFrame.tryAcquire(5,TimeUnit.SECONDS))throw new AssertionError("Hardware buffer timeout");
                                game.update();hud.update();
                                gl.renderLayers(game,hud,W,H,timeline,time,VIEWPORT,W,H);
                            } else baseline.render(gameplay,overlay,timeline,time,VIEWPORT,W,H);
                            GLES20.glFinish();
                            if(frame>=0) {millis[frame]=(System.nanoTime()-start)/1e6;drawTotal+=(drawn-start)/1e6;}
                        }
                        print(hardware?"HARDWARE":"BITMAP",name,seconds,active,millis,drawTotal/FRAMES);
                        }
                    }
                } finally { gameplay.recycle(); overlay.recycle(); if(bg!=null)bg.recycle(); PreviewTextureDecoder.recycleAll(textures); }
            }
        } finally {callbacks.quitSafely();callbacks.join();}
    }
    static void print(String mode,String name,double seconds,int active,double[] millis,double draw) {
        double sum=0; for(double value:millis)sum+=value; Arrays.sort(millis);
        System.out.printf(Locale.ROOT,"%s %s t=%.0fs effects=%d mean=%.2fms p50=%.2fms p95=%.2fms canvas+evaluate=%.2fms\n",mode,name,seconds,active,sum/millis.length,millis[millis.length/2],millis[(int)(millis.length*.95)],draw);
    }
    static void delete(File file) { File[] children=file.listFiles(); if(children!=null)for(File child:children)delete(child); if(!file.delete())throw new IllegalStateException("Cannot delete test workspace "+file); }
}
