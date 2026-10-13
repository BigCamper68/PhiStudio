package com.xpe.mobile.preview;

import java.util.HashMap;
import java.util.Map;

/** One preview's frame history; never shared between charts or independent viewers. */
public final class PreviewPlaybackState {
    final Map<Integer, PaintHistory> paint = new HashMap<>();
    private Object prepared;
    private double seconds = Double.NaN;
    private double aspect;
    boolean advanced;

    public void reset() {
        prepared = null;
        seconds = Double.NaN;
        paint.clear();
    }

    boolean begin(Object nextPrepared, double nextSeconds, double nextAspect) {
        boolean rebuild = prepared != nextPrepared || aspect != nextAspect
                || nextSeconds < seconds || nextSeconds - seconds > .25;
        advanced = nextSeconds != seconds;
        if (rebuild) paint.clear();
        prepared = nextPrepared;
        seconds = nextSeconds;
        aspect = nextAspect;
        return rebuild;
    }
}
