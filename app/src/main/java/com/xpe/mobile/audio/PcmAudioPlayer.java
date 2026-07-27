package com.xpe.mobile.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.PlaybackParams;
import android.os.Handler;

import java.io.IOException;
import java.io.RandomAccessFile;

/** Plays decoded MP3 PCM with sample-accurate seeks and a playback-head based clock. */
public final class PcmAudioPlayer {
    public interface Listener {
        void onCompletion();

        void onError(String message);
    }

    private static final long WRITER_POLL_MS = 2L;
    private static final long COMPLETION_POLL_MS = 5L;

    private final Object lock = new Object();
    private final PcmAudioAsset asset;
    private final Handler callbackHandler;
    private final Listener listener;

    private AudioTrack activeTrack;
    private Thread worker;
    private int generation;
    private long playbackStartFrame;
    private long lastPositionMs;
    private boolean playRequested;
    private boolean released;
    private float volume = 1.0f;

    public PcmAudioPlayer(PcmAudioAsset asset, Handler callbackHandler, Listener listener) {
        if (asset == null || callbackHandler == null || listener == null) {
            throw new IllegalArgumentException("PCM asset, handler and listener are required");
        }
        this.asset = asset;
        this.callbackHandler = callbackHandler;
        this.listener = listener;
    }

    public void start(long positionMs, float speed) {
        stopCurrentPlayback();
        float targetSpeed = Math.max(0.25f, Math.min(2.0f, speed));
        long targetFrame = asset.frameForPositionMillis(positionMs);
        Thread nextWorker;
        int nextGeneration;
        synchronized (lock) {
            if (released) return;
            nextGeneration = ++generation;
            playbackStartFrame = targetFrame;
            lastPositionMs = asset.positionMillisForFrame(targetFrame);
            playRequested = true;
            nextWorker = new Thread(
                    () -> streamPcm(nextGeneration, targetFrame, targetSpeed),
                    "xpe-mp3-pcm-playback");
            worker = nextWorker;
        }
        nextWorker.start();
    }

    public void pause() {
        stopCurrentPlayback();
    }

    public void seek(long positionMs) {
        stopCurrentPlayback();
        long targetFrame = asset.frameForPositionMillis(positionMs);
        synchronized (lock) {
            if (released) return;
            playbackStartFrame = targetFrame;
            lastPositionMs = asset.positionMillisForFrame(targetFrame);
        }
    }

    public void release() {
        stopCurrentPlayback();
        synchronized (lock) {
            released = true;
            generation++;
        }
    }

    public boolean isPlaying() {
        synchronized (lock) {
            return playRequested;
        }
    }

    public long positionMillis() {
        synchronized (lock) {
            updatePositionFromPlaybackHeadLocked(activeTrack);
            return lastPositionMs;
        }
    }

    public long durationMillis() {
        return asset.durationMillis();
    }

    public void setVolume(float value) {
        float target = Math.max(0.0f, Math.min(1.0f, value));
        synchronized (lock) {
            volume = target;
            if (activeTrack != null) activeTrack.setVolume(target);
        }
    }

    private void stopCurrentPlayback() {
        AudioTrack track;
        Thread currentWorker;
        synchronized (lock) {
            updatePositionFromPlaybackHeadLocked(activeTrack);
            playRequested = false;
            generation++;
            track = activeTrack;
            activeTrack = null;
            currentWorker = worker;
            worker = null;
        }
        if (track != null) {
            try {
                track.pause();
                track.flush();
            } catch (IllegalStateException ignored) {
                // The worker is already releasing this track.
            }
        }
        if (currentWorker != null && currentWorker != Thread.currentThread()) {
            currentWorker.interrupt();
        }
    }

    private void streamPcm(int expectedGeneration, long targetFrame, float speed) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        } catch (RuntimeException ignored) {
            // Playback remains correct if an OEM refuses the priority hint.
        }
        AudioTrack track = null;
        try (RandomAccessFile pcm = new RandomAccessFile(asset.file, "r")) {
            int frameSize = asset.bytesPerFrame();
            int bufferSize = playbackBufferSize(frameSize);
            byte[] buffer = new byte[bufferSize];
            track = createTrack(bufferSize, speed);
            synchronized (lock) {
                if (!isCurrentLocked(expectedGeneration)) return;
                activeTrack = track;
            }

            pcm.seek(asset.byteOffsetForFrame(targetFrame));
            long framesWritten = 0L;
            int pendingOffset = 0;
            int pendingBytes = readAligned(pcm, buffer, frameSize);
            boolean endOfFile = pendingBytes < 0;

            if (!endOfFile) {
                int written = writeBlocking(track, buffer, 0, pendingBytes);
                framesWritten += written / frameSize;
                pendingOffset = written;
            }

            if (!isCurrent(expectedGeneration)) return;
            track.play();

            while (isCurrent(expectedGeneration) && !endOfFile) {
                if (pendingOffset >= pendingBytes) {
                    pendingBytes = readAligned(pcm, buffer, frameSize);
                    pendingOffset = 0;
                    if (pendingBytes < 0) {
                        endOfFile = true;
                        break;
                    }
                }
                int written = track.write(buffer, pendingOffset,
                        pendingBytes - pendingOffset, AudioTrack.WRITE_NON_BLOCKING);
                if (written > 0) {
                    if (written % frameSize != 0) {
                        throw new IOException("AudioTrack accepted a partial PCM frame");
                    }
                    pendingOffset += written;
                    framesWritten += written / frameSize;
                } else if (written == 0) {
                    Thread.sleep(WRITER_POLL_MS);
                } else {
                    throw new IOException("AudioTrack write failed: " + written);
                }
            }

            while (isCurrent(expectedGeneration)
                    && playbackHeadFrames(track) < framesWritten) {
                Thread.sleep(COMPLETION_POLL_MS);
            }
            if (isCurrent(expectedGeneration)) complete(expectedGeneration);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            fail(expectedGeneration, exception);
        } finally {
            synchronized (lock) {
                if (activeTrack == track) activeTrack = null;
                if (worker == Thread.currentThread()) worker = null;
            }
            releaseTrack(track);
        }
    }

    private AudioTrack createTrack(int bufferSize, float speed) throws IOException {
        int channelMask = asset.channelCount == 1
                ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(asset.sampleRate)
                .setChannelMask(channelMask)
                .build();
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build())
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IOException("Unable to initialize PCM AudioTrack");
        }
        PlaybackParams params = new PlaybackParams().allowDefaults()
                .setSpeed(speed)
                .setPitch(1.0f);
        track.setPlaybackParams(params);
        synchronized (lock) {
            track.setVolume(volume);
        }
        return track;
    }

    private int playbackBufferSize(int frameSize) throws IOException {
        int channelMask = asset.channelCount == 1
                ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minimum = AudioTrack.getMinBufferSize(asset.sampleRate, channelMask,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IOException("Unsupported PCM output format");
        long quarterSecond = asset.sampleRate * (long) frameSize / 4L;
        long requested = Math.max(minimum, quarterSecond);
        if (requested > Integer.MAX_VALUE) throw new IOException("PCM buffer is too large");
        int aligned = (int) requested;
        aligned -= aligned % frameSize;
        return Math.max(frameSize, aligned);
    }

    private int writeBlocking(AudioTrack track, byte[] data, int offset, int length)
            throws IOException {
        int total = 0;
        while (total < length) {
            int written = track.write(data, offset + total, length - total,
                    AudioTrack.WRITE_BLOCKING);
            if (written <= 0) throw new IOException("AudioTrack prebuffer failed: " + written);
            total += written;
        }
        return total;
    }

    private static int readAligned(RandomAccessFile input, byte[] buffer, int frameSize)
            throws IOException {
        int read = input.read(buffer);
        if (read < 0) return -1;
        if (read % frameSize != 0) {
            throw new IOException("PCM cache contains a partial frame");
        }
        return read;
    }

    private boolean isCurrent(int expectedGeneration) {
        synchronized (lock) {
            return isCurrentLocked(expectedGeneration);
        }
    }

    private boolean isCurrentLocked(int expectedGeneration) {
        return !released && playRequested && generation == expectedGeneration;
    }

    private void complete(int expectedGeneration) {
        boolean notify = false;
        synchronized (lock) {
            if (isCurrentLocked(expectedGeneration)) {
                lastPositionMs = asset.durationMillis();
                playRequested = false;
                notify = true;
            }
        }
        if (notify) callbackHandler.post(listener::onCompletion);
    }

    private void fail(int expectedGeneration, Exception exception) {
        boolean notify = false;
        synchronized (lock) {
            if (generation == expectedGeneration && !released) {
                playRequested = false;
                notify = true;
            }
        }
        if (notify) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            callbackHandler.post(() -> listener.onError(message));
        }
    }

    private void updatePositionFromPlaybackHeadLocked(AudioTrack track) {
        if (track == null) return;
        try {
            long headFrames = playbackHeadFrames(track);
            lastPositionMs = Math.max(lastPositionMs,
                    PcmPlaybackClock.positionMillis(asset, playbackStartFrame, headFrames));
        } catch (IllegalStateException ignored) {
            // Keep the last stable position while a cancelled worker releases the track.
        }
    }

    private static long playbackHeadFrames(AudioTrack track) {
        return Integer.toUnsignedLong(track.getPlaybackHeadPosition());
    }

    private static void releaseTrack(AudioTrack track) {
        if (track == null) return;
        try {
            if (track.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) track.stop();
        } catch (IllegalStateException ignored) {
            // Release is still required after a route or device failure.
        }
        track.release();
    }
}
