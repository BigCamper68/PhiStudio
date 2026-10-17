package com.xpe.mobile.editor;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.audio.PlaybackTimeline;

/** Remembers an exact clock timestamp while the editor also exposes its beat cursor. */
final class PlaybackTimeCursor {
    private ChartDocument chart;
    private long revision;
    private double beat = Double.NaN;
    private double seconds;
    private ChartDocument audioChart;
    private long audioRevision, audioOffset;
    private double audioBeat, audioSeconds;

    double seconds(ChartDocument currentChart, double currentBeat) {
        if (currentChart == null) return 0.0;
        if (chart == currentChart && revision == currentChart.revision()
                && Double.doubleToLongBits(beat) == Double.doubleToLongBits(currentBeat)) {
            return seconds;
        }
        return currentChart.beatToSeconds(currentBeat);
    }

    double beatAtSeconds(ChartDocument currentChart, double requestedSeconds) {
        audioChart = null;
        seconds = Double.isFinite(requestedSeconds) ? Math.max(0.0, requestedSeconds) : 0.0;
        chart = currentChart;
        revision = currentChart.revision();
        beat = currentChart.secondsToBeat(seconds);
        return beat;
    }

    double beatAtAudioSeconds(ChartDocument chart, double requestedSeconds, long packageOffset) {
        double value = Double.isFinite(requestedSeconds) ? requestedSeconds
                : PlaybackTimeline.startMillis(chart.offsetMs, packageOffset) / 1000.0;
        double beat = beatAtSeconds(chart, PlaybackTimeline.chartSeconds(value, chart.offsetMs, packageOffset));
        audioChart = chart; audioRevision = chart.revision(); audioBeat = beat;
        audioOffset = PlaybackTimeline.offsetMillis(chart.offsetMs, packageOffset); audioSeconds = value;
        return beat;
    }

    double audioSeconds(ChartDocument chart, double beat, long packageOffset) {
        long offset = PlaybackTimeline.offsetMillis(chart.offsetMs, packageOffset);
        if (audioChart == chart && audioRevision == chart.revision() && audioOffset == offset
                && Double.doubleToLongBits(beat) == Double.doubleToLongBits(audioBeat)) return audioSeconds;
        double chartSeconds = seconds(chart, beat);
        return chartSeconds <= 0 ? Math.min(0, offset) / 1000.0 : chartSeconds + offset / 1000.0;
    }
}
