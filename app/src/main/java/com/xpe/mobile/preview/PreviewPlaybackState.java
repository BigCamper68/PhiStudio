package com.xpe.mobile.preview;

import java.util.HashMap;
import java.util.Map;

/** One preview's frame history; never shared between charts or independent viewers. */
public final class PreviewPlaybackState {
    final Map<Integer, PaintHistory> paint = new HashMap<>();
    private Object prepared;
    private double seconds = Double.NaN;
    private double aspect;
    private Object animationPrepared;
    private boolean animationHasLeftZero;
    boolean advanced;

    /** Clears paint history for seeks; the same viewer retains its animation origin state. */
    public void reset() {
        prepared = null;
        seconds = Double.NaN;
        paint.clear();
    }

    /** Native Anim starts at time/cursor zero until its first nonzero frame. */
    boolean initialZero(Object nextPrepared, double nextSeconds) {
        if (animationPrepared != nextPrepared) {
            animationPrepared = nextPrepared;
            animationHasLeftZero = false;
        }
        if (nextSeconds != 0.0) animationHasLeftZero = true;
        return nextSeconds == 0.0 && !animationHasLeftZero;
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
