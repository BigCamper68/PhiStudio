package com.xpe.mobile.model;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class EasingTest {
    @Test
    public void allManualEasingIdsHaveCorrectMidpointValues() {
        double[] expected = new double[]{
                0.5,
                0.7071067811865476,
                0.2928932188134524,
                0.75,
                0.25,
                0.5,
                0.5,
                0.875,
                0.125,
                0.9375,
                0.0625,
                0.5,
                0.5,
                0.96875,
                0.03125,
                0.96875,
                0.03125,
                0.8660254037844386,
                0.1339745962155614,
                1.0876975,
                -0.0876975,
                0.5,
                0.5,
                1.015625,
                -0.015625,
                0.765625,
                0.234375,
                0.5,
                0.5
        };

        assertEquals(Easing.MAX_TYPE, expected.length);
        for (int type = Easing.MIN_TYPE; type <= Easing.MAX_TYPE; type++) {
            assertEquals("RPE easing " + type, expected[type - 1],
                    Easing.apply(type, 0.5), 1.0e-9);
        }
    }

    @Test
    public void everyEasingKeepsEndpointsAndInputsAreClamped() {
        for (int type = Easing.MIN_TYPE; type <= Easing.MAX_TYPE; type++) {
            assertEquals("start of easing " + type, 0.0, Easing.apply(type, 0.0), 1.0e-9);
            assertEquals("end of easing " + type, 1.0, Easing.apply(type, 1.0), 1.0e-9);
            assertEquals("clamped start of easing " + type, 0.0,
                    Easing.apply(type, -10.0), 1.0e-9);
            assertEquals("clamped end of easing " + type, 1.0,
                    Easing.apply(type, 10.0), 1.0e-9);
        }
        assertEquals(0.25, Easing.apply(0, 0.25), 0.0);
        assertEquals(0.75, Easing.apply(99, 0.75), 0.0);
    }

    @Test
    public void easingWindowIsNormalizedToEventEndpoints() {
        assertEquals(0.0, Easing.applyWindowed(5, 0.0, 0.25, 0.75), 0.0);
        assertEquals(0.375, Easing.applyWindowed(5, 0.5, 0.25, 0.75), 1.0e-9);
        assertEquals(1.0, Easing.applyWindowed(5, 1.0, 0.25, 0.75), 0.0);
        assertEquals(0.5, Easing.applyWindowed(20, 0.5, 0.4, 0.4), 0.0);
    }

    @Test
    public void cubicBezierSolvesXAndSupportsWindowing() {
        for (double t : new double[]{0.0, 0.1, 0.25, 0.5, 0.9, 1.0}) {
            assertEquals(t, Easing.applyCubicBezier(t,
                    1.0 / 3.0, 1.0 / 3.0, 2.0 / 3.0, 2.0 / 3.0), 1.0e-6);
        }
        double eased = Easing.applyCubicBezier(0.5, 0.42, 0.0, 1.0, 1.0);
        assertTrue(eased < 0.5);
        assertEquals(0.0, Easing.applyCubicBezierWindowed(
                0.0, 0.2, 0.8, 0.42, 0.0, 1.0, 1.0), 0.0);
        assertEquals(1.0, Easing.applyCubicBezierWindowed(
                1.0, 0.2, 0.8, 0.42, 0.0, 1.0, 1.0), 0.0);
    }

    @Test
    public void analyticalIntegralsMatchTheRenderedEasingCurves() {
        int steps = 4096;
        for (int type = Easing.MIN_TYPE; type <= Easing.MAX_TYPE; type++) {
            double numeric = 0.0;
            for (int index = 0; index < steps; index++) {
                double left = index / (double) steps;
                double right = (index + 1.0) / steps;
                numeric += (Easing.apply(type, left) + Easing.apply(type, right))
                        / 2.0 / steps;
            }
            assertEquals("RPE easing integral " + type,
                    numeric, Easing.integral(type, 1.0), 2.0e-4);
        }
        assertEquals(1.0 / 12.0, Easing.integralWindowed(
                5, 0.5, 0.25, 0.75), 1.0e-9);
    }

    @Test
    public void importedRpe170SpeedEasingIsRenderOnlyAndPreserved() throws Exception {
        LineEvent speed = LineEvent.fromJson(EventType.SPEED, new JSONObject()
                .put("startTime", new JSONArray().put(0).put(0).put(1))
                .put("endTime", new JSONArray().put(4).put(0).put(1))
                .put("start", 0.0)
                .put("end", 100.0)
                .put("easingType", 5));

        assertEquals(1, speed.easingType);
        assertEquals(50.0, speed.valueAt(2.0), 0.0);
        assertEquals(50.0, speed.renderSpeedValueAt(2.0, 169), 0.0);
        assertEquals(25.0, speed.renderSpeedValueAt(2.0, 170), 1.0e-9);
        assertEquals(200.0, speed.integratedRenderSpeed(0.0, 4.0, 169), 1.0e-9);
        assertEquals(400.0 / 3.0,
                speed.integratedRenderSpeed(0.0, 4.0, 170), 1.0e-9);
        assertEquals(5, speed.toJson().getInt("easingType"));
    }

    @Test
    public void rpe170ZeroSpeedEasingHoldsStartUntilTheEventEnd() throws Exception {
        LineEvent speed = LineEvent.fromJson(EventType.SPEED, new JSONObject()
                .put("startTime", new JSONArray().put(0).put(0).put(1))
                .put("endTime", new JSONArray().put(4).put(0).put(1))
                .put("start", 2.0)
                .put("end", 8.0)
                .put("easingType", 0));

        assertEquals(2.0, speed.renderSpeedValueAt(2.0, 170), 0.0);
        assertEquals(8.0, speed.renderSpeedValueAt(4.0, 170), 0.0);
        assertEquals(8.0, speed.integratedRenderSpeed(0.0, 4.0, 170), 0.0);
    }

    @Test
    public void lineEventUsesNamedBoundsBezierAndSpeedRules() {
        LineEvent event = event(EventType.MOVE_X, 0.0, 100.0);
        event.easingType = 5;
        event.easingLeft = 0.25;
        event.easingRight = 0.75;
        assertEquals(37.5, event.valueAt(2.0), 1.0e-8);

        event.bezier = true;
        event.easingLeft = 0.0;
        event.easingRight = 1.0;
        event.bezierPoints[0] = 0.42;
        event.bezierPoints[1] = 0.0;
        event.bezierPoints[2] = 1.0;
        event.bezierPoints[3] = 1.0;
        assertTrue(event.valueAt(2.0) < 50.0);

        event.type = EventType.SPEED;
        event.easingType = 25;
        assertEquals(50.0, event.valueAt(2.0), 0.0);
    }

    private static LineEvent event(EventType type, double start, double end) {
        LineEvent event = new LineEvent();
        event.type = type;
        event.startTime = BeatTime.zero();
        event.endTime = new BeatTime(4, 0, 1);
        event.start = start;
        event.end = end;
        return event;
    }
}
