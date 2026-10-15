package com.xpe.mobile.preview;

import com.xpe.mobile.model.ExtendedLineEvents.NumericEvent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Native RPE GIF keyframes, including automatic repetitions between authored events. */
public final class PreviewGifTimeline {
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;
    private static final BigInteger U128_MAX = ONE.shiftLeft(128).subtract(ONE);
    private static final double U128_LIMIT = 0x1.0p128;
    private static final double TAIL_SECONDS = 2000.0;
    private final List<Event> events;
    private volatile Compiled compiled;

    private PreviewGifTimeline(List<Event> events) {
        this.events = Collections.unmodifiableList(events);
    }

    /** Copy event fields now so editor changes cannot alter an already prepared frame. */
    static PreviewGifTimeline from(PrprTime time, List<NumericEvent> source) {
        List<Event> events = new ArrayList<>();
        if (source != null) {
            for (NumericEvent original : source) {
                if (original != null && original.startTime != null) {
                    events.add(new Event(time.seconds(original.startTime.toDouble()),
                            time.seconds((original.endTime == null ? original.startTime : original.endTime).toDouble()),
                            original));
                }
            }
        }
        return new PreviewGifTimeline(events);
    }

    /** Millisecond position consumed by Android Movie; invalid durations draw frame zero. */
    public int movieTimeMillis(double chartSeconds, int durationMs) {
        if (durationMs <= 0 || !Double.isFinite(chartSeconds)) return 0;
        float progress = rawProgressAt(chartSeconds, durationMs);
        // GifFrames::get_prog_frame multiplies in f32, saturates to u128 and only
        // then applies modulo. Keeping that order matters at frame boundaries.
        float millis = progress * (float) durationMs;
        if (millis <= 0f || Float.isNaN(millis)) return 0;
        if (millis < 0x1.0p63f) return (int) ((long) millis % durationMs);
        return unsigned128(millis).mod(BigInteger.valueOf(durationMs)).intValue();
    }

    /** Raw original Anim value; exposed for independent CPU reference diagnostics. */
    public float rawProgressAt(double chartSeconds, int durationMs) {
        if (durationMs <= 0 || !Double.isFinite(chartSeconds)) return Float.NaN;
        Compiled result = compiled;
        if (result == null || result.duration != durationMs) {
            result = compile(durationMs);
            compiled = result;
        }
        return result.at(chartSeconds);
    }

    private Compiled compile(int durationMs) {
        List<Chunk> chunks = new ArrayList<>();
        chunks.add(new Single(new Key(0.0, 0f, null, false)));
        BigInteger next = ZERO;
        for (Event event : events) {
            BigInteger cycles = repetitionsBefore(next, durationMs, event.startTime);
            if (cycles.signum() > 0) {
                chunks.add(new Repetitions(next, durationMs, cycles));
                next = next.add(cycles.multiply(BigInteger.valueOf(durationMs)));
            }
            float stop = (float) (1.0 - (next.doubleValue() - event.startTime * 1000.0) / durationMs);
            chunks.add(new Single(new Key(event.startTime, stop, null, true)));
            chunks.add(new Single(new Key(event.startTime, event.start, event, false)));
            chunks.add(new Single(new Key(event.endTime, event.end, null, false)));
            // The subtraction happens in f32 in parse_gif_events, before f64
            // multiplication and Rust's round-away-from-zero/u128 saturation.
            double anchor = event.endTime * 1000.0 + durationMs * (double) (1f - event.end);
            next = unsigned128(roundPositive(anchor));
        }
        BigInteger cycles = repetitionsBefore(next, durationMs, TAIL_SECONDS);
        if (cycles.signum() > 0) chunks.add(new Repetitions(next, durationMs, cycles));
        return new Compiled(durationMs, chunks);
    }

    private static double roundPositive(double value) {
        if (!Double.isFinite(value) || value <= 0.0) return value;
        double whole = Math.floor(value);
        return value - whole >= 0.5 ? whole + 1.0 : whole;
    }

    private static BigInteger unsigned128(double value) {
        if (Double.isNaN(value) || value <= 0.0) return ZERO;
        if (value >= U128_LIMIT) return U128_MAX;
        return new BigDecimal(value).toBigInteger();
    }

    /** Count the original while-loop iterations without allocating its keyframes. */
    private static BigInteger repetitionsBefore(BigInteger start, int duration, double endSeconds) {
        if (!Double.isFinite(endSeconds) || !(endSeconds > start.doubleValue() / 1000.0)) return ZERO;
        BigInteger step = BigInteger.valueOf(duration);
        BigInteger limit = U128_MAX.subtract(start).divide(step);
        BigInteger estimate = new BigDecimal(endSeconds).multiply(BigDecimal.valueOf(1000))
                .subtract(new BigDecimal(start)).divide(BigDecimal.valueOf(duration), 0, RoundingMode.CEILING)
                .toBigInteger().max(ONE).min(limit);
        BigInteger low = ZERO, high = estimate;
        while (high.compareTo(limit) < 0 && before(start, step, high, endSeconds)) {
            high = high.shiftLeft(1).add(ONE).min(limit);
        }
        // An authored time beyond u128's representable clock would make the
        // upstream release loop overflow forever. Keep the preview bounded.
        if (before(start, step, high, endSeconds)) return high;
        while (high.subtract(low).compareTo(ONE) > 0) {
            BigInteger middle = low.add(high).shiftRight(1);
            if (before(start, step, middle, endSeconds)) low = middle;
            else high = middle;
        }
        return high;
    }

    private static boolean before(BigInteger start, BigInteger step, BigInteger count, double endSeconds) {
        return endSeconds > start.add(step.multiply(count)).doubleValue() / 1000.0;
    }

    private static final class Event {
        final double startTime, endTime;
        final float start, end, left, right;
        final int easing;
        final boolean bezier;
        final double[] points;

        Event(double startTime, double endTime, NumericEvent original) {
            this.startTime = startTime;
            this.endTime = endTime;
            start = (float) original.start;
            end = (float) original.end;
            left = (float) original.easingLeft;
            right = (float) original.easingRight;
            easing = original.easingType;
            bezier = original.bezier;
            points = original.bezierPoints.clone();
        }

        float tween(float progress) {
            return (float) PrprEvents.progress(progress, easing, left, right, bezier, points);
        }
    }

    private static final class Key {
        final double time;
        final float value;
        final Event event;
        final boolean hold;

        Key(double time, float value, Event event, boolean hold) {
            this.time = time;
            this.value = value;
            this.event = event;
            this.hold = hold;
        }

        float at(Key next, double seconds) {
            float progress = (float) ((seconds - time) / (next.time - time));
            float eased = hold ? 0f : event == null ? progress : event.tween(progress);
            return value + (next.value - value) * eased;
        }
    }

    private interface Chunk {
        Key first();
        Key last();
        /** First key strictly after seconds, or null after consuming the chunk. */
        Key after(double seconds);
        /** Last key at or before seconds, only called when first.time <= seconds. */
        Key through(double seconds);
    }

    private static final class Single implements Chunk {
        final Key key;
        Single(Key key) { this.key = key; }
        public Key first() { return key; }
        public Key last() { return key; }
        public Key after(double seconds) { return key.time > seconds ? key : null; }
        public Key through(double seconds) { return key; }
    }

    /** Each repetition is the original pair (1, Hold), (0, Linear) at one time. */
    private static final class Repetitions implements Chunk {
        final BigInteger start, step, count;
        final Key first, last;

        Repetitions(BigInteger start, int duration, BigInteger count) {
            this.start = start;
            this.step = BigInteger.valueOf(duration);
            this.count = count;
            first = key(ZERO, true);
            last = key(count.subtract(ONE), false);
        }

        private double time(BigInteger index) {
            return start.add(step.multiply(index)).doubleValue() / 1000.0;
        }

        private Key key(BigInteger index, boolean firstInPair) {
            return new Key(time(index), firstInPair ? 1f : 0f, null, firstInPair);
        }

        private BigInteger nextIndex(double seconds) {
            BigInteger low = ZERO, high = count;
            while (low.compareTo(high) < 0) {
                BigInteger middle = low.add(high).shiftRight(1);
                if (time(middle) <= seconds) low = middle.add(ONE);
                else high = middle;
            }
            return low;
        }

        public Key first() { return first; }
        public Key last() { return last; }
        public Key after(double seconds) {
            if (first.time > seconds) return first;
            if (last.time <= seconds) return null;
            return key(nextIndex(seconds), true);
        }
        public Key through(double seconds) {
            if (last.time <= seconds) return last;
            return key(nextIndex(seconds).subtract(ONE), false);
        }
    }

    private static final class Compiled {
        final int duration;
        final List<Chunk> chunks;
        Compiled(int duration, List<Chunk> chunks) {
            this.duration = duration;
            this.chunks = chunks;
        }

        float at(double seconds) {
            Key current = chunks.get(0).first();
            // Anim::set_time(0) leaves its constructor cursor at zero. Native
            // float-to-u128 conversion renders any initial NaN as frame zero.
            if (seconds == 0.0) return current.at(chunks.get(1).first(), seconds);
            for (int i = 1; i < chunks.size(); i++) {
                Chunk chunk = chunks.get(i);
                Key next = chunk.after(seconds);
                if (next != null) {
                    if (chunk.first().time <= seconds) current = chunk.through(seconds);
                    return current.at(next, seconds);
                }
                current = chunk.last();
            }
            return current.value;
        }
    }
}
