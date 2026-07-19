package com.xpe.mobile.editor;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class JudgeLineCommandTest {
    @Test
    public void lineZeroIsProtectedAndDeleteRemapsReferencesWithUndoRedo() {
        ChartDocument chart = new ChartDocument();
        chart.judgeLines.clear();
        for (int index = 0; index < 4; index++) {
            JudgeLine line = new JudgeLine();
            line.name = "Line " + index;
            chart.judgeLines.add(line);
        }
        chart.judgeLines.get(2).father = 1;
        chart.judgeLines.get(3).father = 2;
        JudgeLine deleted = chart.judgeLines.get(1);
        JudgeLine oldLine2 = chart.judgeLines.get(2);
        JudgeLine oldLine3 = chart.judgeLines.get(3);

        assertEquals(JudgeLineValidator.Error.LINE_ZERO_PROTECTED,
                JudgeLineValidator.validateDelete(chart, 0));

        EditHistory history = new EditHistory(10);
        history.execute(JudgeLineCommand.delete(chart, 1));
        assertEquals(3, chart.judgeLines.size());
        assertSame(oldLine2, chart.judgeLines.get(1));
        assertEquals(-1, oldLine2.father);
        assertEquals(1, oldLine3.father);

        history.undo();
        assertEquals(4, chart.judgeLines.size());
        assertSame(deleted, chart.judgeLines.get(1));
        assertEquals(1, oldLine2.father);
        assertEquals(2, oldLine3.father);

        history.redo();
        assertEquals(3, chart.judgeLines.size());
        assertEquals(-1, oldLine2.father);
        assertEquals(1, oldLine3.father);
    }

    @Test
    public void propertyEditRoundTripsRpeFieldsAndUnknownDataWithoutParenting() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{"
                + "\"BPMList\":[{\"bpm\":120,\"startTime\":[0,0,1]}],"
                + "\"META\":{},\"judgeLineList\":[{"
                + "\"Name\":\"Base\",\"Texture\":\"line.png\",\"Group\":0,"
                + "\"bpmfactor\":1,\"father\":-1,\"isCover\":1,\"zOrder\":0,"
                + "\"futureLineData\":{\"keep\":true},\"notes\":[],\"eventLayers\":[]}]}");
        JudgeLine target = chart.judgeLines.get(0);
        JudgeLine edited = target.copyProperties();
        edited.name = "Foreground";
        edited.texture = "custom-line.png";
        edited.group = 2;
        edited.bpmFactor = 1.5;
        edited.cover = false;
        edited.zOrder = 9;
        edited.father = 99;

        assertEquals(JudgeLineValidator.Error.NONE,
                JudgeLineValidator.validateProperties(edited));
        EditHistory history = new EditHistory(10);
        history.execute(JudgeLineCommand.edit(target, target.copyProperties(), edited));

        JSONObject line = new JSONObject(chart.toJsonString())
                .getJSONArray("judgeLineList").getJSONObject(0);
        assertEquals("Foreground", line.getString("Name"));
        assertEquals("custom-line.png", line.getString("Texture"));
        assertEquals(2, line.getInt("Group"));
        assertEquals(1.5, line.getDouble("bpmfactor"), 0.0);
        assertEquals(0, line.getInt("isCover"));
        assertEquals(9, line.getInt("zOrder"));
        assertEquals(-1, line.getInt("father"));
        assertEquals(true, line.getJSONObject("futureLineData").getBoolean("keep"));

        history.undo();
        assertEquals("Base", target.name);
        history.redo();
        assertEquals("Foreground", target.name);
    }

    @Test
    public void addAtIndexRemapsExistingReferencesAndUndoRestoresThem() {
        ChartDocument chart = new ChartDocument();
        chart.judgeLines.add(new JudgeLine());
        JudgeLine second = new JudgeLine();
        second.father = 1;
        chart.judgeLines.add(second);
        JudgeLine inserted = new JudgeLine();

        EditHistory history = new EditHistory(10);
        history.execute(JudgeLineCommand.add(chart, inserted, 1));
        assertSame(inserted, chart.judgeLines.get(1));
        assertEquals(2, second.father);
        history.undo();
        assertEquals(2, chart.judgeLines.size());
        assertSame(second, chart.judgeLines.get(1));
        assertEquals(1, second.father);
    }
}
