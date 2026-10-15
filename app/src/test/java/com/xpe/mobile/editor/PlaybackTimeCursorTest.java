package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackTimeCursorTest {
    private static ChartDocument chart() {
        ChartDocument chart = new ChartDocument();
        BpmChange bpm = new BpmChange(); bpm.bpm = 202.99999999999997;
        chart.bpmChanges.add(bpm);
        return chart;
    }

    @Test public void exactClockSurvivesBeatRoundtripAndRepeatedFrames() {
        ChartDocument chart = chart(); PlaybackTimeCursor cursor = new PlaybackTimeCursor();
        double beat = cursor.beatAtSeconds(chart, 300.0);
        for (int frame = 0; frame < 10; frame++) assertEquals(300.0, cursor.seconds(chart, beat), 0.0);
        beat = cursor.beatAtSeconds(chart, 300.016666);
        assertEquals(300.016666, cursor.seconds(chart, beat), 0.0);
    }

    @Test public void beatEditsChartReplacementAndRevisionInvalidateCachedTime() {
        ChartDocument chart = chart(); PlaybackTimeCursor cursor = new PlaybackTimeCursor();
        double beat = cursor.beatAtSeconds(chart, 300.0);
        assertEquals(chart.beatToSeconds(beat + 1), cursor.seconds(chart, beat + 1), 0.0);
        chart.bpmChanges.get(0).bpm = 120; chart.markEdited();
        assertEquals(chart.beatToSeconds(beat), cursor.seconds(chart, beat), 0.0);
        assertNotEquals(300.0, cursor.seconds(chart, beat), 0.0);
        ChartDocument other = chart(); other.bpmChanges.get(0).bpm = 60;
        assertEquals(other.beatToSeconds(beat), cursor.seconds(other, beat), 0.0);
    }

    @Test public void silentPlaybackClockCrossesBpmTransitionInSeconds() {
        ChartDocument chart = chart(); chart.bpmChanges.get(0).bpm = 120;
        BpmChange change = new BpmChange(); change.startTime = new BeatTime(4, 0, 1); change.bpm = 60;
        chart.bpmChanges.add(change);
        PlaybackTimeCursor cursor = new PlaybackTimeCursor();
        double beat = cursor.beatAtSeconds(chart, 1.9);
        beat = cursor.beatAtSeconds(chart, cursor.seconds(chart, beat) + 0.2);
        assertEquals(4.1, beat, 1e-12);
        assertEquals(2.1, cursor.seconds(chart, beat), 0.0);
    }
}
