package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class BpmEditCommandTest {
    @Test
    public void addEditDeleteAreOrderedAndUndoable() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{"
                + "\"BPMList\":["
                + "{\"bpm\":120,\"startTime\":[0,0,1]},"
                + "{\"bpm\":180,\"startTime\":[8,0,1]}],"
                + "\"META\":{},\"judgeLineList\":[]}");
        EditHistory history = new EditHistory(10);

        BpmChange added = change(4, 150.0);
        history.execute(BpmEditCommand.add(chart, added));
        assertSame(added, chart.bpmChanges.get(1));
        history.undo();
        assertEquals(2, chart.bpmChanges.size());
        history.redo();
        assertSame(added, chart.bpmChanges.get(1));

        BpmChange before = added.copy();
        BpmChange after = added.copy();
        after.startTime = new BeatTime(6, 0, 1);
        after.bpm = 160.0;
        history.execute(BpmEditCommand.edit(chart, added, before, after));
        assertEquals(160.0, added.bpm, 0.0);
        assertSame(added, chart.bpmChanges.get(1));
        history.undo();
        assertEquals(150.0, added.bpm, 0.0);
        assertEquals(new BeatTime(4, 0, 1), added.startTime);

        history.execute(BpmEditCommand.delete(chart, added));
        assertEquals(2, chart.bpmChanges.size());
        history.undo();
        assertEquals(3, chart.bpmChanges.size());
        assertSame(added, chart.bpmChanges.get(1));
    }

    @Test
    public void editingBpmKeepsUnknownJsonFields() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{"
                + "\"BPMList\":[{\"bpm\":120,\"startTime\":[0,0,1],"
                + "\"futureBpmData\":{\"mode\":\"custom\"}}],"
                + "\"META\":{},\"judgeLineList\":[]}");
        BpmChange target = chart.bpmChanges.get(0);
        BpmChange before = target.copy();
        BpmChange after = target.copy();
        after.bpm = 144.0;

        EditHistory history = new EditHistory(10);
        history.execute(BpmEditCommand.edit(chart, target, before, after));

        JSONObject exported = new JSONObject(chart.toJsonString());
        JSONObject bpm = exported.getJSONArray("BPMList").getJSONObject(0);
        assertEquals(144.0, bpm.getDouble("bpm"), 0.0);
        assertEquals("custom", bpm.getJSONObject("futureBpmData").getString("mode"));
    }

    private static BpmChange change(int beat, double bpm) {
        BpmChange change = new BpmChange();
        change.startTime = new BeatTime(beat, 0, 1);
        change.bpm = bpm;
        return change;
    }
}
