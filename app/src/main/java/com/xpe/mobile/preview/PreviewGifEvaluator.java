package com.xpe.mobile.preview;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.ExtendedLineEvents;
import java.util.List;

/** PreviewGifEvaluator responsibilities extracted from ChartEvaluator. */
final class PreviewGifEvaluator {
    static GifState evaluateGifState(
            ChartDocument chart, PrprTime time, List<ExtendedLineEvents.NumericEvent> events,
            double beat) {
        ExtendedLineEvents.NumericEvent latest = PrprEvents.event(events, beat, false);
        if (latest == null) return new GifState(true, false, 0.0, 0L);
        double endBeat = latest.endTime == null
                ? latest.startTime.toDouble() : latest.endTime.toDouble();
        if (endBeat > latest.startTime.toDouble() && beat < endBeat) {
            return new GifState(true, true, PrprEvents.numeric(events, time, beat, 0, false), 0L);
        }
        return new GifState(true, false, latest.end,
                chart.beatToMillis(Math.max(0.0, endBeat)));
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
