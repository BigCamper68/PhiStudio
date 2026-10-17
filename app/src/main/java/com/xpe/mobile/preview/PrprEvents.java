package com.xpe.mobile.preview;

import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.NoteControlEvents.ControlEvent;
import com.xpe.mobile.model.ExtendedLineEvents;
import java.util.List;

/** RPE parser/Anim semantics, kept separate from the editor's beat-domain curves. */
final class PrprEvents {
    static final float RPE_ALPHA_RATIO = 1f / 255f;
    private PrprEvents() { }

    static double progress(double input, int easing, double left, double right,
                           boolean bezier, double[] points) {
        // RPEEvent::tween returns the whole Bezier, ignoring the easing window.
        if (bezier) return PrprBezier.value((float) input, points);
        return PrprEasing.window(easing, (float) input, (float) left, (float) right);
    }

    static double control(List<ControlEvent> events, double height, double fallback) {
        if (events == null || events.isEmpty()) return fallback;
        ControlEvent first = events.get(0);
        if (events.size() == 2 && first.easing == 1 && Math.abs(first.value - 1) < 1e-4) return fallback;
        for (int i = 1; i < events.size(); i++) {
            ControlEvent next = events.get(i), previous = events.get(i - 1);
            if (height < next.x) {
                double t = (height - previous.x) / (next.x - previous.x);
                return previous.value + (next.value - previous.value) * PrprEasing.value(next.easing, (float) t);
            }
        }
        return events.get(events.size() - 1).value;
    }

    static double value(LineEvent event, PrprTime time, double seconds) {
        double start = time.seconds(event.startTime.toDouble());
        double end = time.seconds(event.endTime.toDouble());
        return value(event, start, end, seconds);
    }

    static double value(LineEvent event, double start, double end, double seconds) {
        if (seconds >= end) return event.end;
        if (end <= start) return event.start;
        return event.start + (event.end - event.start) * progress((seconds - start) / (end - start),
                event.easingType, event.easingLeft, event.easingRight, event.bezier, event.bezierPoints);
    }

    static float rotationValue(LineEvent event, double start, double end, double seconds) {
        float from = (float) event.start, to = (float) event.end;
        if (seconds >= end) return to;
        if (end <= start) return from;
        return from + (to - from) * (float) progress((seconds - start) / (end - start),
                event.easingType, event.easingLeft, event.easingRight, event.bezier, event.bezierPoints);
    }

    /** PEC AnimFloat interpolates normalized positive opacity and unscaled negative flags. */
    static float pecAlphaValue(LineEvent event, double start, double end, double seconds) {
        float from = pecAlphaEndpoint(event.start);
        float to = pecAlphaEndpoint(event.end);
        if (seconds >= end) return to;
        if (end <= start) return from;
        float progress = (float) progress((seconds - start) / (end - start),
                event.easingType, event.easingLeft, event.easingRight, event.bezier, event.bezierPoints);
        return from + (to - from) * progress;
    }

    private static float pecAlphaEndpoint(double authored) {
        float value = (float) authored;
        return value >= 0f ? value / 255f : value;
    }

    /** RPE maps authored opacity endpoints before the f32 Tweenable calculation. */
    static float rpeAlphaValue(LineEvent event, double start, double end, double seconds) {
        float from = (float) event.start * RPE_ALPHA_RATIO;
        float to = (float) event.end * RPE_ALPHA_RATIO;
        if (seconds >= end) return to;
        if (end <= start) return from;
        float progress = (float) progress((seconds - start) / (end - start),
                event.easingType, event.easingLeft, event.easingRight, event.bezier, event.bezierPoints);
        return from + (to - from) * progress;
    }

    static <T extends ExtendedLineEvents.TimedEvent> T event(List<T> events, double beat, boolean preroll) {
        T latest = null;
        for (T event : events) {
            // Anim advances through start/end keyframes in source order. An overlapping
            // event cannot take over until the previous event's end keyframe is reached.
            if (latest != null && beat < latest.endTime.toDouble()) break;
            if (event.startTime.toDouble() <= beat) latest = event;
            else break;
        }
        return latest != null ? latest : preroll && !events.isEmpty() ? events.get(0) : null;
    }

    static <T extends ExtendedLineEvents.TimedEvent> T eventAtSeconds(
            List<T> events, PrprTime time, double seconds, boolean preroll) {
        T latest = null;
        for (T event : events) {
            // Match Anim's source-order keyframes, including overlapping intervals.
            if (latest != null && seconds < time.seconds(latest.endTime.toDouble())) break;
            if (time.seconds(event.startTime.toDouble()) <= seconds) latest = event;
            else break;
        }
        return latest != null ? latest : preroll && !events.isEmpty() ? events.get(0) : null;
    }

    static double progress(ExtendedLineEvents.TimedEvent event, PrprTime time, double beat) {
        return progressAtSeconds(event, time, time.seconds(beat));
    }

    static double progressAtSeconds(ExtendedLineEvents.TimedEvent event, PrprTime time, double now) {
        double start = time.seconds(event.startTime.toDouble());
        double end = time.seconds(event.endTime.toDouble());
        if (now >= end) return 1;
        if (end <= start) return 0;
        return progress((now - start) / (end - start), event.easingType, event.easingLeft,
                event.easingRight, event.bezier, event.bezierPoints);
    }

    static double numeric(List<ExtendedLineEvents.NumericEvent> events, PrprTime time,
                          double beat, double fallback, boolean preroll) {
        return numericAtSeconds(events, time, time.seconds(beat), fallback, preroll);
    }

    static double numericAtSeconds(List<ExtendedLineEvents.NumericEvent> events, PrprTime time,
                                   double seconds, double fallback, boolean preroll) {
        ExtendedLineEvents.NumericEvent event = eventAtSeconds(events, time, seconds, preroll);
        if (event == null) return fallback;
        float from = (float) event.start, to = (float) event.end;
        return from + (to - from) * (float) progressAtSeconds(event, time, seconds);
    }

    static int color(List<ExtendedLineEvents.ColorEvent> events, PrprTime time, double beat, int fallback) {
        return colorAtSeconds(events, time, time.seconds(beat), fallback);
    }

    static int colorAtSeconds(List<ExtendedLineEvents.ColorEvent> events, PrprTime time,
                              double seconds, int fallback) {
        ExtendedLineEvents.ColorEvent event = eventAtSeconds(events, time, seconds, false);
        if (event == null) return fallback;
        double t = progressAtSeconds(event, time, seconds);
        int result = 0;
        for (int shift : new int[]{16, 8, 0}) {
            double from = (event.startRgb >> shift) & 255, to = (event.endRgb >> shift) & 255;
            result |= (int) Math.round(Math.max(0, Math.min(255, from + (to - from) * t))) << shift;
        }
        return result;
    }

    static String text(List<ExtendedLineEvents.TextEvent> events, PrprTime time, double beat) {
        return textAtSeconds(events, time, time.seconds(beat));
    }

    static String textAtSeconds(List<ExtendedLineEvents.TextEvent> events, PrprTime time, double seconds) {
        ExtendedLineEvents.TextEvent event = eventAtSeconds(events, time, seconds, false);
        if (event == null) return "";
        // At the end keyframe Anim returns the literal string, including %P%.
        if (seconds >= time.seconds(event.endTime.toDouble())) return event.end;
        return ExtendedLineEvents.TextEvent.tween(event.start, event.end,
                Math.max(0, Math.min(1, progressAtSeconds(event, time, seconds))));
    }
}
