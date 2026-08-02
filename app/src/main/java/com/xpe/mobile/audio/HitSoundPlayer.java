package com.xpe.mobile.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;

import com.xpe.mobile.R;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Plays each cue as one pre-mixed static AudioTrack. */
final class HitSoundPlayer implements AutoCloseable {
    interface Fallback {
        void play(HitSoundTimeline.Cue cue);
    }

    private static final long RELEASE_GRACE_MS = 250L;

    private final Object lock = new Object();
    private final Handler callbackHandler;
    private final Fallback fallback;
    private final ExecutorService audioExecutor;
    private final Set<AudioTrack> activeTracks = new HashSet<>();
    private final HitSoundPcmMixer mixer;

    private float volume = 1.0f;
    private boolean closed;

    HitSoundPlayer(Context context, Handler callbackHandler, Fallback fallback)
            throws IOException {
        if (context == null || callbackHandler == null || fallback == null) {
            throw new IllegalArgumentException("Context, handler and fallback are required");
        }
        this.callbackHandler = callbackHandler;
        this.fallback = fallback;

        PcmHitSound click = HitSoundPcmLoader.load(context, R.raw.hitsound_click_pcm);
        PcmHitSound flick = HitSoundPcmLoader.load(context, R.raw.hitsound_flick_pcm);
        PcmHitSound drag = HitSoundPcmLoader.load(context, R.raw.hitsound_drag_pcm);
        mixer = HitSoundPcmMixer.forBuiltInSamples(click, flick, drag);

        audioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "phistudio-hitsound-mixer");
            thread.setDaemon(true);
            return thread;
        });
    }

    void setVolume(float value) {
        synchronized (lock) {
            volume = Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0f;
        }
    }

    void play(HitSoundTimeline.Cue cue) {
        if (cue == null || cue.noteCount() == 0) return;
        try {
            audioExecutor.execute(() -> playOnAudioThread(cue));
        } catch (RejectedExecutionException ignored) {
            // Closing the editor deliberately rejects late UI-frame callbacks.
        }
    }

    @Override
    public void close() {
        List<AudioTrack> tracks;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            tracks = new ArrayList<>(activeTracks);
            activeTracks.clear();
        }
        audioExecutor.shutdownNow();
        for (AudioTrack track : tracks) releaseTrack(track);
    }

    private void playOnAudioThread(HitSoundTimeline.Cue cue) {
        float currentVolume;
        synchronized (lock) {
            if (closed || volume <= 0f) return;
            currentVolume = volume;
        }

        byte[] pcm;
        try {
            pcm = mixer.mix(cue, currentVolume);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            postFallback(cue);
            return;
        }
        if (pcm.length == 0) return;

        AudioTrack track = null;
        try {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            } catch (RuntimeException ignored) {
                // Playback remains correct if an OEM refuses the priority hint.
            }
            track = createTrack(pcm.length);
            writeFully(track, pcm);
            synchronized (lock) {
                if (closed) {
                    releaseTrack(track);
                    return;
                }
                activeTracks.add(track);
                track.play();
            }
            AudioTrack completedTrack = track;
            callbackHandler.postDelayed(() -> finishTrack(completedTrack),
                    playbackDurationMs(pcm.length) + RELEASE_GRACE_MS);
        } catch (IllegalArgumentException | IllegalStateException
                 | UnsupportedOperationException | IOException exception) {
            if (track != null) {
                synchronized (lock) {
                    activeTracks.remove(track);
                }
                releaseTrack(track);
            }
            postFallback(cue);
        }
    }

    private static AudioTrack createTrack(int bufferSize) throws IOException {
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(HitSoundPcmMixer.OUTPUT_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bufferSize)
                .build();
        if (!canWriteStaticDataState(track.getState())) {
            track.release();
            throw new IOException("Unable to initialize hit-sound AudioTrack");
        }
        return track;
    }

    static boolean canWriteStaticDataState(int state) {
        return state == AudioTrack.STATE_INITIALIZED
                || state == AudioTrack.STATE_NO_STATIC_DATA;
    }

    private static void writeFully(AudioTrack track, byte[] pcm) throws IOException {
        int offset = 0;
        while (offset < pcm.length) {
            int written = track.write(pcm, offset, pcm.length - offset,
                    AudioTrack.WRITE_BLOCKING);
            if (written <= 0) {
                throw new IOException("Hit-sound AudioTrack write failed: " + written);
            }
            offset += written;
        }
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            throw new IOException("Hit-sound AudioTrack did not accept static PCM data");
        }
    }

    private static long playbackDurationMs(int pcmBytes) {
        long frameCount = pcmBytes / HitSoundPcmMixer.OUTPUT_BYTES_PER_FRAME;
        return Math.max(1L, (frameCount * 1_000L
                + HitSoundPcmMixer.OUTPUT_SAMPLE_RATE - 1L)
                / HitSoundPcmMixer.OUTPUT_SAMPLE_RATE);
    }

    private void finishTrack(AudioTrack track) {
        boolean owned;
        synchronized (lock) {
            owned = activeTracks.remove(track);
        }
        if (owned) releaseTrack(track);
    }

    private void postFallback(HitSoundTimeline.Cue cue) {
        synchronized (lock) {
            if (closed) return;
        }
        callbackHandler.post(() -> fallback.play(cue));
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
