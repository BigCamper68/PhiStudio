package com.xpe.mobile.preview;

import android.graphics.*;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.NoteTextureSet;
import com.xpe.mobile.model.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk=34)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PrprCanvasGeometryTest {
    static class Recorded extends Canvas {
        final List<RectF> images=new ArrayList<>();
        final List<float[]> scales=new ArrayList<>();
        final List<RectF> circles=new ArrayList<>();
        final List<RectF> squares=new ArrayList<>();
        final List<float[]> origins=new ArrayList<>();
        @Override public void drawBitmap(Bitmap bitmap,Rect src,RectF dst,Paint paint) { images.add(new RectF(dst)); }
        @Override public void scale(float x,float y) { scales.add(new float[]{x,y}); }
        @Override public void drawOval(float l,float t,float r,float b,Paint p) { circles.add(new RectF(l,t,r,b)); }
        @Override public void drawRect(float l,float t,float r,float b,Paint p) { squares.add(new RectF(l,t,r,b)); }
        @Override public void translate(float x,float y) { origins.add(new float[]{x,y}); }
    }
    private Recorded draw(ChartDocument chart,int width,int height) {
        var app=RuntimeEnvironment.getApplication();
        PreviewRenderer renderer=new PreviewRenderer(new NoteTextureSet(app.getResources()),1,"",app.getAssets());
        Recorded canvas=new Recorded();
        renderer.drawGameplayOverlay(canvas,ChartEvaluator.evaluate(chart,0,false,-1,true,width/(double)height),new RectF(0,0,width,height),new EditorSettings(),-1);
        return canvas;
    }
    private ChartDocument chart(NoteType type,boolean above,double size,boolean uniform) {
        ChartDocument c=new ChartDocument(); c.bpmChanges.add(new BpmChange()); c.noteUniformScale=uniform;
        JudgeLine l=new JudgeLine(); c.judgeLines.add(l);
        Note n=new Note(); n.type=type; n.startTime=new BeatTime(1,0,1); n.endTime=new BeatTime(2,0,1);
        n.above=above?1:0; n.size=size; l.notes.add(n); return c;
    }
    private Recorded drawScaled(ChartDocument chart,double scale,int mode) {
        var app=RuntimeEnvironment.getApplication();
        var textures=new NoteTextureSet(app.getResources());
        PreviewRenderer renderer=new PreviewRenderer(textures,1,"",app.getAssets());renderer.setShowHud(false);
        EditorSettings settings=new EditorSettings();settings.previewNoteScale=scale;settings.markLineId=false;
        Recorded game=new Recorded(),back=new Recorded(),overlay=new Recorded();
        RenderScene scene=ChartEvaluator.evaluateAtSeconds(chart,0,false);
        RectF viewport=new RectF(0,0,1350,900);
        switch(mode) {
            case 0 -> renderer.draw(game,scene,viewport,null,settings,-1);
            case 1 -> renderer.drawGameplayOverlay(game,scene,viewport,settings,-1);
            case 2 -> renderer.drawShaderLayers(game,overlay,scene,viewport,viewport,null,settings,-1,.6);
            case 3 -> renderer.drawSeparatedShaderLayers(back,game,overlay,scene,viewport,viewport,null,settings,-1,.6);
            case 4 -> renderer.drawEditorShaderLayers(back,game,overlay,scene,viewport,viewport,null,settings,-1);
        }
        renderer.release();textures.recycle();return game;
    }
    @Test public void phiraPreviewScaleChangesSpritesAndHoldCapsAcrossEveryRendererEntry() {
        for(int mode=0;mode<5;mode++)for(NoteType type:NoteType.values())for(boolean uniform:new boolean[]{false,true}) {
            ChartDocument c=chart(type,false,-1.7,uniform);
            Recorded base=drawScaled(c,1,mode);
            for(double scale:new double[]{.8,1.2}) {
                Recorded actual=drawScaled(c,scale,mode);assertEquals(base.images.size(),actual.images.size());
                for(int i=0;i<base.images.size();i++) {
                    assertEquals(base.images.get(i).width()*scale,actual.images.get(i).width(),.001);
                    assertEquals(base.images.get(i).height()*(type==NoteType.HOLD&&i==0?1:scale),actual.images.get(i).height(),.001);
                }
                assertEquals(base.origins.size(),actual.origins.size());
                for(int i=0;i<base.origins.size();i++)assertArrayEquals(base.origins.get(i),actual.origins.get(i),0);
                for(int i=0;i<base.scales.size();i++)assertArrayEquals(base.scales.get(i),actual.scales.get(i),0);
            }
        }
    }
    @Test public void phiraPreviewScaleAlsoScalesHitSpritesSquareSizeAndTravel() {
        var app=RuntimeEnvironment.getApplication();var textures=new NoteTextureSet(app.getResources());
        PreviewRenderer renderer=new PreviewRenderer(textures,1,"",app.getAssets());
        ChartDocument c=chart(NoteType.TAP,true,1,false);c.judgeLines.get(0).notes.get(0).startTime=BeatTime.zero();
        RenderScene scene=ChartEvaluator.evaluateAtSeconds(c,.1,false);RectF viewport=new RectF(0,0,1350,900);
        List<Recorded> draws=new ArrayList<>();
        for(double scale:new double[]{1,.8,1.2}) {
            EditorSettings settings=new EditorSettings();settings.previewNoteScale=scale;
            renderer.drawGameplayOverlay(new Canvas(),scene,viewport,settings,-1);
            Recorded recorded=new Recorded();renderer.previewHitEffects.drawHitEffects(recorded,new PreviewRenderer.Projection(viewport),scene);draws.add(recorded);
        }
        assertEquals(1,draws.get(0).images.size());assertEquals(4,draws.get(0).squares.size());
        for(int i=1;i<3;i++) {
            double scale=i==1?.8:1.2;Recorded base=draws.get(0),actual=draws.get(i);
            assertEquals(base.images.get(0).width()*scale,actual.images.get(0).width(),.001);
            for(int k=0;k<4;k++) {
                RectF b=base.squares.get(k),a=actual.squares.get(k);
                assertEquals(b.width()*scale,a.width(),.001);
                assertEquals((b.centerX()-675)*scale,a.centerX()-675,.001);
                assertEquals((b.centerY()-450)*scale,a.centerY()-450,.001);
            }
        }
        renderer.release();textures.recycle();
    }
    @Test public void holdCapsExtendOutwardFromBody() {
        Recorded draw=draw(chart(NoteType.HOLD,true,1,false),1350,900);
        assertEquals(3,draw.images.size());
        RectF body=draw.images.get(0),tail=draw.images.get(1),head=draw.images.get(2);
        assertEquals(body.top,tail.bottom,.0001f); assertTrue(tail.top<body.top);
        assertEquals(body.bottom,head.top,.0001f); assertTrue(head.bottom>body.bottom);
    }
    @Test public void belowNotesReflectOnlyYAndNegativeSizeReflectsX() {
        for(NoteType type:NoteType.values()) {
            Recorded draw=draw(chart(type,false,-2,false),1350,900);
            assertFalse(draw.images.isEmpty());
            assertArrayEquals(new float[]{-1,-1},draw.scales.get(draw.scales.size()-1),0);
            Recorded ordinary=draw(chart(type,false,2,false),1350,900);
            assertArrayEquals(new float[]{1,-1},ordinary.scales.get(ordinary.scales.size()-1),0);
        }
    }
    @Test public void uniformOptionScalesNoteHeightAndHoldBody() {
        for(NoteType type:NoteType.values()) {
            Recorded draw=draw(chart(type,true,2,true),1350,900);
            assertFalse(draw.images.isEmpty());
            assertArrayEquals(new float[]{1,2},draw.scales.get(draw.scales.size()-1),0);
        }
    }
    @Test public void paintRadiusStaysInPixelsAcrossViewports() {
        ChartDocument c=chart(NoteType.TAP,true,1,false); c.judgeLines.get(0).notes.clear();
        ExtendedLineEvents.NumericEvent paint=new ExtendedLineEvents.NumericEvent(); paint.start=paint.end=6;
        c.judgeLines.get(0).extended.paintEvents.add(paint);
        for(int width:new int[]{900,1350,1920,2560}) {
            Recorded draw=draw(c,width,900); assertEquals(1,draw.circles.size());
            assertEquals(12,draw.circles.get(0).width(),.0001f); assertEquals(12,draw.circles.get(0).height(),.0001f);
        }
    }
}
