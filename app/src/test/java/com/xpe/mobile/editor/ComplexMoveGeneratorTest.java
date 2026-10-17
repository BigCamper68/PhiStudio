package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ComplexMoveGeneratorTest {
    @Test
    public void fitsManualCircleAsPairedLinearEventsAtRequestedDensity() {
        ComplexMoveGenerator.Result result = ComplexMoveGenerator.generate(
                new EventLayer(), 0, spec("300*cos($t$*2*Pi)",
                        "300*sin($t$*2*Pi)", 4.0));

        assertEquals(ComplexMoveGenerator.Error.NONE, result.error);
        assertEquals(8, result.segmentCount);
        assertEquals(8, result.moveXEvents.size());
        assertEquals(8, result.moveYEvents.size());
        assertEquals(new BeatTime(2, 0, 1), result.moveXEvents.get(0).startTime);
        assertEquals(new BeatTime(2, 1, 4), result.moveXEvents.get(0).endTime);
        assertEquals(300.0, result.path.get(0).x, 1.0e-9);
        assertEquals(300.0, result.path.get(2).y, 1.0e-9);
        for (int index = 0; index < result.segmentCount; index++) {
            LineEvent x = result.moveXEvents.get(index);
            LineEvent y = result.moveYEvents.get(index);
            assertEquals(x.startTime, y.startTime);
            assertEquals(x.endTime, y.endTime);
            assertEquals(1, x.easingType);
            assertEquals(1, y.easingType);
        }
    }

    @Test
    public void independentAxisTimeEasingChangesTheParametricPath() {
        ComplexMoveGenerator.Spec spec = spec("100*t", "100*t", 2.0);
        spec.yTimeEasing = new ComplexMoveGenerator.TimeEasing(3, 0.0, 1.0);
        ComplexMoveGenerator.Result result = ComplexMoveGenerator.preview(spec);

        assertEquals(ComplexMoveGenerator.Error.NONE, result.error);
        int midpoint = result.path.size() / 2;
        assertEquals(50.0, result.path.get(midpoint).x, 1.0e-9);
        assertEquals(100.0 * (1.0 - Math.cos(Math.PI / 4.0)),
                result.path.get(midpoint).y, 1.0e-9);
    }

    @Test
    public void validatesEasingWindowOverlapAndAtomicUndoRedo() {
        ComplexMoveGenerator.TimeEasing bounded =
                ComplexMoveGenerator.TimeEasing.parse("5, 0.25, 0.75");
        assertEquals(5, bounded.type);
        assertEquals(0.25, bounded.left, 0.0);
        assertEquals(0.75, bounded.right, 0.0);

        EventLayer layer = new EventLayer();
        ComplexMoveGenerator.Result result = ComplexMoveGenerator.generate(
                layer, 0, spec("100*t", "-100*t", 2.0));
        assertEquals(ComplexMoveGenerator.Error.NONE, result.error);
        EditHistory history = new EditHistory(10);
        history.execute(ComplexMoveCommand.add(layer,
                result.moveXEvents, result.moveYEvents));
        assertEquals(4, layer.events(EventType.MOVE_X).size());
        assertEquals(4, layer.events(EventType.MOVE_Y).size());
        history.undo();
        assertTrue(layer.events(EventType.MOVE_X).isEmpty());
        assertTrue(layer.events(EventType.MOVE_Y).isEmpty());
        assertTrue(history.canRedo());
        history.redo();
        assertEquals(4, layer.events(EventType.MOVE_X).size());

        ComplexMoveGenerator.Result overlap = ComplexMoveGenerator.generate(
                layer, 0, spec("100*t", "-100*t", 2.0));
        assertEquals(ComplexMoveGenerator.Error.EVENT_OVERLAP, overlap.error);
        assertFalse(overlap.error == ComplexMoveGenerator.Error.NONE);
    }

    @Test
    public void rejectsOutOfRangeAndUnboundedGeneration() {
        assertEquals(ComplexMoveGenerator.Error.X_OUT_OF_RANGE,
                ComplexMoveGenerator.preview(spec("1000*t", "0", 1.0)).error);
        ComplexMoveGenerator.Spec dense = spec("0", "0", 1.0);
        dense.density = 10000.0;
        assertEquals(ComplexMoveGenerator.Error.TOO_MANY_SEGMENTS,
                ComplexMoveGenerator.preview(dense).error);
    }

    private static ComplexMoveGenerator.Spec spec(String x, String y, double density) {
        ComplexMoveGenerator.Spec spec = new ComplexMoveGenerator.Spec();
        spec.startTime = new BeatTime(2, 0, 1);
        spec.endTime = new BeatTime(4, 0, 1);
        spec.xExpression = x;
        spec.yExpression = y;
        spec.xTimeEasing = new ComplexMoveGenerator.TimeEasing(1, 0.0, 1.0);
        spec.yTimeEasing = new ComplexMoveGenerator.TimeEasing(1, 0.0, 1.0);
        spec.density = density;
        return spec;
    }
}
