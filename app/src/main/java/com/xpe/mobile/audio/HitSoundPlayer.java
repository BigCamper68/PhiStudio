package com.xpe.mobile.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import com.xpe.mobile.R;
import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;

/** One streaming track for all overlapping hit sounds, independent of note density. */
final class HitSoundPlayer implements AutoCloseable {
    interface Fallback { void play(HitSoundTimeline.Cue cue); }
    interface Output {
        boolean isPlaying();
        int writableFrames();
        void play();
        int write(byte[] data, int offset, int length);
        void pause();
        void flush();
        void release();
    }
    interface OutputFactory { Output create() throws IOException; }
    private final Object lock = new Object();
    private final Consumer<Runnable> post;
    private final OutputFactory outputFactory;
    private final Fallback fallback;
    private final HitSoundStreamMixer mixer;
    private final Thread thread;
    private final byte[] block = new byte[HitSoundStreamMixer.BLOCK_FRAMES * 4];
    private Output track;
    private float volume = 1f;
    private boolean closed;
    private boolean failed;
    private boolean keepAlive;
    private int generation;
    private int offset = block.length;

    HitSoundPlayer(Context context, Handler callbackHandler, Fallback fallback) throws IOException {
        this(new HitSoundStreamMixer(
                HitSoundPcmLoader.load(context, R.raw.hitsound_click_pcm),
                HitSoundPcmLoader.load(context, R.raw.hitsound_flick_pcm),
                HitSoundPcmLoader.load(context, R.raw.hitsound_drag_pcm)),
                callbackHandler::post, fallback, HitSoundPlayer::createTrack);
    }

    HitSoundPlayer(HitSoundStreamMixer mixer, Consumer<Runnable> post, Fallback fallback, OutputFactory factory) {
        this.mixer = mixer;
        this.post = post;
        this.fallback = fallback;
        outputFactory = factory;
        thread = new Thread(this::run, "phistudio-hitsound-stream");
        thread.setDaemon(true);
        thread.start();
    }

    /** Prime before playtest starts and keep a continuous PCM clock through silent gaps. */
    void prepare() {
        synchronized (lock) {
            if (closed || failed) return;
            keepAlive = true;
            lock.notifyAll();
        }
    }

    void setVolume(float value) {
        synchronized (lock) {
            volume = Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0f;
            if (volume == 0f) resetLocked();
        }
    }

    void setCustomSounds(Map<String,PcmHitSound> samples) {
        synchronized (lock) {
            if (closed) return;
            resetLocked();
            mixer.setCustomSounds(samples);
        }
    }

    void play(HitSoundTimeline.Cue cue) {
        if (cue == null || cue.noteCount() == 0) return;
        synchronized (lock) {
            if (closed || volume <= 0) return;
            if (failed) {
                int submittedGeneration = generation;
                post.accept(() -> {
                    synchronized (lock) {
                        if (!closed && generation == submittedGeneration) fallback.play(cue);
                    }
                });
            } else {
                mixer.add(cue);
                lock.notifyAll();
            }
        }
    }

    void stop() {
        synchronized (lock) {
            keepAlive = false;
            resetLocked();
        }
    }

    private void resetLocked() {
        generation++; mixer.clear(); offset = block.length;
        if (track != null) {
            try { track.pause(); track.flush(); }
            catch (IllegalStateException ignored) { }
        }
        lock.notifyAll();
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            stop();
            closed = true;
            lock.notifyAll();
        }
        thread.interrupt();
    }

    private void run() {
        try {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO); }
            catch (RuntimeException ignored) { }
            while (true) {
                synchronized (lock) {
                    while (!closed && !keepAlive && offset == block.length && !mixer.hasAudio()) lock.wait();
                    if (closed) return;
                }
                // Native track creation happens once, outside the UI-facing lock.
                if (track == null) {
                    Output created = outputFactory.create();
                    synchronized (lock) { track = created; }
                }
                synchronized (lock) {
                    if (closed) return;
                    if (offset == block.length) {
                        if (!keepAlive && !mixer.hasAudio()) continue;
                        // Mix only when the native queue can accept a block. Touches
                        // arriving during backpressure still belong in that next block.
                        if (track.writableFrames() < HitSoundStreamMixer.BLOCK_FRAMES) {
                            lock.wait(2L);
                            continue;
                        }
                        mixer.render(block, volume);
                        offset = 0;
                    }
                    if (!track.isPlaying()) track.play();
                    int written = track.write(block, offset, block.length - offset);
                    if (written < 0) throw new IOException("Hit-sound stream write failed: " + written);
                    offset += written;
                    // Bounded native buffer provides backpressure; never queue work per note.
                    if (written == 0) lock.wait(2L);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | IOException exception) {
            synchronized (lock) { failed = true; mixer.clear(); }
        } finally {
            synchronized (lock) {
                if (track != null) {
                    try { track.pause(); track.flush(); }
                    catch (IllegalStateException ignored) { }
                    track.release();
                    track = null;
                }
            }
        }
    }

    private static Output createTrack() throws IOException {
        int minimum = AudioTrack.getMinBufferSize(HitSoundStreamMixer.RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack result = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(HitSoundStreamMixer.RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(Math.max(minimum, HitSoundStreamMixer.BLOCK_FRAMES * 4 * 4))
                .build();
        if (!canWriteStreamDataState(result.getState())) {
            result.release();
            throw new IOException("Unable to initialize hit-sound stream");
        }
        // Use two blocks on the fast output path. Other routes retain their advertised
        // minimum so a slow system mixer can consume a complete period without underrun.
        int requested = HitSoundStreamMixer.BLOCK_FRAMES * 2;
        if (result.getPerformanceMode() != AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            requested = Math.max(requested, Math.max(0, minimum) / 4);
        try { result.setBufferSizeInFrames(requested); }
        catch (RuntimeException exception) { result.release(); throw exception; }
        return new Output() {
            private long writtenFrames, headWrap, lastHead;
            public boolean isPlaying() { return result.getPlayState() == AudioTrack.PLAYSTATE_PLAYING; }
            public int writableFrames() {
                long head = Integer.toUnsignedLong(result.getPlaybackHeadPosition());
                if (head < lastHead) headWrap += 1L << 32;
                lastHead = head;
                long queued = Math.max(0, writtenFrames - headWrap - head);
                return (int) Math.max(0, result.getBufferSizeInFrames() - queued);
            }
            public void play() { result.play(); }
            public int write(byte[] data, int offset, int length) {
                int written = result.write(data, offset, length, AudioTrack.WRITE_NON_BLOCKING);
                if (written > 0) writtenFrames += written / 4;
                return written;
            }
            public void pause() { result.pause(); }
            public void flush() { result.flush(); writtenFrames = headWrap = lastHead = 0; }
            public void release() { result.release(); }
        };
    }

    static boolean canWriteStreamDataState(int state) {
        return state == AudioTrack.STATE_INITIALIZED;
    }
}
