package com.xpe.mobile.preview;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.ExtendedLineEvents;
import java.util.List;

/** PreviewGifEvaluator responsibilities extracted from ChartEvaluator. */
final class PreviewGifEvaluator {
    static GifState evaluateGifState(
            ChartDocument chart, PrprTime time, List<ExtendedLineEvents.NumericEvent> events,
            double beat) {
        return evaluateGifStateAtSeconds(chart, time, events, time.seconds(beat));
    }

    static GifState evaluateGifStateAtSeconds(
            ChartDocument chart, PrprTime time, List<ExtendedLineEvents.NumericEvent> events,
            double seconds) {
        ExtendedLineEvents.NumericEvent latest = PrprEvents.eventAtSeconds(events, time, seconds, false);
        if (latest == null) return new GifState(true, false, 0.0, 0L);
        double endBeat = latest.endTime == null
                ? latest.startTime.toDouble() : latest.endTime.toDouble();
        double endSeconds = time.seconds(endBeat);
        if (endBeat > latest.startTime.toDouble() && seconds < endSeconds) {
            return new GifState(true, true, PrprEvents.numericAtSeconds(events, time, seconds, 0, false), 0L);
        }
        return new GifState(true, false, latest.end,
                Math.round(Math.max(0.0, endSeconds) * 1000.0));
    }

    static final class GifState {
        final boolean enabled;
        final boolean controlled;
        final double progress;
        final long anchorTimeMs;

        GifState(boolean enabled, boolean controlled, double progress,
                 long anchorTimeMs) {
            this.enabled = enabled;
            this.controlled = controlled;
            this.progress = progress;
            this.anchorTimeMs = anchorTimeMs;
        }

        static GifState disabled() {
            return new GifState(false, false, 0.0, 0L);
        }
    }
}
