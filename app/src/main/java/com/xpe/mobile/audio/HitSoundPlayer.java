package com.xpe.mobile.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import com.xpe.mobile.R;
import java.io.IOException;
import java.util.Map;

/** One streaming track for all overlapping hit sounds, independent of note density. */
final class HitSoundPlayer implements AutoCloseable {
    interface Fallback { void play(HitSoundTimeline.Cue cue); }
    private final Object lock = new Object();
    private final Handler callbackHandler;
    private final Fallback fallback;
    private final HitSoundStreamMixer mixer;
    private final Thread thread;
    private final byte[] block = new byte[HitSoundStreamMixer.BLOCK_FRAMES * 4];
    private AudioTrack track;
    private float volume = 1f;
    private boolean closed;
    private boolean failed;
    private int generation;
    private int offset = block.length;

    HitSoundPlayer(Context context, Handler callbackHandler, Fallback fallback) throws IOException {
        this.callbackHandler = callbackHandler;
        this.fallback = fallback;
        mixer = new HitSoundStreamMixer(
                HitSoundPcmLoader.load(context, R.raw.hitsound_click_pcm),
                HitSoundPcmLoader.load(context, R.raw.hitsound_flick_pcm),
                HitSoundPcmLoader.load(context, R.raw.hitsound_drag_pcm));
        thread = new Thread(this::run, "phistudio-hitsound-stream");
        thread.setDaemon(true);
        thread.start();
    }

    void setVolume(float value) {
        synchronized (lock) {
            volume = Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0f;
            if (volume == 0f) stop();
        }
    }

    void setCustomSounds(Map<String,PcmHitSound> samples) {
        synchronized (lock) {
            if (closed) return;
            stop();
            mixer.setCustomSounds(samples);
        }
    }

    void play(HitSoundTimeline.Cue cue) {
        if (cue == null || cue.noteCount() == 0) return;
        synchronized (lock) {
            if (closed || volume <= 0) return;
            if (failed) {
                int submittedGeneration = generation;
                callbackHandler.post(() -> {
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
            generation++;
            mixer.clear();
            offset = block.length;
            if (track != null) {
                try { track.pause(); track.flush(); }
                catch (IllegalStateException ignored) { }
            }
            lock.notifyAll();
        }
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
                    while (!closed && offset == block.length && !mixer.hasAudio()) lock.wait();
                    if (closed) return;
                }
                // Native track creation happens once, outside the UI-facing lock.
                if (track == null) {
                    AudioTrack created = createTrack();
                    synchronized (lock) { track = created; }
                }
                synchronized (lock) {
                    if (closed) return;
                    if (offset == block.length) {
                        if (!mixer.hasAudio()) continue;
                        mixer.render(block, volume);
                        offset = 0;
                    }
                    if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
                    int written = track.write(block, offset, block.length - offset,
                            AudioTrack.WRITE_NON_BLOCKING);
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

    private static AudioTrack createTrack() throws IOException {
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
        return result;
    }

    static boolean canWriteStreamDataState(int state) {
        return state == AudioTrack.STATE_INITIALIZED;
    }
}
