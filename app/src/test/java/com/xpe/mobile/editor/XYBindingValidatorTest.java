package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class XYBindingValidatorTest {
    @Test
    public void findsUniqueCounterpartAndRecognizesSynchronizedLayer() {
        EventLayer layer = new EventLayer();
        LineEvent moveX = event(EventType.MOVE_X, 1, 2);
        LineEvent moveY = event(EventType.MOVE_Y, 1, 2);
        layer.events(EventType.MOVE_X).add(moveX);
        layer.events(EventType.MOVE_Y).add(moveY);

        XYBindingValidator.PairLookup lookup = XYBindingValidator.findPair(layer, moveX);
        assertEquals(XYBindingValidator.Error.NONE, lookup.error);
        assertSame(moveY, lookup.event);
        assertTrue(XYBindingValidator.isLayerSynchronized(layer));
    }

    @Test
    public void rejectsMissingAndAmbiguousPairsWithoutChangingImportedLists() {
        EventLayer layer = new EventLayer();
        LineEvent moveX = event(EventType.MOVE_X, -1, 1);
        layer.events(EventType.MOVE_X).add(moveX);

        assertEquals(XYBindingValidator.Error.PAIR_NOT_FOUND,
                XYBindingValidator.findPair(layer, moveX).error);
        assertFalse(XYBindingValidator.isLayerSynchronized(layer));
        assertEquals(1, layer.events(EventType.MOVE_X).size());

        LineEvent firstY = event(EventType.MOVE_Y, -1, 1);
        LineEvent secondY = event(EventType.MOVE_Y, -1, 1);
        layer.events(EventType.MOVE_Y).add(firstY);
        layer.events(EventType.MOVE_Y).add(secondY);
        assertEquals(XYBindingValidator.Error.AMBIGUOUS_PAIR,
                XYBindingValidator.findPair(layer, moveX).error);
        assertEquals(2, layer.events(EventType.MOVE_Y).size());
    }


    @Test
    public void duplicateIntervalsRemainAmbiguousEvenWhenAxisCountsMatch() {
        EventLayer layer = new EventLayer();
        for (int index = 0; index < 2; index++) {
            layer.events(EventType.MOVE_X).add(event(EventType.MOVE_X, 1, 2));
            layer.events(EventType.MOVE_Y).add(event(EventType.MOVE_Y, 1, 2));
        }

        assertFalse(XYBindingValidator.isLayerSynchronized(layer));
        assertEquals(XYBindingValidator.Error.AMBIGUOUS_PAIR,
                XYBindingValidator.findPair(layer,
                        layer.events(EventType.MOVE_X).get(0)).error);
    }

    @Test
    public void invalidAndOverlappingImportedPairsDoNotEnableBindingOrMutateData() {
        EventLayer layer = new EventLayer();
        LineEvent negativeX = event(EventType.MOVE_X, -1, 1);
        LineEvent negativeY = event(EventType.MOVE_Y, -1, 1);
        layer.events(EventType.MOVE_X).add(negativeX);
        layer.events(EventType.MOVE_Y).add(negativeY);

        assertFalse(XYBindingValidator.isLayerSynchronized(layer));
        assertSame(negativeX, layer.events(EventType.MOVE_X).get(0));
        assertSame(negativeY, layer.events(EventType.MOVE_Y).get(0));

        layer.events(EventType.MOVE_X).clear();
        layer.events(EventType.MOVE_Y).clear();
        for (EventType type : new EventType[]{EventType.MOVE_X, EventType.MOVE_Y}) {
            layer.events(type).add(event(type, 0, 2));
            layer.events(type).add(event(type, 1, 3));
        }

        assertFalse(XYBindingValidator.isLayerSynchronized(layer));
        assertEquals(2, layer.events(EventType.MOVE_X).size());
        assertEquals(2, layer.events(EventType.MOVE_Y).size());

        layer.events(EventType.MOVE_X).get(1).startTime = beat(2);
        layer.events(EventType.MOVE_Y).get(1).startTime = beat(2);
        assertTrue(XYBindingValidator.isLayerSynchronized(layer));

        layer.events(EventType.MOVE_X).get(1).endTime = beat(2);
        layer.events(EventType.MOVE_Y).get(1).endTime = beat(2);
        assertFalse(XYBindingValidator.isLayerSynchronized(layer));
    }

    @Test
    public void pairedTimeValidationChecksBothAxesAndAllowsTouchingBoundaries() {
        EventLayer layer = new EventLayer();
        LineEvent previousX = event(EventType.MOVE_X, 0, 1);
        LineEvent targetX = event(EventType.MOVE_X, 1, 2);
        LineEvent nextX = event(EventType.MOVE_X, 3, 4);
        LineEvent previousY = event(EventType.MOVE_Y, 0, 1);
        LineEvent targetY = event(EventType.MOVE_Y, 1, 2);
        LineEvent nextY = event(EventType.MOVE_Y, 2, 3);
        layer.events(EventType.MOVE_X).add(previousX);
        layer.events(EventType.MOVE_X).add(targetX);
        layer.events(EventType.MOVE_X).add(nextX);
        layer.events(EventType.MOVE_Y).add(previousY);
        layer.events(EventType.MOVE_Y).add(targetY);
        layer.events(EventType.MOVE_Y).add(nextY);

        assertEquals(XYBindingValidator.Error.NONE,
                XYBindingValidator.validatePairedTimes(layer, 0, targetX, targetY,
                        beat(1), beat(2)));
        assertEquals(XYBindingValidator.Error.EVENT_OVERLAP,
                XYBindingValidator.validatePairedTimes(layer, 0, targetX, targetY,
                        beat(1), beat(2.5)));
        assertEquals(XYBindingValidator.Error.NEGATIVE_START_TIME,
                XYBindingValidator.validatePairedTimes(layer, 0, targetX, targetY,
                        beat(-0.25), beat(2)));
        assertEquals(XYBindingValidator.Error.END_TIME_NOT_AFTER_START,
                XYBindingValidator.validatePairedTimes(layer, 0, targetX, targetY,
                        beat(2), beat(2)));
        assertEquals(XYBindingValidator.Error.RESERVED_LAYER,
                XYBindingValidator.validatePairedTimes(layer, 4, targetX, targetY,
                        beat(1), beat(2)));
    }

    @Test
    public void placementChecksOverlapForBothMoveTypes() {
        EventLayer layer = new EventLayer();
        layer.events(EventType.MOVE_Y).add(event(EventType.MOVE_Y, 2, 3));
        LineEvent moveX = event(EventType.MOVE_X, 1, 2.5);
        LineEvent moveY = event(EventType.MOVE_Y, 1, 2.5);

        assertEquals(XYBindingValidator.Error.EVENT_OVERLAP,
                XYBindingValidator.validatePlacement(layer, 0, moveX, moveY));
        assertEquals(0, layer.events(EventType.MOVE_X).size());
        assertEquals(1, layer.events(EventType.MOVE_Y).size());
    }

    @Test
    public void chartSynchronizationChecksOnlyNormalLayersZeroThroughThree() {
        ChartDocument chart = new ChartDocument();
        JudgeLine line = new JudgeLine();
        line.eventLayers.clear();
        for (int index = 0; index < 5; index++) line.eventLayers.add(new EventLayer());
        line.eventLayers.get(0).events(EventType.MOVE_X).add(event(EventType.MOVE_X, 0, 1));
        line.eventLayers.get(0).events(EventType.MOVE_Y).add(event(EventType.MOVE_Y, 0, 1));
        line.eventLayers.get(4).events(EventType.MOVE_X).add(event(EventType.MOVE_X, 4, 5));
        chart.judgeLines.add(line);

        assertTrue(XYBindingValidator.isChartSynchronized(chart));
        line.eventLayers.get(2).events(EventType.MOVE_X).add(event(EventType.MOVE_X, 2, 3));
        assertFalse(XYBindingValidator.isChartSynchronized(chart));
    }

    private static LineEvent event(EventType type, double start, double end) {
        LineEvent event = new LineEvent();
        event.type = type;
        event.startTime = beat(start);
        event.endTime = beat(end);
        return event;
    }

    private static BeatTime beat(double value) {
        return BeatTime.fromDouble(value, 4);
    }
}
