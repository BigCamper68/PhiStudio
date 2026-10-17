package com.xpe.mobile.preview;

import com.xpe.mobile.model.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PrprRotationTest {
    @Test public void nativeFloatRotationMatchesRustIncludingBillionsOfDegrees() throws Exception {
        for (String row : csv("rotation-oracle.csv")) {
            if (row.startsWith("degrees")) continue;
            String[] c = row.split(",");
            float angle = Float.parseFloat(c[0]);
            assertEquals(Float.parseFloat(c[1]), PrprRotation.radians(angle), 0);
            assertEquals(Float.parseFloat(c[2]), PrprRotation.cos(angle), 1.2e-7);
            assertEquals(Float.parseFloat(c[3]), PrprRotation.sin(angle), 1.2e-7);
            double screen = Math.toRadians(-PrprRotation.screenAngle(angle));
            assertEquals(Float.parseFloat(c[2]), Math.cos(screen), 3e-7);
            assertEquals(Float.parseFloat(c[3]), Math.sin(screen), 3e-7);
        }
    }

    @Test public void futureRotationLayersKeepPrprExtrapolationAndFloatAdditionOrder() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{\"BPMList\":[{\"bpm\":130,\"startTime\":[0,0,1]}]}");
        JudgeLine line = chart.judgeLines.get(0);
        line.layer(0).events(EventType.ROTATE).clear();
        line.layer(0).events(EventType.ROTATE).add(event(0, 1, 0, 0, 1));
        line.layer(0).events(EventType.ROTATE).add(event(101, 103, 90, 0, 14));
        line.layer(1).events(EventType.ROTATE).clear();
        line.layer(1).events(EventType.ROTATE).add(event(0, 1, 0, 0, 1));
        line.layer(1).events(EventType.ROTATE).add(event(102, 103, 0, -10, 10));
        line.layer(2).events(EventType.ROTATE).clear();
        line.layer(2).events(EventType.ROTATE).add(event(102, 103, 0, 20, 11));
        line.layer(2).events(EventType.ROTATE).add(event(103, 104, 0, 10, 11));
        for (String row : csv("disk-line-oracle.csv")) {
            if (row.startsWith("beat")) continue;
            String[] c = row.split(",");
            if (!c[1].equals("4")) continue;
            double beat = Double.parseDouble(c[0]);
            RenderScene.RenderLine frame = ChartEvaluator.evaluateAtSeconds(chart, chart.beatToSeconds(beat), false).lines.get(0);
            assertEquals("beat " + beat, Float.parseFloat(c[2]), frame.rotationDegrees, 0);
            assertEquals(Float.parseFloat(c[4]), PrprRotation.cos(frame.rotationDegrees), 1.2e-7);
            assertEquals(Float.parseFloat(c[5]), PrprRotation.sin(frame.rotationDegrees), 1.2e-7);
        }
    }

    private LineEvent event(int start, int end, double from, double to, int easing) {
        LineEvent e = new LineEvent(); e.type = EventType.ROTATE;
        e.startTime = new BeatTime(start, 0, 1); e.endTime = new BeatTime(end, 0, 1);
        e.start = from; e.end = to; e.easingType = easing; return e;
    }
    private String[] csv(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/prpr/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R");
        }
    }
}
