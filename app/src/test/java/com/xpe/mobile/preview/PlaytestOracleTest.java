package com.xpe.mobile.preview;

import com.xpe.mobile.model.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.Test;
import static org.junit.Assert.*;

/** Expected values come from extracted upstream Rust, not from the Java implementation. */
public class PlaytestOracleTest {
    private BufferedReader rows(String name) {
        return new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/prpr/" + name)), StandardCharsets.UTF_8));
    }
    @Test public void timeWindowsDistanceAndPrejudgementMatchUpstreamRust() throws Exception {
        int cases = 0;
        try (BufferedReader rows = rows("judge-oracle.csv")) {
            rows.readLine(); String row;
            while ((row = rows.readLine()) != null) {
                String[] v = row.split(",");
                int kind = Integer.parseInt(v[0]); double at = 1 + Integer.parseInt(v[1]) / 1000.0;
                double x = Float.parseFloat(v[2]); boolean flick = Boolean.parseBoolean(v[3]);
                ChartDocument c = ChartDocument.fromJson("{}"); c.judgeLines.clear();
                JudgeLine line = new JudgeLine(); c.judgeLines.add(line);
                Note n = new Note(); n.type = new NoteType[]{NoteType.TAP, NoteType.HOLD, NoteType.FLICK, NoteType.DRAG}[kind];
                n.startTime = new BeatTime(2, 0, 1); n.endTime = new BeatTime(kind == 1 ? 4 : 2, 0, 1); line.notes.add(n);
                PlaytestJudge j = new PlaytestJudge(c, false, 16.0 / 9, 0);
                if (flick) {
                    j.queue(0, PlaytestJudge.Phase.DOWN, x, 0, 0, 0, 0); j.update(0);
                    j.update(at - .02);
                    j.queue(0, PlaytestJudge.Phase.MOVE, x, .01, 0, .01, at - .01); j.update(at - .01);
                    j.queue(0, PlaytestJudge.Phase.MOVE, x, .21, 0, .21, at); j.update(at);
                } else {
                    j.queue(0, PlaytestJudge.Phase.DOWN, x, 0, x, 0, at); j.update(at);
                }
                j.update(2);
                for (PlaytestJudge.Grade grade : PlaytestJudge.Grade.values())
                    assertEquals(row + " " + grade, Integer.parseInt(v[4 + grade.ordinal()]), j.count(grade));
                cases++;
            }
        }
        assertEquals(4365, cases);
    }
    @Test public void flickTrackerMatchesUpstreamRustIncludingStopsAndDirectionReversals() throws Exception {
        int cases = 0;
        try (BufferedReader rows = rows("flick-oracle.csv")) {
            rows.readLine(); String row;
            while ((row = rows.readLine()) != null) {
                String[] v = row.split(","); float dt = Float.parseFloat(v[0]);
                float x1 = Float.parseFloat(v[1]), y1 = Float.parseFloat(v[2]);
                float x2 = Float.parseFloat(v[3]), y2 = Float.parseFloat(v[4]);
                PlaytestJudge.FlickTracker tracker = new PlaytestJudge.FlickTracker(0, 0, 0);
                tracker.push(dt, x1, y1); tracker.push(dt * 2, x1 + x2, y1 + y2);
                assertEquals(row, Boolean.parseBoolean(v[5]), tracker.flicked); cases++;
            }
        }
        assertEquals(360, cases);
    }
}
