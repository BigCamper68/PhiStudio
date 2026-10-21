package com.xpe.mobile.preview;

import com.xpe.mobile.model.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class NoiseFieldTest {
    public static JSONObject area(boolean subtract,double appear,double enable,double disable,double disappear) throws Exception {
        return new JSONObject().put("bottomLeftPercentage",point(.25,.25)).put("topRightPercentage",point(.75,.75))
                .put("appearTime",appear).put("enableTime",enable).put("disableTime",disable).put("disappearTime",disappear)
                .put("isSubtract",subtract).put("moveEvents",new JSONArray()).put("scaleEvents",new JSONArray()).put("rotateEvents",new JSONArray());
    }
    public static JSONObject point(double x,double y)throws Exception{return new JSONObject().put("x",x).put("y",y);}
    public static List<NoiseField> fields(JSONObject... areas)throws Exception {return NoiseField.fromJson(new JSONArray(Arrays.asList(areas)));}
    @Test public void allNativePhasesUseHalfOpenSecondIntervals()throws Exception{
        var fields=fields(area(false,1,2,3,4));
        assertTrue(NoiseFieldEvaluator.evaluate(fields,.999,1.5).zones.isEmpty());
        var first=NoiseFieldEvaluator.evaluate(fields,1,1.5).zones.get(0);assertFalse(first.active);assertFalse(first.ready);assertEquals(0,first.opacity,0);
        var ready=NoiseFieldEvaluator.evaluate(fields,1.5,1.5).zones.get(0);assertFalse(ready.active);assertTrue(ready.ready);assertEquals(1,ready.opacity,0);
        assertTrue(NoiseFieldEvaluator.evaluate(fields,2,1.5).zones.get(0).active);
        assertFalse(NoiseFieldEvaluator.evaluate(fields,3,1.5).zones.get(0).active);
        assertFalse(NoiseFieldEvaluator.evaluate(fields,3.9,1.5).zones.get(0).ready);
        assertTrue(NoiseFieldEvaluator.evaluate(fields,4,1.5).zones.isEmpty());
        assertEquals(1,NoiseFieldEvaluator.evaluate(fields(area(false,2,2,3,4)),2,1.5).zones.get(0).opacity,0);
    }
    @Test public void nativeLookupCurvesArePowersWithOnePercentInterpolation() {
        assertEquals(.25,NoiseFieldEvaluator.ease(1,.5f),0);
        assertEquals(.75,NoiseFieldEvaluator.ease(2,.5f),0);
        assertEquals(.125,NoiseFieldEvaluator.ease(4,.5f),0);
        assertEquals(.0625,NoiseFieldEvaluator.ease(7,.5f),0);
        assertEquals(.03125,NoiseFieldEvaluator.ease(10,.5f),0);
        assertEquals((.12f*.12f+.13f*.13f)*.5f,NoiseFieldEvaluator.ease(1,.125f),1e-7);
        for(int type=0;type<15;type++) {
            assertEquals(type==14?1:0,NoiseFieldEvaluator.ease(type,0),0);
            assertEquals(type==13?0:1,NoiseFieldEvaluator.ease(type,1),0);
        }
        assertEquals(0,NoiseFieldEvaluator.ease(13,.9f),0);assertEquals(1,NoiseFieldEvaluator.ease(14,.01f),0);
    }
    @Test public void precedingKeyframeOwnsEasingAndZeroDoesNotTween()throws Exception {
        JSONObject a=area(false,0,0,10,10);
        a.put("moveEvents",new JSONArray().put(new JSONObject().put("endPosition",point(.5,.5)).put("time",1).put("easeTypeX",13).put("easeTypeY",0))
                .put(new JSONObject().put("endPosition",point(1,.5)).put("time",3).put("easeTypeX",0).put("easeTypeY",0)));
        var fields=fields(a);
        assertEquals(0,NoiseFieldEvaluator.evaluate(fields,.5,1.5).zones.get(0).x,0);
        assertEquals(0,NoiseFieldEvaluator.evaluate(fields,2,1.5).zones.get(0).x,0);
        assertEquals(1,NoiseFieldEvaluator.evaluate(fields,3,1.5).zones.get(0).x,1e-6);
    }
    @Test public void transformsPreserveScreenSpaceAnchorsAtEveryAspectAndAfterReverseSeek()throws Exception {
        JSONObject a=area(false,0,0,10,10);
        a.put("scaleEvents",new JSONArray().put(scale(0,1,1,.25,.25)).put(scale(2,2,2,.75,.75)));
        a.put("rotateEvents",new JSONArray().put(rotate(0,0,.25,.25)).put(rotate(2,90,.75,.75)));
        a.put("moveEvents",new JSONArray().put(new JSONObject().put("time",0).put("endPosition",point(.6,.4))));
        var fields=fields(a);
        for(double aspect:new double[]{1.5,16.0/9,21.0/9}) {
            var end=NoiseFieldEvaluator.evaluate(fields,2,aspect).zones.get(0);
            assertEquals(-.3f-1f/(float)aspect,end.x,1e-6);
            assertEquals(1-.7f/(float)aspect,end.y,1e-6);
            assertEquals(2,end.width,1e-6);assertEquals(2/aspect,end.height,1e-6);assertEquals(90,end.rotation,0);
            var before=NoiseFieldEvaluator.evaluate(fields,.75,aspect).zones.get(0);
            NoiseFieldEvaluator.evaluate(fields,8,aspect);
            assertArrayEquals(before.corners(),NoiseFieldEvaluator.evaluate(fields,.75,aspect).zones.get(0).corners(),0);
        }
    }
    @Test public void zeroScaleSafeDivisionAndLastDuplicateKeyframeStayFinite()throws Exception {
        JSONObject a=area(false,0,0,10,10);
        a.put("scaleEvents",new JSONArray().put(scale(0,0,0,.1,.1)).put(scale(1,1,1,.1,.1)).put(scale(1,-2,-3,.9,.9)));
        var fields=fields(a);assertTrue(NoiseFieldEvaluator.evaluate(fields,0,1.5).zones.isEmpty());
        var z=NoiseFieldEvaluator.evaluate(fields,1,1.5).zones.get(0);
        assertEquals(2,z.width,1e-6);assertEquals(2,z.height,1e-6);
        for(float v:z.corners())assertTrue(Float.isFinite(v));
    }
    @Test public void visualSubtractionAndTouchParityAreSeparateAndInsetProtectsEdges()throws Exception {
        var normal=area(false,0,0,10,10);var sub=area(true,0,0,10,10);
        assertTrue(NoiseFieldEvaluator.evaluate(fields(normal),1,1.5).blocks(0,0));
        assertFalse(NoiseFieldEvaluator.evaluate(fields(normal),1,1.5).blocks(.49f,0));
        assertFalse(NoiseFieldEvaluator.evaluate(fields(normal,sub),1,1.5).blocks(0,0));
        assertTrue(NoiseFieldEvaluator.evaluate(fields(normal,sub,sub),1,1.5).blocks(0,0));
        assertFalse(NoiseFieldEvaluator.evaluate(fields(normal,sub,sub,sub),1,1.5).blocks(0,0));
        assertTrue(NoiseFieldEvaluator.evaluate(fields(sub),1,1.5).blocks(0,0));
        assertFalse(NoiseFieldEvaluator.evaluate(fields(sub,sub),1,1.5).blocks(0,0));
        assertFalse(NoiseFieldEvaluator.evaluate(fields(area(false,0,2,3,4)),1.75,1.5).blocks(0,0));
    }
    @Test public void infectedFingerStaysBlockedUntilReleaseAndTouchHaloFades()throws Exception {
        NoiseFieldInput input=new NoiseFieldInput(fields(area(false,0,0,1,1)),1.5);
        assertFalse(input.event(7,PlaytestJudge.Phase.DOWN,0,0,.2));
        assertTrue(input.touching());assertEquals(1,input.frame(.3).touches.size());
        assertEquals(1,input.frame(.3).touches.get(0).scale,1e-6);
        assertFalse(input.event(7,PlaytestJudge.Phase.MOVE,1,0,.4));
        assertTrue(input.advance(2).contains(7));
        assertFalse(input.event(7,PlaytestJudge.Phase.UP,1,0,2));assertFalse(input.touching());
        assertFalse(input.frame(2.05).touches.isEmpty());assertTrue(input.frame(2.11).touches.isEmpty());
        assertTrue(input.event(7,PlaytestJudge.Phase.DOWN,0,0,3));input.clear();assertFalse(input.touching());
    }
    @Test public void stationaryTouchBecomesBlockedWhenAreaActivates()throws Exception {
        NoiseFieldInput input=new NoiseFieldInput(fields(area(false,0,1,2,3)),16.0/9);
        assertTrue(input.event(1,PlaytestJudge.Phase.DOWN,0,0,.8));
        assertTrue(input.advance(.99).isEmpty());assertTrue(input.advance(1).contains(1));
        assertTrue(input.advance(2.5).contains(1));input.clear();assertTrue(input.advance(2.5).isEmpty());
    }
    @Test public void playtestDropsBlockedTapButAnotherFingerCanHitOutsideField()throws Exception {
        ChartDocument c=ChartDocument.fromJson("{}");c.noiseFields.addAll(fields(area(false,0,0,10,10)));
        var line=c.judgeLines.get(0);line.notes.clear();
        for(double x:new double[]{0,540}){Note n=new Note();n.positionX=x;n.startTime=n.endTime=new BeatTime(2,0,1);line.notes.add(n);}
        PlaytestJudge judge=new PlaytestJudge(c,false,1.5,0);
        judge.queue(1,PlaytestJudge.Phase.DOWN,0,0,0,0,1);
        judge.queue(2,PlaytestJudge.Phase.DOWN,.8,0,.8,0,1);judge.update(1);
        assertEquals(1,judge.count(PlaytestJudge.Grade.PERFECT));assertTrue(judge.touchingNoiseField());
        judge.update(1.3);assertEquals(1,judge.count(PlaytestJudge.Grade.MISS));
        assertTrue(ChartEvaluator.evaluatePlaytest(c,1.3,false,-1,false,1.5,null,judge).noiseFields.visible());
        judge.clearTouches();assertFalse(judge.touchingNoiseField());
    }
    @Test public void activationUnderHeldFingerBreaksHoldAndPauseClearsInfection()throws Exception {
        ChartDocument c=ChartDocument.fromJson("{}");
        c.noiseFields.addAll(fields(area(false,0,1.5,5,5)));
        Note hold=new Note();hold.type=NoteType.HOLD;hold.startTime=new BeatTime(2,0,1);hold.endTime=new BeatTime(6,0,1);
        c.judgeLines.get(0).notes.add(hold);
        PlaytestJudge judge=new PlaytestJudge(c,false,1.5,0);
        judge.queue(3,PlaytestJudge.Phase.DOWN,0,0,0,0,1);judge.update(1);
        assertFalse(judge.touchingNoiseField());judge.update(1.5);assertTrue(judge.touchingNoiseField());
        judge.update(1.56);assertEquals(1,judge.count(PlaytestJudge.Grade.MISS));
        judge.clearTouches();assertFalse(judge.touchingNoiseField());
        assertTrue(judge.noiseFieldFrame(1.56).touches.isEmpty());
    }
    private static JSONObject scale(double t,double x,double y,double ax,double ay)throws Exception{return new JSONObject().put("time",t).put("scale",point(x,y)).put("anchor",point(ax,ay));}
    private static JSONObject rotate(double t,double rotation,double ax,double ay)throws Exception{return new JSONObject().put("time",t).put("rotation",rotation).put("anchor",point(ax,ay));}
}
