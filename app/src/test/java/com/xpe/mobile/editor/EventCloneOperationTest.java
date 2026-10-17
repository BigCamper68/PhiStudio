package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class EventCloneOperationTest {
    @Test
    public void clonesAcrossLineSequenceWithTimeAndPropertyProfilesAtomically() {
        ChartDocument chart = new ChartDocument();
        JudgeLine source = emptyLine();
        JudgeLine target = emptyLine();
        chart.judgeLines.add(source);
        chart.judgeLines.add(target);
        LineEvent event = event(1, 2, 10.0, 20.0);
        source.eventLayers.get(0).events(EventType.MOVE_X).add(event);

        EventCloneOperation.Spec spec = spec(new int[]{0, 1}, false);
        spec.timeIncrement = new BeatTime(0, 1, 2);
        spec.xProfile = profile(0.0, 100.0);
        EventCloneOperation.Result result = EventCloneOperation.prepare(
                chart, source, 0, Collections.singletonList(event), spec);
        assertEquals(EventCloneOperation.Error.NONE, result.error);

        EditHistory history = new EditHistory(4);
        history.execute(result.command);
        assertFalse(source.eventLayers.get(0).events(EventType.MOVE_X).contains(event));
        LineEvent firstClone = source.eventLayers.get(0).events(EventType.MOVE_X).get(0);
        LineEvent secondClone = target.eventLayers.get(0).events(EventType.MOVE_X).get(0);
        assertEquals(new BeatTime(1, 0, 1), firstClone.startTime);
        assertEquals(new BeatTime(1, 1, 2), secondClone.startTime);
        assertEquals(120.0, secondClone.end, 0.0);

        history.undo();
        assertTrue(source.eventLayers.get(0).events(EventType.MOVE_X).contains(event));
        assertTrue(target.eventLayers.get(0).events(EventType.MOVE_X).isEmpty());
        history.redo();
        assertEquals(1, target.eventLayers.get(0).events(EventType.MOVE_X).size());
    }

    @Test
    public void keepSourceRejectsOverlappingCloneOnSourceLine() {
        ChartDocument chart = new ChartDocument();
        JudgeLine source = emptyLine();
        chart.judgeLines.add(source);
        LineEvent event = event(1, 2, 0.0, 1.0);
        source.eventLayers.get(0).events(EventType.MOVE_X).add(event);

        EventCloneOperation.Result result = EventCloneOperation.prepare(chart, source, 0,
                Collections.singletonList(event), spec(new int[]{0}, true));
        assertEquals(EventCloneOperation.Error.EVENT_OVERLAP, result.error);
    }

    @Test
    public void parsesUniqueLineSequences() {
        int[] parsed = EventCloneOperation.parseLineSequence("0, 2 5");
        assertEquals(3, parsed.length);
        assertEquals(5, parsed[2]);
    }

    private static EventCloneOperation.Spec spec(int[] lines, boolean keepSource) {
        EventCloneOperation.Spec spec = new EventCloneOperation.Spec();
        spec.lineSequence = lines;
        spec.keepSource = keepSource;
        spec.xProfile = profile(0.0, 0.0);
        spec.yProfile = profile(0.0, 0.0);
        spec.rotateProfile = profile(0.0, 0.0);
        spec.alphaProfile = profile(0.0, 0.0);
        return spec;
    }

    private static BatchValueTransform.Spec profile(double lower, double upper) {
        BatchValueTransform.Spec spec = new BatchValueTransform.Spec();
        spec.lowerBound = lower;
        spec.upperBound = upper;
        spec.periodicSequence = new double[]{1.0};
        return spec;
    }

    private static JudgeLine emptyLine() {
        JudgeLine line = new JudgeLine();
        line.eventLayers.clear();
        line.eventLayers.add(new EventLayer());
        return line;
    }

    private static LineEvent event(int startBeat, int endBeat, double start, double end) {
        LineEvent event = new LineEvent();
        event.type = EventType.MOVE_X;
        event.startTime = new BeatTime(startBeat, 0, 1);
        event.endTime = new BeatTime(endBeat, 0, 1);
        event.start = start;
        event.end = end;
        return event;
    }
}
