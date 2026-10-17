package com.xpe.mobile.audio;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounded streaming pitch-preserving time stretch of MUSIC ONLY, before hit mixing. */
final class PcmMusicStream {
    private final RandomAccessFile input;
    private final int frameSize;
    private final Sonic sonic;
    private final byte[] source;
    private long remainingInputFrames;
    private long remainingOutputFrames;
    private long leadingOutputFrames;
    private boolean ended;

    PcmMusicStream(RandomAccessFile input, PcmAudioAsset asset, long startFrame, float speed)
            throws IOException {
        this.input = input;
        frameSize = asset.bytesPerFrame();
        remainingInputFrames = asset.totalFrames - Math.max(0, startFrame);
        remainingOutputFrames = Math.round(remainingInputFrames / (double) speed);
        if (startFrame < 0) {
            remainingOutputFrames = Math.round((asset.totalFrames - (double) startFrame) / speed);
            leadingOutputFrames = Math.round(-startFrame / (double) speed);
        }
        source = new byte[Math.max(1, asset.sampleRate / 100) * frameSize];
        sonic = speed == 1f ? null
                : new Sonic(asset.sampleRate, asset.channelCount, speed, 1f, asset.sampleRate, false);
        input.seek(asset.byteOffsetForFrame(startFrame));
    }

    int read(byte[] output) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (remainingOutputFrames == 0) return -1;
        if (leadingOutputFrames > 0) {
            int frames = (int) Math.min(output.length / frameSize, leadingOutputFrames);
            if (frames == 0) throw new IllegalArgumentException("Output must hold a complete PCM frame");
            java.util.Arrays.fill(output, 0, frames * frameSize, (byte) 0);
            leadingOutputFrames -= frames; remainingOutputFrames -= frames;
            return frames * frameSize;
        }
        int length = (int) Math.min(output.length / frameSize, remainingOutputFrames) * frameSize;
        if (length == 0) throw new IllegalArgumentException("Output must hold a complete PCM frame");
        if (sonic == null) {
            input.readFully(output, 0, length);
        } else {
            while (!ended && sonic.getOutputSize() < length) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (remainingInputFrames == 0) {
                    sonic.queueEndOfStream();
                    ended = true;
                } else {
                    int count = (int) Math.min(source.length / frameSize, remainingInputFrames) * frameSize;
                    input.readFully(source, 0, count);
                    sonic.queueInput(ByteBuffer.wrap(source, 0, count).order(ByteOrder.LITTLE_ENDIAN));
                    remainingInputFrames -= count / frameSize;
                }
            }
            int available = Math.min(length, sonic.getOutputSize());
            sonic.getOutput(ByteBuffer.wrap(output, 0, available).order(ByteOrder.LITTLE_ENDIAN));
            // Keep exact transport duration even if Sonic rounds its final grain by a frame.
            java.util.Arrays.fill(output, available, length, (byte) 0);
        }
        remainingOutputFrames -= length / frameSize;
        return length;
    }
}
