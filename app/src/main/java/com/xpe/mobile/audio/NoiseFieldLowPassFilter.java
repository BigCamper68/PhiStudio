package com.xpe.mobile.audio;

/** Music-only PCM16 low-pass. Phigros' serialized preset is 1500 Hz / 0.1 s.
 * The published ProgressControl enables/bypasses immediately and animates its
 * stored cutoff, including while bypassed. Unity's private DSP is replaced by
 * the standard maximally-flat biquad (Audio EQ Cookbook, Q = 1/sqrt(2)).
 * One instance belongs to one playback worker; transport restarts discard it.
 */
final class NoiseFieldLowPassFilter {
    private static final double BLOCKED_HZ = 1500, OPEN_HZ = 22000;
    private final int rate, channels, duration;
    private final double[] x1, x2, y1, y2;
    private boolean enabled, used;
    private double cutoff = BLOCKED_HZ, start = BLOCKED_HZ, target = BLOCKED_HZ;
    private int remaining, coefficientFrame;
    private double b0, b1, b2, a1, a2;

    NoiseFieldLowPassFilter(int sampleRate, int channelCount) {
        if (sampleRate <= 0 || channelCount < 1 || channelCount > 2)
            throw new IllegalArgumentException("PCM rate and mono/stereo channels are required");
        rate = sampleRate; channels = channelCount; duration = Math.max(1, rate / 10);
        x1 = new double[channels]; x2 = new double[channels];
        y1 = new double[channels]; y2 = new double[channels];
        coefficients();
    }

    void process(byte[] pcm, int count, boolean touching) {
        if (count < 0 || count > pcm.length || count % (channels * 2) != 0)
            throw new IllegalArgumentException("Complete PCM frames are required");
        if (enabled != touching) {
            enabled = touching; start = cutoff; target = touching ? BLOCKED_HZ : OPEN_HZ;
            remaining = used ? duration : 0;
            if (touching) used = true;
            if (remaining == 0) cutoff = target;
            coefficientFrame = 0; coefficients();
        }
        int frames = count / (channels * 2);
        if (!enabled) {
            // Bit-exact bypass, without walking all PCM samples in ordinary charts.
            advance(frames);
            for (int c = 0; c < channels && frames > 0; c++) {
                x2[c] = frames > 1 ? sample(pcm, (frames - 2) * channels + c) : x1[c];
                x1[c] = sample(pcm, (frames - 1) * channels + c);
                y2[c] = x2[c]; y1[c] = x1[c];
            }
            return;
        }
        for (int frame = 0; frame < frames; frame++) {
            if (remaining > 0) {
                advance(1);
                if ((coefficientFrame++ & 31) == 0 || remaining == 0) coefficients();
            }
            for (int c = 0; c < channels; c++) {
                int index = frame * channels + c;
                double input = sample(pcm, index);
                double output = b0 * input + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c];
                x2[c] = x1[c]; x1[c] = input; y2[c] = y1[c]; y1[c] = output;
                int value = (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(output)));
                pcm[index * 2] = (byte) value; pcm[index * 2 + 1] = (byte) (value >> 8);
            }
        }
    }

    private void advance(int frames) {
        if (remaining == 0) return;
        remaining = Math.max(0, remaining - frames);
        cutoff = start + (target - start) * (1 - remaining / (double) duration);
    }
    private void coefficients() {
        double omega = 2 * Math.PI * Math.min(cutoff, rate * .49) / rate;
        double cos = Math.cos(omega), alpha = Math.sin(omega) / Math.sqrt(2), norm = 1 + alpha;
        b0 = (1 - cos) / (2 * norm); b1 = 2 * b0; b2 = b0;
        a1 = -2 * cos / norm; a2 = (1 - alpha) / norm;
    }
    private static int sample(byte[] pcm, int index) {
        return (short) ((pcm[index * 2] & 255) | (pcm[index * 2 + 1] << 8));
    }
}
