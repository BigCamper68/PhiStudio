package com.xpe.mobile.audio;

import java.util.Arrays;

/** Immutable interleaved 16-bit PCM sample used by the hit-sound mixer. */
final class PcmHitSound {
    final int sampleRate;
    final int channelCount;
    private final short[] samples;

    PcmHitSound(int sampleRate, int channelCount, short[] samples) {
        if (sampleRate <= 0) throw new IllegalArgumentException("Sample rate must be positive");
        if (channelCount < 1 || channelCount > 2) {
            throw new IllegalArgumentException("Only mono or stereo PCM is supported");
        }
        if (samples == null || samples.length % channelCount != 0) {
            throw new IllegalArgumentException("PCM must contain complete audio frames");
        }
        this.sampleRate = sampleRate;
        this.channelCount = channelCount;
        this.samples = Arrays.copyOf(samples, samples.length);
    }

    static PcmHitSound fromLittleEndianPcm16(int sampleRate, int channelCount, byte[] pcm) {
        if (pcm == null || (pcm.length & 1) != 0) {
            throw new IllegalArgumentException("PCM must contain complete 16-bit samples");
        }
        short[] samples = new short[pcm.length / 2];
        for (int index = 0; index < samples.length; index++) {
            int byteIndex = index * 2;
            samples[index] = (short) ((pcm[byteIndex] & 0xff)
                    | pcm[byteIndex + 1] << 8);
        }
        return new PcmHitSound(sampleRate, channelCount, samples);
    }

    int frameCount() {
        return samples.length / channelCount;
    }

    short sample(int frame, int channel) {
        return samples[frame * channelCount + channel];
    }

    PcmHitSound convertTo(int targetSampleRate, int targetChannelCount) {
        if (targetSampleRate <= 0) {
            throw new IllegalArgumentException("Target sample rate must be positive");
        }
        if (targetChannelCount < 1 || targetChannelCount > 2) {
            throw new IllegalArgumentException("Target channel count must be mono or stereo");
        }
        if (frameCount() == 0) {
            return new PcmHitSound(targetSampleRate, targetChannelCount, new short[0]);
        }
        if (sampleRate == targetSampleRate && channelCount == targetChannelCount) return this;

        long scaledFrames = Math.round(frameCount() * (double) targetSampleRate / sampleRate);
        if (scaledFrames <= 0L || scaledFrames > Integer.MAX_VALUE / targetChannelCount) {
            throw new IllegalArgumentException("Converted PCM sample is too large");
        }
        int targetFrames = (int) scaledFrames;
        short[] converted = new short[targetFrames * targetChannelCount];
        for (int targetFrame = 0; targetFrame < targetFrames; targetFrame++) {
            double sourcePosition = targetFrame * (double) sampleRate / targetSampleRate;
            int sourceFrame = Math.min(frameCount() - 1, (int) sourcePosition);
            int nextFrame = Math.min(frameCount() - 1, sourceFrame + 1);
            double fraction = sourcePosition - sourceFrame;
            for (int targetChannel = 0; targetChannel < targetChannelCount; targetChannel++) {
                double first = channelSample(sourceFrame, targetChannel, targetChannelCount);
                double second = channelSample(nextFrame, targetChannel, targetChannelCount);
                converted[targetFrame * targetChannelCount + targetChannel] =
                        clampToPcm16(Math.round(first + (second - first) * fraction));
            }
        }
        return new PcmHitSound(targetSampleRate, targetChannelCount, converted);
    }

    private double channelSample(int frame, int targetChannel, int targetChannelCount) {
        if (channelCount == targetChannelCount) return sample(frame, targetChannel);
        if (channelCount == 1) return sample(frame, 0);
        return (sample(frame, 0) + (double) sample(frame, 1)) * 0.5;
    }

    private static short clampToPcm16(long value) {
        if (value > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (value < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) value;
    }
}
