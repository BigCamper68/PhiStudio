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
        @Override public void drawBitmap(Bitmap bitmap,Rect src,RectF dst,Paint paint) { images.add(new RectF(dst)); }
        @Override public void scale(float x,float y) { scales.add(new float[]{x,y}); }
        @Override public void drawOval(float l,float t,float r,float b,Paint p) { circles.add(new RectF(l,t,r,b)); }
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
