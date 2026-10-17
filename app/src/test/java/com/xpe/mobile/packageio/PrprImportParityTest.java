package com.xpe.mobile.packageio;

import com.xpe.mobile.model.*;
import com.xpe.mobile.preview.ChartEvaluator;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrprImportParityTest {
    @Test public void officialV1UnpacksIntegerXAndUses520ForY() throws Exception {
        JSONObject line=new JSONObject().put("bpm",120)
            .put("judgeLineMoveEvents",new JSONArray().put(new JSONObject()
                .put("startTime",0).put("endTime",32).put("start",440520).put("end",440520)))
            .put("notesAbove",new JSONArray().put(new JSONObject().put("type",1).put("time",32).put("positionX",2).put("speed",1)));
        ChartDocument c=OfficialPhigrosConverter.convert(new JSONObject().put("formatVersion",1).put("offset",0)
            .put("judgeLineList",new JSONArray().put(line)));
        JudgeLine converted=c.judgeLines.get(0);
        assertEquals(0,converted.layer(0).events(EventType.MOVE_X).get(0).start,.00001);
        assertEquals(450,converted.layer(0).events(EventType.MOVE_Y).get(0).start,.00001);
        assertEquals(151.875,converted.notes.get(0).positionX,.00001);
    }
    @Test public void pecLineWidthMatchesPrprBeforeAndAfterRpeSave() throws Exception {
        ChartDocument c=LegacyPecConverter.convert("0\nbp 0 120\ncp 0 0 1024 700\ncd 0 0 0\nca 0 0 255\ncv 0 0 7\nn1 0 4 0 1 0\n");
        for(int i=0;i<2;i++) {
            assertEquals(3.91/6,ChartEvaluator.evaluate(c,0,false).lines.get(0).scaleX,1e-7);
            c=ChartDocument.fromJson(c.toJsonString());
        }
    }
}
