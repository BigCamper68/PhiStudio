package com.xpe.mobile.preview;

import com.xpe.mobile.model.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrprPlaybackPatchTest {
    static ChartDocument chart() {
        ChartDocument c = new ChartDocument(); c.bpmChanges.add(new BpmChange());
        c.judgeLines.add(new JudgeLine()); return c;
    }
    static LineEvent event(EventType type, int start, int end, double from, double to) {
        LineEvent e = new LineEvent(); e.type = type;
        e.startTime = new BeatTime(start,0,1); e.endTime = new BeatTime(end,0,1);
        e.start = from; e.end = to; return e;
    }
    static ExtendedLineEvents.NumericEvent paint(int start, int end, double radius) {
        var e = new ExtendedLineEvents.NumericEvent();
        e.startTime = new BeatTime(start,0,1); e.endTime = new BeatTime(end,0,1);
        e.start = e.end = radius; return e;
    }
    static RenderScene frame(ChartDocument c, double beat, PreviewPlaybackState state) {
        return ChartEvaluator.evaluate(c,beat,false,-1,true,1.5,state);
    }
    static int count(PaintHistory history) {
        int n=0; for(;history!=null;history=history.previous) if(history.stroke!=null)n++;
        return n;
    }
    @Test public void continuousPaintAddsOneMarkPerFrameAndPausedFrameDoesNotDarken() {
        ChartDocument c=chart(); c.judgeLines.get(0).extended.paintEvents.add(paint(0,10,6));
        PreviewPlaybackState playback=new PreviewPlaybackState();
        PaintHistory a=frame(c,0,playback).paintHistories.get(0);
        PaintHistory b=frame(c,.02,playback).paintHistories.get(0);
        assertSame(a,b.previous); assertEquals(2,count(b));
        assertSame(b,frame(c,.02,playback).paintHistories.get(0));
        assertEquals(1,count(a)); // Previous immutable frame stays unchanged.
    }
    @Test public void negativeAlphaPreservesOldPaintButNonPositiveBrushClearsIt() {
        ChartDocument c=chart(); JudgeLine line=c.judgeLines.get(0);
        line.extended.paintEvents.add(paint(0,2,6)); line.extended.paintEvents.add(paint(2,3,0));
        var alpha=line.eventLayers.get(0).events(EventType.ALPHA);alpha.clear();
        alpha.add(event(EventType.ALPHA,0,1,100,100));alpha.add(event(EventType.ALPHA,1,3,-1,-1));
        RenderScene hidden=frame(c,1.5,null);
        assertEquals(1,hidden.lines.size());
        assertTrue(hidden.lines.get(0).paintStrokes.stream().anyMatch(s->s.alpha>0));
        assertEquals(0,hidden.lines.get(0).alpha);
        assertTrue(frame(c,2,null).lines.get(0).paintStrokes.isEmpty());
        PreviewPlaybackState playback=new PreviewPlaybackState();
        frame(c,1.9,playback);
        assertEquals(0,count(frame(c,2,playback).paintHistories.get(0)));
    }
    @Test public void seekAndChartSwitchDiscardPreviousPaintRoots() {
        ChartDocument c=chart(); c.judgeLines.get(0).extended.paintEvents.add(paint(0,10,6));
        PreviewPlaybackState playback=new PreviewPlaybackState();
        PaintHistory first=frame(c,0,playback).paintHistories.get(0);
        frame(c,.1,playback); playback.reset();
        PaintHistory reset=frame(c,0,playback).paintHistories.get(0);
        assertNotSame(first,reset); assertEquals(1,count(reset));
        ChartDocument other=chart(); other.judgeLines.get(0).extended.paintEvents.add(paint(0,10,9));
        assertEquals(9,frame(other,0,playback).paintHistories.get(0).stroke.radius,0);
    }
    @Test public void longPaintHistoryIsNotResampledAt2400Marks() {
        ChartDocument c=chart();c.judgeLines.get(0).extended.paintEvents.add(paint(0,200,6));
        var early=frame(c,100,null).lines.get(0).paintStrokes;
        var late=frame(c,101,null).lines.get(0).paintStrokes;
        assertTrue(early.size()>2400); assertTrue(late.size()>early.size());
        for(int i=0;i<early.size();i++) assertEquals(early.get(i).alpha,late.get(i).alpha);
    }
    @Test public void emptyExtendedChannelsStillSelectTheLineKind() throws Exception {
        ChartDocument c=chart(); JudgeLine l=c.judgeLines.get(0);
        l.extended=ExtendedLineEvents.fromJson(new JSONObject().put("textEvents",new JSONArray()));
        assertEquals("",frame(c,0,null).lines.get(0).text);
        l.extended=ExtendedLineEvents.fromJson(new JSONObject().put("paintEvents",new JSONArray()));
        assertTrue(frame(c,0,null).lines.get(0).paintMode);
        l.texture="animated.gif";
        l.extended=ExtendedLineEvents.fromJson(new JSONObject().put("gifEvents",new JSONArray()));
        assertTrue(frame(c,0,null).lines.get(0).gifEnabled);
    }
    @Test public void overlappingEventsWaitForThePreviousEndKeyframe() {
        ChartDocument c=chart(); JudgeLine l=c.judgeLines.get(0);
        var x=l.eventLayers.get(0).events(EventType.MOVE_X); x.clear();
        x.add(event(EventType.MOVE_X,0,4,0,400));x.add(event(EventType.MOVE_X,1,2,1000,2000));
        assertEquals(200,frame(c,2,null).lines.get(0).x,1e-5);
        assertEquals(2000,frame(c,4,null).lines.get(0).x,1e-5);
        var a=paint(0,4,0);a.end=400;var b=paint(1,2,1000);b.end=2000;
        l.extended.paintEvents.add(a);l.extended.paintEvents.add(b);
        assertEquals(200,PrprEvents.numeric(l.extended.paintEvents,new PrprTime(c),2,-1,false),1e-5);
    }
    @Test public void particleCurveAndDiscreteDragMatchEmitterParameters() {
        assertEquals(1,PrprParticleMotion.opacity(0),0);
        assertEquals(.7,PrprParticleMotion.opacity(.5f),1e-6);
        assertEquals(.35,PrprParticleMotion.opacity(.75f),1e-6);
        assertEquals(0,PrprParticleMotion.opacity(1),0);
        PrprParticleMotion motion=new PrprParticleMotion();
        motion.advance(0); motion.advance(.02);
        assertEquals(2.5*(1-6*.02)*.02,motion.distance,1e-12);
        motion.advance(.04);
        assertEquals(2.5*.88*.02+2.5*.88*.88*.02,motion.distance,1e-12);
        double previous=motion.distance;motion.advance(.04);assertEquals(previous,motion.distance,0);
    }
}
