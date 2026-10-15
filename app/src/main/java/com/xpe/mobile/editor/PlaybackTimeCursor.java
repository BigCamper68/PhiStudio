package com.xpe.mobile.editor;

import com.xpe.mobile.model.ChartDocument;

/** Remembers an exact clock timestamp while the editor also exposes its beat cursor. */
final class PlaybackTimeCursor {
    private ChartDocument chart;
    private long revision;
    private double beat = Double.NaN;
    private double seconds;

    double seconds(ChartDocument currentChart, double currentBeat) {
        if (currentChart == null) return 0.0;
        if (chart == currentChart && revision == currentChart.revision()
                && Double.doubleToLongBits(beat) == Double.doubleToLongBits(currentBeat)) {
            return seconds;
        }
        return currentChart.beatToSeconds(currentBeat);
    }

    double beatAtSeconds(ChartDocument currentChart, double requestedSeconds) {
        seconds = Double.isFinite(requestedSeconds) ? Math.max(0.0, requestedSeconds) : 0.0;
        chart = currentChart;
        revision = currentChart.revision();
        beat = currentChart.secondsToBeat(seconds);
        return beat;
    }
}
