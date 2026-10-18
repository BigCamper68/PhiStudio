package com.xpe.mobile.audio;

/** Interpolates the consumed audio clock between block-sized AudioTrack observations. */
public final class PcmPresentationClock {
    public static final class Timestamp {
        public long framePosition, nanoTime;
    }
    private final int sampleRate;
    private final double startSeconds, endSeconds, speed;
    private long head, headTime, written, timestampFrame, timestampTime;
    private double presented;

    public PcmPresentationClock(int sampleRate, double startSeconds, double endSeconds, float speed) {
        this.sampleRate = sampleRate; this.startSeconds = startSeconds;
        this.endSeconds = endSeconds; this.speed = speed;
    }

    public void observe(long frames, long now, long writtenFrames) {
        long value = Math.max(0, frames);
        written = Math.max(written, Math.max(value, writtenFrames));
        if (value > head) { head = value; headTime = now; }
    }

    public void timestamp(Timestamp sample, long now) {
        if (sample.nanoTime <= 0 || sample.nanoTime > now || now - sample.nanoTime > 500_000_000L
                || sample.framePosition < 0 || sample.framePosition > written
                || sample.nanoTime <= timestampTime || sample.framePosition < timestampFrame) return;
        timestampFrame = sample.framePosition; timestampTime = sample.nanoTime;
    }

    public double seconds(long now) {
        // In particular, retain an exact negative start until the device actually consumes PCM.
        if (head == 0 && timestampFrame == 0) return startSeconds;
        double frames = head;
        if (headTime > 0 && now >= headTime)
            frames += Math.min(50_000_000L, now - headTime) * (sampleRate / 1_000_000_000.0);
        // Keep checkpoint3's playback-head time domain. A route timestamp may
        // describe an earlier presentation stage and must not pull the preview behind it.
        if (timestampTime > 0 && now >= timestampTime && now - timestampTime <= 500_000_000L)
            frames = Math.max(frames, timestampFrame + (now - timestampTime) * (sampleRate / 1_000_000_000.0));
        // Do not run ahead through an underrun, or beyond PCM already queued to the device.
        frames = Math.min(written, Math.min(frames, head + sampleRate * .05));
        presented = Math.max(presented, frames);
        return Math.min(endSeconds, startSeconds + presented * speed / sampleRate);
    }
}
