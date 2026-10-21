package com.xpe.mobile.preview;

import com.xpe.mobile.model.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhiraNoiseFieldMarkersTest {
    static JSONObject event(double beat, double end, Object from, Object to) throws Exception {
        return new JSONObject().put("startTime",BeatTime.fromDouble(beat,1000).toJson())
                .put("endTime",BeatTime.fromDouble(end,1000).toJson()).put("start",from).put("end",to).put("easingType",1);
    }
    static JSONObject numeric(double from,double to,double a,double b)throws Exception{return event(a,b,from,to);}
    static JSONObject marker()throws Exception{
        JSONArray codes=new JSONArray();for(int i=0;i<4;i++)codes.put(event(i*2,i*2,i+1,i+1));
        return new JSONObject().put("Texture","guides/IsSubtract0.PNG").put("anchor",new JSONArray().put(.25).put(.75))
                .put("eventLayers",new JSONArray().put(new JSONObject().put("speedEvents",codes)
                .put("moveXEvents",new JSONArray().put(numeric(135,135,0,8)))
                .put("moveYEvents",new JSONArray().put(numeric(90,90,0,8)))
                .put("rotateEvents",new JSONArray().put(numeric(-30,-30,0,8)))))
                .put("extended",new JSONObject().put("scaleXEvents",new JSONArray().put(numeric(-2,-2,0,8)))
                .put("scaleYEvents",new JSONArray().put(numeric(3,3,0,8)))
                .put("colorEvents",new JSONArray().put(event(0,4,new JSONArray().put(255).put(255).put(255),new JSONArray().put(0).put(128).put(255)))));
    }
    static ChartDocument chart(JSONObject line)throws Exception{
        ChartDocument chart=ChartDocument.fromParsedJson(new JSONObject().put("judgeLineList",new JSONArray().put(line)));
        chart.noiseMarkerSizes.put(chart.judgeLines.get(0).texture,new int[]{100,50});return chart;
    }
    private static NoiseFieldFrame frame(ChartDocument chart,double t,double aspect){return ChartEvaluator.evaluateAtSeconds(chart,t,false,-1,false,aspect,null).noiseFields;}
    @Test public void importedRgbAndLifecycleUsePlayerCanvasGeometryAcrossRatiosAndReverseSeeks()throws Exception{
        ChartDocument chart=chart(marker());
        assertEquals(0,PhiraNoiseFieldMarkers.kind("guides\\IsSubtract0.PNG"));assertEquals(1,PhiraNoiseFieldMarkers.kind("isSubtract1.png"));assertEquals(-1,PhiraNoiseFieldMarkers.kind("not-isSubtract0.png"));
        for(double aspect:new double[]{.75,4.0/3,1.5,16.0/9,21.0/9})for(double t:new double[]{.25,1.25,2.25,1.25,.25,2.25}){
            var zone=frame(chart,t,aspect).zones.get(0);
            assertEquals(1.5/aspect,zone.yScale,1e-7);
            assertEquals(200.0/675,zone.width,1e-7);assertEquals(150.0/675,zone.height,1e-7);
            assertEquals(30,zone.rotation,0);assertEquals(t>=1&&t<2,zone.active);assertEquals(t>=.5&&t<1,zone.ready);
            double dx=-50.0/675,dy=-37.5/675,c=Math.cos(Math.PI/6),s=.5;
            assertEquals(.2+c*dx-s*dy,zone.x,1e-6);
            assertEquals((90.0/675+s*dx+c*dy)*1.5/aspect,zone.y,1e-6);
            assertTrue(zone.contains(zone.x,zone.y,0));assertFalse(zone.contains(zone.x+1,zone.y+1,0));
            assertEquals(255,zone.color&255);assertEquals(t>=1&&t<2,frame(chart,t,aspect).blocks(zone.x,zone.y));
            assertEquals(0,ChartEvaluator.evaluateAtSeconds(chart,t,false,-1,false,aspect,null).lines.get(0).alpha);
        }
        assertEquals(0xff80c0ff,frame(chart,1,1.5).zones.get(0).color);
        assertTrue(frame(chart,3,1.5).zones.isEmpty());
    }
    @Test public void parentRotationPrecedesViewportStretchAndMarkerColorDefaultsToNativeRed()throws Exception{
        JSONObject child=marker().put("father",0).put("rotateWithFather",true);
        child.getJSONObject("extended").remove("colorEvents");
        JSONObject parent=new JSONObject().put("eventLayers",new JSONArray().put(new JSONObject()
                .put("moveXEvents",new JSONArray().put(numeric(100,100,0,8)))
                .put("moveYEvents",new JSONArray().put(numeric(50,50,0,8)))
                .put("rotateEvents",new JSONArray().put(numeric(-90,-90,0,8)))));
        ChartDocument chart=ChartDocument.fromParsedJson(new JSONObject().put("judgeLineList",new JSONArray().put(parent).put(child)));
        chart.noiseMarkerSizes.put(chart.judgeLines.get(1).texture,new int[]{100,50});
        for(double aspect:new double[]{1.5,16.0/9}){
            var zone=frame(chart,1.25,aspect).zones.get(0);assertEquals(120,zone.rotation,1e-5);assertEquals(NoiseField.DEFAULT_COLOR,zone.color);
            double angle=Math.toRadians(120),dx=-50.0/675,dy=-37.5/675;
            assertEquals((100-90)/675.0+Math.cos(angle)*dx-Math.sin(angle)*dy,zone.x,1e-6);
            assertEquals(((50+135)/675.0+Math.sin(angle)*dx+Math.cos(angle)*dy)*1.5/aspect,zone.y,1e-6);
        }
    }
    @Test public void markerInputBlocksInPlaytestAndRetainsInfectionUntilRelease()throws Exception{
        ChartDocument chart=chart(marker());var prepared=PreviewChartIndex.prepared(chart,false);
        NoiseFieldInput input=new NoiseFieldInput(chart,prepared,16.0/9);
        var zone=frame(chart,1.25,16.0/9).zones.get(0);
        assertFalse(input.event(3,PlaytestJudge.Phase.DOWN,zone.x,zone.y,1.25));assertTrue(input.touching());
        assertFalse(input.event(3,PlaytestJudge.Phase.MOVE,1,1,1.3));assertTrue(input.advance(3.5).contains(3));
        assertFalse(input.event(3,PlaytestJudge.Phase.UP,1,1,3.5));assertFalse(input.touching());
        assertTrue(input.event(4,PlaytestJudge.Phase.DOWN,zone.x,zone.y,.25));assertFalse(input.touching());
        input.clear();assertTrue(input.frame(0).touches.isEmpty());
    }
    @Test public void alphaFallbackPreservesDisabledReadyActiveAndHiddenPhases()throws Exception{
        JSONObject raw=marker();JSONObject layer=raw.getJSONArray("eventLayers").getJSONObject(0);layer.remove("speedEvents");
        layer.put("alphaEvents",new JSONArray().put(numeric(0,0,0,1)).put(numeric(128,128,1,2))
                .put(numeric(255,255,2,4)).put(numeric(128,128,4,5)).put(numeric(0,0,5,6)));
        ChartDocument chart=chart(raw);
        assertTrue(frame(chart,.1,1.5).zones.isEmpty());
        assertFalse(frame(chart,.6,1.5).zones.get(0).active);assertTrue(frame(chart,.6,1.5).zones.get(0).ready);
        assertTrue(frame(chart,1.1,1.5).zones.get(0).active);assertFalse(frame(chart,2.1,1.5).zones.get(0).active);
        assertTrue(frame(chart,2.6,1.5).zones.isEmpty());
    }
}
