package com.xpe.mobile.video;

import com.xpe.mobile.model.*;
import com.xpe.mobile.preview.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExportAlphaBoundaryTest {
    @Test public void sceneSelectsTheNewAlphaAtFrameAlignedBoundariesAt30And60Fps() {
        ChartDocument chart = new ChartDocument();
        BpmChange bpm = new BpmChange(); bpm.bpm = 180; chart.bpmChanges.add(bpm);
        JudgeLine line = new JudgeLine(); chart.judgeLines.add(line);
        line.layer(0).events(EventType.ALPHA).clear();
        for (int i = 0; i < 4; i++) {
            LineEvent e = new LineEvent(); e.type = EventType.ALPHA;
            e.startTime = new BeatTime(i, 0, 1); e.endTime = new BeatTime(i + 1, 0, 1);
            e.start = e.end = i % 2 == 0 ? 255 : 0; line.layer(0).events(e.type).add(e);
        }
        for (int fps : new int[]{30, 60}) {
            VideoExportOptions options = new VideoExportOptions(1280, 720, fps, 8000000,
                    -21, 1500, false, false, 0, 0, false, true);
            for (int beat = 1; beat < 4; beat++) {
                long frame = (long) beat * fps / 3;
                RenderScene scene = ChartEvaluator.evaluateAtSeconds(chart, options.chartSeconds(frame, -21, 0), false);
                assertEquals(beat % 2 == 0 ? 255 : 0, scene.lines.get(0).alpha);
            }
        }
    }
}
