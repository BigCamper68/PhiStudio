package com.xpe.mobile.preview;

import com.xpe.mobile.model.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrprParityRegressionTest {
    private static final double UNIT = 100 / .83175;
    private static ChartDocument chart() {
        ChartDocument c = new ChartDocument();
        BpmChange first = new BpmChange(); first.bpm = 120; c.bpmChanges.add(first);
        BpmChange second = new BpmChange(); second.bpm = 60; second.startTime = beat(2); c.bpmChanges.add(second);
        c.judgeLines.add(new JudgeLine()); return c;
    }
    private static BeatTime beat(int value) { return new BeatTime(value,0,1); }
    private static LineEvent event(EventType type,double from,double to,int start,int end) {
        LineEvent e = new LineEvent(); e.type=type; e.start=from; e.end=to; e.startTime=beat(start); e.endTime=beat(end); return e;
    }
    private static void set(ChartDocument c,LineEvent e) {
        c.judgeLines.get(0).layer(0).events(e.type).clear(); c.judgeLines.get(0).layer(0).events(e.type).add(e); c.markEdited();
    }
    private static RenderScene.RenderLine frame(ChartDocument c,double beat) {
        return ChartEvaluator.evaluate(c,beat,false,-1,true).lines.get(0);
    }
    private static Note note(double x,int start) {
        Note n = new Note(); n.startTime=beat(start); n.endTime=n.startTime; n.positionX=x; return n;
    }
    @Test public void spatialAlphaAndRotationInterpolateInSecondsAcrossBpmChange() {
        ChartDocument c=chart(); set(c,event(EventType.MOVE_X,0,900,0,4));
        set(c,event(EventType.MOVE_Y,0,450,0,4)); set(c,event(EventType.ROTATE,0,90,0,4));
        set(c,event(EventType.ALPHA,0,255,0,4));
        RenderScene.RenderLine f=frame(c,2);
        assertEquals(300,f.x,.001); assertEquals(150,f.y,.001); assertEquals(-30,f.rotationDegrees,.001); assertEquals(85,f.alpha);
    }
    @Test public void speedUsesElapsedSecondsAndPreservesGaps() {
        ChartDocument c=chart(); set(c,event(EventType.SPEED,0,12,0,4));
        Note n=note(0,4); c.judgeLines.get(0).notes.add(n);
        // 3 seconds, v(t)=4t. At beat 2 (t=1), remaining area = 18-2.
        assertEquals(16*UNIT,frame(c,2).notes.get(0).startDistance,.002);
    }
    @Test public void speedEasingZeroIsStepEvenForPre170Charts() throws Exception {
        ChartDocument c=chart(); c.rpeVersion=160;
        LineEvent e=LineEvent.fromJson(EventType.SPEED,event(EventType.SPEED,2,8,0,4).toJson().put("easingType",0)); set(c,e);
        c.judgeLines.get(0).notes.add(note(0,4));
        assertEquals(6*UNIT,frame(c,0).notes.get(0).startDistance,.002);
    }
    @Test public void overlappingModernSpeedEventsStartAtPreviousEnd() {
        ChartDocument c=chart(); c.rpeVersion=170; set(c,event(EventType.SPEED,2,2,0,4));
        c.judgeLines.get(0).layer(0).events(EventType.SPEED).add(event(EventType.SPEED,4,8,2,6));
        c.judgeLines.get(0).notes.add(note(0,6));
        assertEquals(18*UNIT,frame(c,0).notes.get(0).startDistance,.002);
    }
    @Test public void zeroAreaModernTweenFallsBackToLinearLikePrpr() throws Exception {
        ChartDocument c=chart(); c.rpeVersion=170;
        set(c,LineEvent.fromJson(EventType.SPEED,event(EventType.SPEED,-1,2,0,4).toJson().put("easingType",5)));
        PrprSpeed speed=new PrprSpeed(c,c.judgeLines.get(0),6,true,new PrprTime(c));
        // integral -1+3t^2 is zero; prpr falls back to a linear-speed segment.
        assertEquals(-.5*UNIT,speed.distanceAt(2),.002);
    }
    @Test public void bezierWindowIsIgnoredInRendererButSourceIsPreserved() throws Exception {
        ChartDocument c=chart(); LineEvent e=event(EventType.MOVE_X,0,100,0,4); e.bezier=true;
        e.bezierPoints[0]=.25; e.bezierPoints[1]=.1; e.bezierPoints[2]=.25; e.bezierPoints[3]=1;
        e.easingLeft=.2; e.easingRight=.8; set(c,e);
        double expected=100*Easing.applyCubicBezier(1.0/3,.25,.1,.25,1);
        assertEquals(expected,frame(c,2).x,.001);
        ChartDocument reopened=ChartDocument.fromJson(c.toJsonString());
        assertEquals(expected,frame(reopened,2).x,.001);
        assertEquals(.2,reopened.judgeLines.get(0).layer(0).events(EventType.MOVE_X).get(0).easingLeft,0);
    }
    @Test public void scalePrerollAndExtendedTimingsUseSeconds() {
        ChartDocument c=chart(); ExtendedLineEvents ext=c.judgeLines.get(0).extended;
        ExtendedLineEvents.NumericEvent scale=new ExtendedLineEvents.NumericEvent(); scale.startTime=beat(2); scale.endTime=beat(4); scale.start=2; scale.end=6; ext.scaleXEvents.add(scale);
        assertEquals(0,frame(c,0).scaleX,.0001); // t=-1/2 => raw 0, then normal-line factor .5
        ExtendedLineEvents.NumericEvent incline=new ExtendedLineEvents.NumericEvent(); incline.startTime=beat(0); incline.endTime=beat(4); incline.start=0; incline.end=90; ext.inclineEvents.add(incline);
        assertEquals(30,frame(c,2).inclineDegrees,.001);
    }
    @Test public void textColorAndGifUseSeconds() {
        ChartDocument c=chart(); ExtendedLineEvents ext=c.judgeLines.get(0).extended;
        ExtendedLineEvents.TextEvent text=new ExtendedLineEvents.TextEvent(); text.start=""; text.end="ABCDEF"; text.endTime=beat(4); ext.textEvents.add(text);
        ExtendedLineEvents.ColorEvent color=new ExtendedLineEvents.ColorEvent(); color.startRgb=0; color.endRgb=0xFFFFFF; color.endTime=beat(4); ext.colorEvents.add(color);
        assertEquals("AB",frame(c,2).text); assertEquals(0x555555,frame(c,2).colorRgb);
        c.judgeLines.get(0).texture="test.gif"; ExtendedLineEvents.NumericEvent gif=new ExtendedLineEvents.NumericEvent(); gif.end=1; gif.endTime=beat(4); ext.gifEvents.add(gif);
        assertEquals(1.0/3,frame(c,2).gifProgress,.000001);
    }
    @Test public void invisibleGeometryStillJudgesAndNegativeSizeIsKept() {
        ChartDocument c=chart(); Note n=note(0,4); n.size=-1.5; c.judgeLines.get(0).notes.add(n);
        assertEquals(-1.5,frame(c,0).notes.get(0).size,0);
        set(c,event(EventType.ALPHA,-255,-255,0,4));
        assertTrue(frame(c,4).notes.isEmpty()); assertEquals(1,frame(c,4).hitEffects.size());
    }
    @Test public void realTapDisappearsAtJudgementAndHoldEmitsCompletionEffect() {
        ChartDocument c=chart(); c.judgeLines.get(0).notes.add(note(0,2));
        assertTrue(frame(c,2).notes.isEmpty()); assertEquals(1,frame(c,2).hitEffects.size());
        Note hold=note(0,2); hold.type=NoteType.HOLD; hold.endTime=beat(3);
        c.judgeLines.get(0).notes.clear(); c.judgeLines.get(0).notes.add(hold); c.markEdited();
        assertTrue(frame(c,3).notes.isEmpty());
        assertEquals(0,frame(c,3).hitEffects.get(0).progress,0);
    }

    @Test public void controlsExtrapolateBeforeFirstHeightAndUsePrprBackCurve() {
        java.util.List<NoteControlEvents.ControlEvent> controls=new java.util.ArrayList<>();
        NoteControlEvents.ControlEvent a=new NoteControlEvents.ControlEvent(); a.value=2; a.x=0;
        NoteControlEvents.ControlEvent b=new NoteControlEvents.ControlEvent(); b.value=4; b.x=100; b.easing=5;
        controls.add(a); controls.add(b);
        assertEquals(2.02,PrprEvents.control(controls,-10,1),1e-7);
        b.easing=23;
        assertEquals(2+2*PrprEasing.value(23,.25f),PrprEvents.control(controls,25,1),1e-7);
    }

    @Test public void alpha256IsClampedBeforeControl() {
        ChartDocument c=chart(); Note n=note(0,4); n.alpha=256; c.judgeLines.get(0).notes.add(n);
        NoteControlEvents.ControlEvent alpha=new NoteControlEvents.ControlEvent(); alpha.value=.8;
        c.judgeLines.get(0).noteControls.alpha.add(alpha);
        assertEquals(204,frame(c,0).notes.get(0).alpha);
    }
    @Test public void distantLocalNoteCanBeBroughtOnscreenByLineTranslation() {
        ChartDocument c=chart(); set(c,event(EventType.MOVE_Y,-10000,-10000,0,4));
        set(c,event(EventType.SPEED,0,0,0,4)); Note n=note(0,4); n.yOffset=10000; c.judgeLines.get(0).notes.add(n);
        assertEquals(1,frame(c,0).notes.size()); assertEquals(0,frame(c,0).y+frame(c,0).notes.get(0).startDistance,.001);
    }
    @Test public void saveDoesNotInsertSpeedOrSecondarySpatialEvents() throws Exception {
        ChartDocument c=chart(); set(c,event(EventType.SPEED,3,3,2,4));
        c.judgeLines.get(0).layer(1).events(EventType.MOVE_X).add(event(EventType.MOVE_X,10,20,2,4));
        JSONObject encoded=new JSONObject(c.toJsonString()).getJSONArray("judgeLineList").getJSONObject(0);
        JSONArray layers=encoded.getJSONArray("eventLayers");
        assertEquals(1,layers.getJSONObject(0).getJSONArray("speedEvents").length());
        assertEquals(1,layers.getJSONObject(1).getJSONArray("moveXEvents").length());
        ChartDocument restored=ChartDocument.fromJson(c.toJsonString()); assertEquals(frame(c,0).x,frame(restored,0).x,0);
    }
}
