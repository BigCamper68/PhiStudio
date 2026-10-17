package com.xpe.mobile.audio;

/** Signed audio timeline: a negative chart offset includes silence before music starts. */
public final class PlaybackTimeline {
    private PlaybackTimeline() {}
    public static long offsetMillis(long chartOffset, long packageOffset) {
        return ScheduledHitSoundMixer.addSaturated(chartOffset, packageOffset);
    }
    public static long startMillis(long chartOffset, long packageOffset) {
        return Math.min(0, offsetMillis(chartOffset, packageOffset));
    }
    public static double chartSeconds(double audioSeconds, long chartOffset, long packageOffset) {
        return Math.max(0, audioSeconds - offsetMillis(chartOffset, packageOffset) / 1000.0);
    }
    public static long audioMillis(double chartSeconds, long chartOffset, long packageOffset) {
        if (chartSeconds <= 0) return startMillis(chartOffset, packageOffset);
        return ScheduledHitSoundMixer.addSaturated(Math.round(chartSeconds * 1000),
                offsetMillis(chartOffset, packageOffset));
    }
}
