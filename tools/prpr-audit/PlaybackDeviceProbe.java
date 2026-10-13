package com.xpe.mobile.preview;

import android.content.res.*;
import android.graphics.*;
import android.util.DisplayMetrics;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.NoteTextureSet;
import com.xpe.mobile.model.*;
import java.io.*;

/** Uses Android's actual Canvas/Bitmap; no APK install or foreground Activity needed. */
public final class PlaybackDeviceProbe {
    static void require(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
    static LineEvent event(int start,int end,double alpha) {
        LineEvent e=new LineEvent();e.type=EventType.ALPHA;
        e.startTime=new BeatTime(start,0,1);e.endTime=new BeatTime(end,0,1);
        e.start=e.end=alpha;return e;
    }
    static ExtendedLineEvents.NumericEvent brush(int start,int end,double radius) {
        var e=new ExtendedLineEvents.NumericEvent();e.startTime=new BeatTime(start,0,1);
        e.endTime=new BeatTime(end,0,1);e.start=e.end=radius;return e;
    }
    static ChartDocument chart() {
        ChartDocument c=new ChartDocument();c.bpmChanges.add(new BpmChange());
        JudgeLine l=new JudgeLine();c.judgeLines.add(l);
        l.eventLayers.get(0).events(EventType.ALPHA).clear();
        l.eventLayers.get(0).events(EventType.ALPHA).add(event(0,1,10));
        l.eventLayers.get(0).events(EventType.ALPHA).add(event(1,3,-1));
        l.extended.paintEvents.add(brush(0,2,20));l.extended.paintEvents.add(brush(2,3,0));
        return c;
    }
    static int frame(PreviewRenderer renderer,Bitmap bitmap,ChartDocument chart,PreviewPlaybackState state,double beat) {
        bitmap.eraseColor(Color.TRANSPARENT);
        RenderScene scene=ChartEvaluator.evaluate(chart,beat,false,-1,true,
                bitmap.getWidth()/(double)bitmap.getHeight(),state);
        renderer.drawGameplayOverlay(new Canvas(bitmap),scene,new RectF(0,0,bitmap.getWidth(),bitmap.getHeight()),new EditorSettings(),-1);
        return bitmap.getPixel(bitmap.getWidth()/2,bitmap.getHeight()/2);
    }
    static final class Recorded extends Canvas {
        final java.util.List<String> order=new java.util.ArrayList<>();
        final java.util.List<Integer> alphas=new java.util.ArrayList<>();
        final java.util.List<Float> sizes=new java.util.ArrayList<>();
        int rotations;
        @Override public void rotate(float degrees) { rotations++; }
        @Override public void drawBitmap(Bitmap b,Rect src,RectF dst,Paint p) {
            order.add("sprite");alphas.add(p.getAlpha());
        }
        @Override public void drawRect(float l,float t,float r,float b,Paint p) {
            order.add("square");sizes.add(r-l);
        }
    }
    static Recorded particles(PreviewRenderer renderer,double progress) {
        var effects=new java.util.ArrayList<RenderScene.HitEffect>();
        effects.add(new RenderScene.HitEffect(0,0,0,0,progress,0xFFEC9F,123));
        effects.add(new RenderScene.HitEffect(10,0,10,0,progress,0xFFEC9F,456));
        var line=new RenderScene.RenderLine(0,0,0,0,0,0,-1,1,1,0,null,"line.png",false,true,
                false,false,0,0,java.util.Collections.emptyList(),effects,java.util.Collections.emptyList());
        var scene=new RenderScene(0,0,java.util.Collections.singletonList(line),null);
        Recorded canvas=new Recorded();
        renderer.drawGameplayOverlay(canvas,scene,new RectF(0,0,1350,900),new EditorSettings(),-1);
        return canvas;
    }
    public static void main(String[] args) {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out)));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err)));
        try {
            Typeface.class.getMethod("loadPreinstalledSystemFontMap").invoke(null);
            AssetManager assets=AssetManager.class.getDeclaredConstructor().newInstance();
            AssetManager.class.getMethod("addAssetPath",String.class).invoke(assets,args[0]);
            DisplayMetrics metrics=new DisplayMetrics();metrics.setToDefaults();
            Resources resources=new Resources(assets,metrics,new Configuration());
            PreviewRenderer renderer=new PreviewRenderer(new NoteTextureSet(resources),1,"AUTOPLAY",assets);
            for(int width:new int[]{900,1350,1920}) {
                Bitmap bitmap=Bitmap.createBitmap(width,900,Bitmap.Config.ARGB_8888);
                ChartDocument chart=chart();PreviewPlaybackState state=new PreviewPlaybackState();
                int first=frame(renderer,bitmap,chart,state,0);
                require(Math.abs(Color.alpha(first)-26)<=1,"first brush alpha at "+width+": "+Color.alpha(first));
                require((first&0xffffff)==0xffffff,"paint uses white, not AP line color");
                int second=frame(renderer,bitmap,chart,state,.02);
                require(Color.alpha(second)>Color.alpha(first),"paint accumulates on a new frame");
                require(second==frame(renderer,bitmap,chart,state,.02),"pause must not add another mark");
                require(Color.alpha(frame(renderer,bitmap,chart,state,1.1))>0,"negative line alpha must retain framebuffer");
                require(Color.alpha(frame(renderer,bitmap,chart,state,2))==0,"radius zero clears framebuffer");
                state.reset();
                require(first==frame(renderer,bitmap,chart,state,0),"restart clears old framebuffer");
                // Static reconstruction and stateful rendering use the same radius in pixels.
                require(Color.alpha(bitmap.getPixel(width/2+18,450))>0,"20px brush inner pixel");
                require(Color.alpha(bitmap.getPixel(width/2+22,450))==0,"20px brush outer pixel");
                bitmap.recycle();
                System.out.println("PASS native Canvas paint: "+width+"x900, accumulation, pause, negative alpha, clear, restart, pixel radius");
            }
            Recorded early=particles(renderer,.25),late=particles(renderer,.75);
            require(early.order.size()==10,"two sprites and eight squares");
            require(early.order.get(0).equals("sprite")&&early.order.get(1).equals("sprite"),"all sprites precede squares");
            require(early.rotations==0&&late.rotations==0,"squares are not rotated");
            require(Math.abs(early.alphas.get(0)-191)<=1&&Math.abs(late.alphas.get(0)-79)<=1,"sprite fade curve includes base alpha");
            for(int i=0;i<8;i++)require(Math.abs(early.sizes.get(i)-late.sizes.get(i))<.001,"square sizes stay constant");
            System.out.println("PASS native Canvas particle commands: sprite/square ordering, fade, fixed square size, no rotation");
            System.out.println("PASS Android API "+android.os.Build.VERSION.SDK_INT+"; renderer executed without installing APK");
            System.exit(0);
        } catch(Throwable t) {t.printStackTrace();System.exit(1);}
    }
}
