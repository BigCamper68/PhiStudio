package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public final class EventSubdivisionOperationTest {
    @Test public void createsSixtyFourExactStepsAndUndoRestoresTheOriginalIdentity() {
        EventLayer layer = new EventLayer();
        LineEvent source = add(layer, EventType.MOVE_Y, "569", "573", 450, 0);
        EventSubdivisionOperation.Result result = prepare(layer, source, "0", 16);
        assertEquals(EventSubdivisionOperation.Error.NONE, result.error);
        assertEquals(64, result.events.size());
        assertSame(source, layer.events(source.type).get(0));
        EditHistory history = new EditHistory(8);
        history.execute(result.command);
        for (int i = 0; i < 64; i++) {
            LineEvent part = result.events.get(i);
            assertEquals(new BeatTime(569, i, 16), part.startTime);
            assertEquals(new BeatTime(569, i + 1, 16), part.endTime);
            assertEquals(source.valueAt(part.startTime.toDouble()), part.start, 1e-9);
        }
        history.undo();
        assertEquals(1, layer.events(source.type).size());
        assertSame(source, layer.events(source.type).get(0));
        assertEquals(beat("573"), source.endTime);
        assertFalse(history.canUndo());
        history.redo();
        assertSame(result.events.get(0), layer.events(source.type).get(0));
    }

    @Test public void anchorsBothDirectionsAtTheCursorAndKeepsShortBoundaryParts() {
        EventLayer layer = new EventLayer();
        LineEvent source = add(layer, EventType.ROTATE, "0", "1", 0, 100);
        EventSubdivisionOperation.Result result = prepare(layer, source, "0.5", 4);
        assertEquals(EventSubdivisionOperation.Error.NONE, result.error);
        assertEquals(4, result.events.size());
        assertEquals(beat("0.25"), result.events.get(0).endTime);
        LineEvent offset = add(new EventLayer(), EventType.MOVE_X, "0.1", "0.9", 0, 1);
        EventLayer other = new EventLayer();
        other.events(offset.type).add(offset);
        result = prepare(other, offset, "0.5", 4);
        assertEquals(4, result.events.size());
        assertEquals(beat("0.1"), result.events.get(0).startTime);
        assertEquals(beat("0.25"), result.events.get(0).endTime);
        assertEquals(beat("0.9"), result.events.get(3).endTime);
        result = prepare(other, offset, "10", 4);
        assertEquals(beat("0.35"), result.events.get(0).endTime);
    }

    @Test public void sixthBeatGridAndThirdBeatEndpointsRemainExact() {
        EventLayer layer = new EventLayer();
        LineEvent source = add(layer, EventType.ROTATE, "0:1/3", "1:1/3", 0, 1);
        EventSubdivisionOperation.Result result = prepare(layer, source, "0", 6);
        assertEquals(EventSubdivisionOperation.Error.NONE, result.error);
        assertEquals(new BeatTime(0, 1, 6), result.step);
        assertEquals(6, result.events.size());
        assertEquals(new BeatTime(0, 1, 2), result.events.get(0).endTime);
        assertEquals(new BeatTime(1, 1, 3), result.events.get(5).endTime);
    }

    @Test public void everyStandardEasingRetainsItsShapeAcrossDenseCuts() {
        for (int easing = 1; easing <= 29; easing++) {
            EventLayer layer = new EventLayer();
            LineEvent source = add(layer, EventType.MOVE_X, "0", "4", -50, 150);
            source.easingType = easing;
            source.easingLeft = 0.07;
            source.easingRight = 0.93;
            EventSubdivisionOperation.Result result = prepare(layer, source, "0", 16);
            assertEquals("easing " + easing, EventSubdivisionOperation.Error.NONE, result.error);
            for (LineEvent part : result.events) {
                for (int i = 0; i <= 4; i++) {
                    double t = part.startTime.toDouble()
                            + (part.endTime.toDouble() - part.startTime.toDouble()) * i / 4;
                    assertEquals("easing " + easing, source.valueAt(t), part.valueAt(t), 1e-7);
                }
            }
        }
    }

    @Test public void pairedXyIsIncludedOnlyOnceAndUndoesAtomically() {
        EventLayer layer = new EventLayer();
        LineEvent x = add(layer, EventType.MOVE_X, "4", "5", 0, 10);
        LineEvent y = add(layer, EventType.MOVE_Y, "4", "5", 20, 30);
        EventSubdivisionOperation.Result result = EventSubdivisionOperation.prepare(layer, 0,
                Arrays.asList(x, y, x), beat("4.5"), 8, true);
        assertEquals(EventSubdivisionOperation.Error.NONE, result.error);
        assertEquals(2, result.sourceCount);
        assertEquals(16, result.events.size());
        result.command.apply();
        assertEquals(8, layer.events(EventType.MOVE_X).size());
        assertEquals(8, layer.events(EventType.MOVE_Y).size());
        assertTrue(XYBindingValidator.isLayerSynchronized(layer));
        result.command.revert();
        assertEquals(Collections.singletonList(x), layer.events(EventType.MOVE_X));
        assertEquals(Collections.singletonList(y), layer.events(EventType.MOVE_Y));
        layer.events(EventType.MOVE_Y).clear();
        assertEquals(EventSubdivisionOperation.Error.XY_BINDING_INVALID,
                EventSubdivisionOperation.prepare(layer, 0, Collections.singletonList(x),
                        beat("4"), 8, true).error);
        assertEquals(EventSubdivisionOperation.Error.NONE, prepare(layer, x, "4", 8).error);
    }

    @Test public void rejectedInputsNeverModifyAnyTrack() {
        EventLayer layer = new EventLayer();
        LineEvent source = add(layer, EventType.ALPHA, "0", "4", 0, -1);
        for (int grid : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertEquals(EventSubdivisionOperation.Error.INVALID_GRID,
                    prepare(layer, source, "0", grid).error);
        }
        assertEquals(EventSubdivisionOperation.Error.TOO_MANY_SEGMENTS,
                prepare(layer, source, "0", 10000).error);
        assertEquals(EventSubdivisionOperation.Error.RESERVED_LAYER,
                EventSubdivisionOperation.prepare(layer, 4, Collections.singletonList(source),
                        beat("0"), 32, false).error);
        EventLayer shortLayer = new EventLayer();
        LineEvent shortEvent = add(shortLayer, EventType.ALPHA, "0.1", "0.2", 0, 1);
        assertEquals(EventSubdivisionOperation.Error.NO_CUTS,
                prepare(shortLayer, shortEvent, "0", 1).error);
        add(layer, EventType.ALPHA, "1", "2", 10, 10);
        assertEquals(EventSubdivisionOperation.Error.EVENT_OVERLAP,
                prepare(layer, source, "0", 32).error);
        assertSame(source, layer.events(EventType.ALPHA).get(0));
        assertEquals(beat("4"), source.endTime);
    }

    @Test public void enforcesTheLimitAcrossTheWholeSelectionIncludingXy() {
        EventLayer layer = new EventLayer();
        LineEvent x = add(layer, EventType.MOVE_X, "0", "1", 0, 1);
        LineEvent y = add(layer, EventType.MOVE_Y, "0", "1", 0, 1);
        assertEquals(EventSubdivisionOperation.Error.NONE, prepare(layer, x, "0", 4096).error);
        assertEquals(EventSubdivisionOperation.Error.TOO_MANY_SEGMENTS,
                EventSubdivisionOperation.prepare(layer, 0, Arrays.asList(x, y), beat("0"),
                        4096, true).error);
        assertEquals(1, layer.events(EventType.MOVE_X).size());
    }

    @Test public void keepsUnknownJsonLinkGroupsAndConstantNegativeAlpha() throws Exception {
        EventLayer layer = new EventLayer();
        LineEvent source = LineEvent.fromJson(EventType.ALPHA, new JSONObject()
                .put("startTime", beat("569").toJson()).put("endTime", beat("573").toJson())
                .put("start", -1).put("end", -1).put("linkgroup", 7).put("futureField", "keep"));
        layer.events(source.type).add(source);
        EventSubdivisionOperation.Result result = prepare(layer, source, "0", 32);
        assertEquals(128, result.events.size());
        result.command.apply();
        EventLayer roundTrip = EventLayer.fromJson(layer.toJson());
        for (LineEvent part : roundTrip.events(EventType.ALPHA)) {
            assertEquals(-1, part.start, 0);
            assertEquals(-1, part.end, 0);
            assertEquals(7, part.linkGroup);
            assertEquals("keep", part.toJson().getString("futureField"));
        }
    }

    @Test public void refusesUnsupportedImportedCurvesWithoutPartialChanges() throws Exception {
        EventLayer layer = new EventLayer();
        LineEvent normal = add(layer, EventType.ALPHA, "0", "1", 0, -1);
        LineEvent bezier = add(layer, EventType.MOVE_X, "0", "1", 0, 100);
        bezier.bezier = true;
        EventSubdivisionOperation.Result result = EventSubdivisionOperation.prepare(layer, 0,
                Arrays.asList(normal, bezier), beat("0"), 32, false);
        assertEquals(EventSubdivisionOperation.Error.UNSUPPORTED_CURVE, result.error);
        assertEquals(1, layer.events(normal.type).size());
        LineEvent speed = LineEvent.fromJson(EventType.SPEED, new JSONObject()
                .put("startTime", beat("0").toJson()).put("endTime", beat("1").toJson())
                .put("start", 0).put("end", 10).put("easingType", 19));
        layer.events(speed.type).add(speed);
        assertEquals(EventSubdivisionOperation.Error.UNSUPPORTED_CURVE,
                prepare(layer, speed, "0", 32).error);
    }

    @Test public void speedSubdivisionPreservesTheIntegratedScrollDistance() {
        EventLayer layer = new EventLayer();
        LineEvent speed = add(layer, EventType.SPEED, "665", "672", -7, 100);
        EventSubdivisionOperation.Result result = prepare(layer, speed, "0", 32);
        assertEquals(EventSubdivisionOperation.Error.NONE, result.error);
        double area = 0;
        for (LineEvent part : result.events) {
            area += part.integratedRenderSpeed(part.startTime.toDouble(), part.endTime.toDouble(), 160);
        }
        assertEquals(speed.integratedRenderSpeed(665, 672, 160), area, 1e-9);
    }

    @Test public void tempoChangesUseElapsedTimeWhenSlicingAnEasing() {
        EventLayer layer = new EventLayer();
        LineEvent source = add(layer, EventType.MOVE_Y, "0", "4", 450, 0);
        source.easingType = 19;
        java.util.function.DoubleUnaryOperator time = b -> b <= 1 ? b : 1 + (b - 1) / 2;
        EventSubdivisionOperation.Result result = EventSubdivisionOperation.prepare(layer, 0,
                Collections.singletonList(source), beat("0"), 8, false, time);
        assertEquals(EventSubdivisionOperation.Error.NONE, result.error);
        for (LineEvent part : result.events) {
            for (int i = 1; i < 4; i++) {
                double b = part.startTime.toDouble()
                        + (part.endTime.toDouble() - part.startTime.toDouble()) * i / 4;
                double originalProgress = time.applyAsDouble(b) / time.applyAsDouble(4);
                double localProgress = (time.applyAsDouble(b) - time.applyAsDouble(part.startTime.toDouble()))
                        / (time.applyAsDouble(part.endTime.toDouble()) - time.applyAsDouble(part.startTime.toDouble()));
                assertEquals(450 - 450 * source.easedProgressAt(originalProgress),
                        part.start + (part.end - part.start) * part.easedProgressAt(localProgress), 1e-7);
            }
        }
    }

    private static EventSubdivisionOperation.Result prepare(EventLayer layer, LineEvent source,
                                                             String cursor, int grid) {
        return EventSubdivisionOperation.prepare(layer, 0, Collections.singletonList(source),
                beat(cursor), grid, false);
    }

    private static BeatTime beat(String value) { return BeatTime.parseFlexible(value); }

    private static LineEvent add(EventLayer layer, EventType type, String startTime, String endTime,
                                 double start, double end) {
        LineEvent event = new LineEvent();
        event.type = type;
        event.startTime = beat(startTime);
        event.endTime = beat(endTime);
        event.start = start;
        event.end = end;
        layer.events(type).add(event);
        return event;
    }
}
