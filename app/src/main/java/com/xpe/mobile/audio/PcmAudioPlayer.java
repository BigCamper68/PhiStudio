package com.xpe.mobile.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.AudioTimestamp;
import android.media.PlaybackParams;
import android.os.Handler;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.function.Consumer;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.concurrent.locks.ReentrantLock;

/** Sample-addressed music and hit playback with one output clock and atomic transport changes. */
public final class PcmAudioPlayer {
    public interface Listener {
        void onCompletion();
        void onError(String message);
    }

    // Small injectable boundary for deterministic seek/cancellation tests without Android JNI.
    interface Output {
        int write(byte[] pcm, int offset, int length) throws IOException;
        void play();
        void pause();
        void flush();
        long headFrames();
        default boolean timestamp(PcmPresentationClock.Timestamp value) { return false; }
        default int startThresholdFrames() { return 0; }
        void release();
    }
    interface OutputFactory {
        Output create(PcmAudioAsset asset, float speed) throws IOException;
    }

    private final Object lock = new Object();
    // A cancelled native creation may finish late. Keep its entire allocation/release
    // lifetime separate from the UI lock, so a replacement never competes for its route.
    private final ReentrantLock outputLifecycle = new ReentrantLock();
    private final PcmAudioAsset asset;
    private final Consumer<Runnable> post;
    private final Listener listener;
    private final OutputFactory outputFactory;
    private Output activeTrack;
    private Thread worker;
    private int generation;
    private long playbackStartFrame;
    private float playbackSpeed = 1f;
    private long lastPositionMs;
    private double lastPositionSeconds;
    private long writtenOutputFrames;
    private PcmPresentationClock presentationClock;
    private final PcmPresentationClock.Timestamp timestamp = new PcmPresentationClock.Timestamp();
    private boolean playRequested;
    private boolean released;
    private volatile float volume = 1f;
    private volatile float hitVolume = 1f;
    private HitSoundTimeline timeline = HitSoundTimeline.empty();
    private long audioOffsetMs;
    private PcmHitSound[] sounds;
    private Map<String,PcmHitSound> customSounds = Collections.emptyMap();

    public PcmAudioPlayer(PcmAudioAsset asset, Handler handler, Listener listener) {
        this(asset, callback -> handler.post(callback), listener, PcmAudioPlayer::createTrack);
    }

    PcmAudioPlayer(PcmAudioAsset asset, Consumer<Runnable> post, Listener listener,
                   OutputFactory factory) {
        if (asset == null || post == null || listener == null || factory == null) {
            throw new IllegalArgumentException("PCM asset, callback and output are required");
        }
        this.asset = asset;
        this.post = post;
        this.listener = listener;
        outputFactory = factory;
    }

    void configureHitSounds(HitSoundTimeline timeline, long offsetMs, PcmHitSound[] samples) {
        configureHitSounds(timeline, offsetMs, samples, Collections.emptyMap());
    }

    void configureHitSounds(HitSoundTimeline timeline, long offsetMs, PcmHitSound[] samples,
                            Map<String,PcmHitSound> customSamples) {
        synchronized (lock) {
            this.timeline = timeline == null ? HitSoundTimeline.empty() : timeline;
            audioOffsetMs = offsetMs;
            sounds = samples == null ? null : samples.clone();
            customSounds = Collections.unmodifiableMap(new HashMap<>(customSamples));
        }
    }

    public void start(long positionMs, float speed) {
        Thread next;
        synchronized (lock) {
            if (released) return;
            stopLocked();
            int expected = ++generation;
            long targetFrame = asset.timelineFrameForPositionMillis(positionMs);
            playbackStartFrame = targetFrame;
            lastPositionMs = Math.min(positionMs, asset.durationMillis());
            lastPositionSeconds = lastPositionMs / 1000.0;
            writtenOutputFrames = 0;
            playRequested = true;
            float rate = Float.isFinite(speed) ? Math.max(.25f, Math.min(2f, speed)) : 1f;
            playbackSpeed = rate;
            presentationClock = new PcmPresentationClock(asset.sampleRate, lastPositionSeconds,
                    asset.totalFrames / (double) asset.sampleRate, rate);
            HitSoundTimeline schedule = timeline;
            long offset = audioOffsetMs;
            PcmHitSound[] samples = sounds;
            Map<String,PcmHitSound> custom = customSounds;
            next = new Thread(() -> stream(expected, targetFrame, rate, schedule, offset, samples, custom),
                    "phistudio-pcm-playback");
            next.setDaemon(true);
            worker = next;
        }
        next.start();
    }

    public void pause() {
        synchronized (lock) { stopLocked(); }
    }

    public void seek(long positionMs) {
        synchronized (lock) {
            if (released) return;
            stopLocked();
            playbackStartFrame = asset.timelineFrameForPositionMillis(positionMs);
            lastPositionMs = Math.min(positionMs, asset.durationMillis());
            lastPositionSeconds = lastPositionMs / 1000.0;
            presentationClock = null;
        }
    }

    public void release() {
        synchronized (lock) {
            if (released) return;
            stopLocked();
            released = true;
        }
    }

    public boolean isPlaying() {
        synchronized (lock) { return playRequested; }
    }

    public long positionMillis() {
        synchronized (lock) {
            updatePositionLocked();
            return lastPositionMs;
        }
    }

    public double positionSeconds() {
        synchronized (lock) {
            // Read the live counter for this frame, just as checkpoint3 did.
            updatePositionLocked();
            return lastPositionSeconds;
        }
    }

    public long durationMillis() { return asset.durationMillis(); }
    public void setVolume(float value) { volume = safeVolume(value); }
    public void setHitVolume(float value) { hitVolume = safeVolume(value); }

    private static float safeVolume(float value) {
        return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0f;
    }

    // ALL native play/write/pause/flush operations share this lock and generation check.
    private void stopLocked() {
        generation++;
        playRequested = false;
        if (activeTrack != null) {
            try {
                activeTrack.pause();
                updatePositionLocked();
                activeTrack.flush();
            } catch (IllegalStateException ignored) { }
            activeTrack = null;
        }
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private void stream(int expected, long target, float speed, HitSoundTimeline schedule,
                        long offsetMs, PcmHitSound[] samples, Map<String,PcmHitSound> custom) {
        try {
            outputLifecycle.lockInterruptibly();
            try {
                // Waiting replacements can themselves be cancelled by another seek/start.
                if (isCurrent(expected)) streamCurrent(expected, target, speed, schedule,
                        offsetMs, samples, custom);
            } finally {
                outputLifecycle.unlock();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (lock) {
                if (worker == Thread.currentThread()) worker = null;
            }
        }
    }

    private void streamCurrent(int expected, long target, float speed, HitSoundTimeline schedule,
                        long offsetMs, PcmHitSound[] samples, Map<String,PcmHitSound> custom) {
        // There is nothing to stream at EOF, even if this device currently has no audio route.
        if (target >= asset.totalFrames) { complete(expected); return; }
        Output output = null;
        try (RandomAccessFile input = new RandomAccessFile(asset.file, "r")) {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO); }
            catch (RuntimeException ignored) { }
            int frameSize = asset.bytesPerFrame();
            byte[] buffer = new byte[Math.max(1, asset.sampleRate / 100) * frameSize];
            ScheduledHitSoundMixer mixer = new ScheduledHitSoundMixer(
                    asset, schedule, offsetMs, samples, custom, target, speed);
            PcmMusicStream music = new PcmMusicStream(input, asset, target, speed);
            // Native speed MUST stay 1x: only the music has been time-stretched.
            output = outputFactory.create(asset, 1f);
            long framesRead = 0;
            long framesWritten = 0;
            boolean started = false;
            while (isCurrent(expected)) {
                int count = music.read(buffer);
                if (count < 0) break;
                if (count % frameSize != 0) throw new IOException("Incomplete PCM frame");
                mixer.mix(buffer, count, target + framesRead, volume, hitVolume);
                framesRead += count / frameSize;
                int writtenBytes = 0;
                while (writtenBytes < count) {
                    int written;
                    synchronized (lock) {
                        if (!isCurrentLocked(expected)) return;
                        activeTrack = output;
                        written = output.write(buffer, writtenBytes, count - writtenBytes);
                        if (written < 0 || written % frameSize != 0) {
                            throw new IOException("PCM output write failed: " + written);
                        }
                        if (!started && written > 0) {
                            // No cancelled worker can play after a newer pause/seek/start.
                            output.play();
                            started = true;
                        }
                    }
                    writtenBytes += written;
                    framesWritten += written / frameSize;
                    synchronized (lock) {
                        if (!isCurrentLocked(expected)) return;
                        writtenOutputFrames = framesWritten;
                        observeClockLocked(output);
                    }
                    if (written == 0) Thread.sleep(2);
                }
            }
            // A seek within the final few milliseconds may leave less music than the
            // native stream's start threshold. Pad only its output queue so it can drain;
            // the chart duration and completion target remain the real music length.
            if (framesWritten > 0 && framesWritten < output.startThresholdFrames()) {
                byte[] silence = new byte[buffer.length];
                long queued = framesWritten;
                while (queued < output.startThresholdFrames()) {
                    int written;
                    synchronized (lock) {
                        if (!isCurrentLocked(expected)) return;
                        int length = (int) Math.min(silence.length,
                                (output.startThresholdFrames() - queued) * frameSize);
                        written = output.write(silence, 0, length);
                        if (written < 0 || written % frameSize != 0) {
                            throw new IOException("PCM tail write failed: " + written);
                        }
                    }
                    queued += written / frameSize;
                    if (written == 0) Thread.sleep(2);
                }
            }
            while (true) {
                synchronized (lock) {
                    if (!isCurrentLocked(expected)) return;
                    observeClockLocked(output);
                    if (output.headFrames() >= framesWritten) break;
                }
                Thread.sleep(5);
            }
            complete(expected);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            fail(expected, exception);
        } catch (OutOfMemoryError exception) {
            fail(expected, new IOException("Not enough memory for PCM hit-sound mixing", exception));
        } finally {
            synchronized (lock) {
                if (activeTrack == output) {
                    updatePositionLocked();
                    activeTrack = null;
                }
                if (worker == Thread.currentThread()) worker = null;
                if (output != null) output.release();
            }
        }
    }

    private boolean isCurrent(int expected) {
        synchronized (lock) { return isCurrentLocked(expected); }
    }

    private boolean isCurrentLocked(int expected) {
        return !released && playRequested && generation == expected;
    }

    private void complete(int expected) {
        synchronized (lock) {
            if (!isCurrentLocked(expected)) return;
            lastPositionMs = asset.durationMillis();
            lastPositionSeconds = asset.totalFrames / (double) asset.sampleRate;
            playRequested = false;
        }
        post.accept(() -> {
            synchronized (lock) {
                if (released || generation != expected) return;
                listener.onCompletion();
            }
        });
    }

    private void fail(int expected, Exception exception) {
        synchronized (lock) {
            if (released || generation != expected) return;
            playRequested = false;
        }
        String message = AudioErrorMessages.describe(exception) + " ("
                + asset.sampleRate + " Hz, " + asset.channelCount + " ch)";
        post.accept(() -> {
            synchronized (lock) {
                if (released || generation != expected) return;
                listener.onError(message);
            }
        });
    }

    private void observeClockLocked(Output output) {
        if (presentationClock == null) return;
        long now = System.nanoTime();
        presentationClock.observe(output.headFrames(), now, writtenOutputFrames);
        if (output.timestamp(timestamp)) presentationClock.timestamp(timestamp, System.nanoTime());
    }

    private void updatePositionLocked() {
        if (activeTrack == null || presentationClock == null) return;
        try {
            // Legacy millisecond callers also work with synchronous injected outputs.
            presentationClock.observe(activeTrack.headFrames(), System.nanoTime(), writtenOutputFrames);
            lastPositionSeconds = Math.max(lastPositionSeconds, presentationClock.seconds(System.nanoTime()));
            lastPositionMs = Math.round(lastPositionSeconds * 1000);
        } catch (IllegalStateException ignored) { }
    }

    private static Output createTrack(PcmAudioAsset asset, float speed) throws IOException {
        int channels = asset.channelCount == 1
                ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minimum = AudioTrack.getMinBufferSize(asset.sampleRate, channels, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IOException("Unsupported PCM output format");
        AudioTrack track = PcmOutputSetup.open(asset.sampleRate, asset.bytesPerFrame(), minimum, speed,
                new PcmOutputSetup.Backend<AudioTrack>() {
            public AudioTrack create(int bufferBytes, boolean lowLatency) throws IOException {
                AudioTrack result = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(asset.sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(channels).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(lowLatency ? AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                        : AudioTrack.PERFORMANCE_MODE_NONE)
                .setBufferSizeInBytes(bufferBytes)
                .build();
                if (result.getState() != AudioTrack.STATE_INITIALIZED) {
                    result.release();
                    throw new IOException("PCM output unavailable");
                }
                return result;
            }
            public void setSpeed(AudioTrack track, float rate) {
                track.setPlaybackParams(new PlaybackParams().allowDefaults().setSpeed(rate).setPitch(1f));
            }
            public void prepare(AudioTrack track) {
            // setPlaybackParams may implicitly start playback. Return a paused, empty output.
            track.pause();
            track.flush();
            track.setVolume(1f); // Music and effects have independent gain in the PCM mixer.
            }
            public void release(AudioTrack track) { track.release(); }
        });
        return new Output() {
            private final AudioTimestamp nativeTimestamp = new AudioTimestamp();
            public boolean timestamp(PcmPresentationClock.Timestamp value) {
                if (!track.getTimestamp(nativeTimestamp)) return false;
                value.framePosition = nativeTimestamp.framePosition; value.nanoTime = nativeTimestamp.nanoTime;
                return true;
            }
            public int write(byte[] pcm, int offset, int length) {
                return track.write(pcm, offset, length, AudioTrack.WRITE_NON_BLOCKING);
            }
            public void play() { track.play(); }
            public void pause() { track.pause(); }
            public void flush() { track.flush(); }
            public long headFrames() { return Integer.toUnsignedLong(track.getPlaybackHeadPosition()); }
            public int startThresholdFrames() { return track.getBufferSizeInFrames(); }
            public void release() {
                try { track.pause(); track.flush(); }
                catch (IllegalStateException ignored) { }
                track.release();
            }
        };
    }
}
