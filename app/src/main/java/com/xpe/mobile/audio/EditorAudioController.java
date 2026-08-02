package com.xpe.mobile.audio;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.MediaTimestamp;
import android.media.PlaybackParams;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;

import com.xpe.mobile.R;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.model.NoteType;
import com.xpe.mobile.packageio.AndroidPackageIo;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Owns editor music playback, MP3 decoding and note hit sounds.
 *
 * <p>The controller deliberately uses an executor that is independent from project-library I/O.
 * Decoding a long MP3 therefore cannot block project open, import, recovery or duplication.
 */
public final class EditorAudioController implements AutoCloseable {
    public interface Listener {
        void onAudioStateChanged();

        void onAudioCompleted();

        void showMessage(String message);

        boolean isHostUnavailable();
    }

    private static final long AUDIO_SEEK_RETRY_DELAY_MS = 40L;

    private final Activity activity;
    private final Handler mainHandler;
    private final Listener listener;
    private final ExecutorService decodeExecutor;
    private final PlaybackPositionTracker playbackPositionTracker =
            new PlaybackPositionTracker();
    private final PlaybackSeekCoordinator playbackSeekCoordinator =
            new PlaybackSeekCoordinator();
    private final Map<NoteType, Integer> hitSoundIds = new EnumMap<>(NoteType.class);
    private final Set<Integer> loadedHitSounds =
            Collections.synchronizedSet(new LinkedHashSet<>());
    private final HitSoundPlayer hitSoundPlayer;

    private MediaPlayer mediaPlayer;
    private SoundPool hitSoundPool;
    private PcmAudioPlayer pcmAudioPlayer;
    private Future<?> audioDecodeTask;
    private File decodedMp3PcmFile;
    private EditorSettings settings = new EditorSettings();
    private boolean audioPrepared;
    private boolean audioStartPending;
    private boolean audioSeekPending;
    private boolean currentAudioIsMp3;
    private boolean closed;
    private int audioCommandGeneration;
    private int audioLoadGeneration;
    private long pendingAudioPositionMs;

    public EditorAudioController(Activity activity, Handler mainHandler, Listener listener) {
        if (activity == null || mainHandler == null || listener == null) {
            throw new IllegalArgumentException("Activity, handler and listener are required");
        }
        this.activity = activity;
        this.mainHandler = mainHandler;
        this.listener = listener;
        decodeExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "phistudio-mp3-decode");
            thread.setDaemon(true);
            return thread;
        });
        initializeHitSounds();
        HitSoundPlayer player;
        try {
            player = new HitSoundPlayer(activity, mainHandler, this::playHitSoundFallback);
        } catch (IOException | RuntimeException ignored) {
            // SoundPool below remains available if bundled PCM cannot be opened on an OEM build.
            player = null;
        }
        hitSoundPlayer = player;
    }

    public void applySettings(EditorSettings value) {
        settings = value == null ? new EditorSettings() : value.copy();
        applyMusicVolume();
        if (hitSoundPlayer != null) {
            hitSoundPlayer.setVolume((float) settings.soundEffectVolume);
        }
    }

    public boolean isReady() {
        if (closed) return false;
        if (currentAudioIsMp3) return pcmAudioPlayer != null && audioPrepared;
        return mediaPlayer != null && audioPrepared;
    }

    public boolean isPlaying() {
        if (currentAudioIsMp3) {
            return isReady() && pcmAudioPlayer.isPlaying();
        }
        try {
            return isReady() && (audioStartPending || mediaPlayer.isPlaying());
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    public void playHitSound(HitSoundTimeline.Cue cue) {
        if (closed || cue == null || cue.noteCount() == 0) return;
        if (hitSoundPlayer != null) hitSoundPlayer.play(cue);
        else playHitSoundFallback(cue);
    }

    public void start(long positionMs, float speed) {
        if (!isReady()) return;
        if (currentAudioIsMp3) {
            pcmAudioPlayer.start(positionMs, speed);
            notifyAudioStateChanged();
            return;
        }
        try {
            int duration = mediaPlayer.getDuration();
            int target = (int) Math.max(0L, Math.min(positionMs, Math.max(0, duration - 1)));
            int generation = ++audioCommandGeneration;
            float targetSpeed = Math.max(0.25f, Math.min(2.0f, speed));
            audioStartPending = true;
            audioSeekPending = false;
            pendingAudioPositionMs = target;
            if (mediaPlayer.isPlaying()) mediaPlayer.pause();
            playbackSeekCoordinator.begin();
            mediaPlayer.setOnSeekCompleteListener(player -> {
                if (player != mediaPlayer || generation != audioCommandGeneration) return;
                try {
                    long reportedPosition = player.getCurrentPosition();
                    if (playbackSeekCoordinator.onSeekComplete(target, reportedPosition)
                            == PlaybackSeekCoordinator.Action.RETRY) {
                        // Some OEM decoders publish a stale compressed-audio position once.
                        mainHandler.postDelayed(() -> {
                            if (player != mediaPlayer
                                    || generation != audioCommandGeneration
                                    || !audioStartPending) return;
                            try {
                                player.seekTo(target, MediaPlayer.SEEK_CLOSEST);
                            } catch (IllegalArgumentException | IllegalStateException exception) {
                                failPendingAudioStart(player);
                            }
                        }, AUDIO_SEEK_RETRY_DELAY_MS);
                        return;
                    }
                    player.setOnSeekCompleteListener(null);
                    PlaybackParams params = player.getPlaybackParams();
                    params.setSpeed(targetSpeed);
                    params.setPitch(1.0f);
                    player.setPlaybackParams(params);
                    playbackPositionTracker.startAfterSeek(
                            target, targetSpeed, System.nanoTime());
                    audioStartPending = false;
                    playbackSeekCoordinator.reset();
                    notifyAudioStateChanged();
                } catch (IllegalArgumentException | IllegalStateException exception) {
                    failPendingAudioStart(player);
                }
            });
            mediaPlayer.seekTo(target, MediaPlayer.SEEK_CLOSEST);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            failPendingAudioStart(mediaPlayer);
        }
    }

    public void pause() {
        if (!isReady()) return;
        if (currentAudioIsMp3) {
            pcmAudioPlayer.pause();
            return;
        }
        try {
            audioCommandGeneration++;
            audioStartPending = false;
            audioSeekPending = false;
            playbackSeekCoordinator.reset();
            playbackPositionTracker.reset();
            mediaPlayer.setOnSeekCompleteListener(null);
            if (mediaPlayer.isPlaying()) mediaPlayer.pause();
        } catch (IllegalStateException ignored) {
            // The player will be recreated after another audio import.
        }
    }

    public void seek(long positionMs) {
        if (!isReady()) return;
        if (currentAudioIsMp3) {
            pcmAudioPlayer.seek(positionMs);
            notifyAudioStateChanged();
            return;
        }
        try {
            int duration = mediaPlayer.getDuration();
            int target = (int) Math.max(0L, Math.min(positionMs, Math.max(0, duration - 1)));
            int generation = ++audioCommandGeneration;
            audioStartPending = false;
            audioSeekPending = true;
            pendingAudioPositionMs = target;
            playbackSeekCoordinator.reset();
            playbackPositionTracker.reset();
            if (mediaPlayer.isPlaying()) mediaPlayer.pause();
            mediaPlayer.setOnSeekCompleteListener(player -> {
                if (player != mediaPlayer || generation != audioCommandGeneration) return;
                try {
                    player.setOnSeekCompleteListener(null);
                } catch (IllegalStateException ignored) {
                    // The player is already being recreated.
                }
                audioSeekPending = false;
                pendingAudioPositionMs = target;
                notifyAudioStateChanged();
            });
            mediaPlayer.seekTo(target, MediaPlayer.SEEK_CLOSEST);
            notifyAudioStateChanged();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            audioCommandGeneration++;
            audioSeekPending = false;
            playbackPositionTracker.reset();
            try {
                if (mediaPlayer != null) mediaPlayer.setOnSeekCompleteListener(null);
            } catch (IllegalStateException ignored) {
                // The player is already being recreated.
            }
            notifyAudioStateChanged();
            listener.showMessage(activity.getString(R.string.audio_player_not_ready));
        }
    }

    public long positionMillis() {
        if (!isReady()) return 0L;
        if (currentAudioIsMp3) return pcmAudioPlayer.positionMillis();
        try {
            long fallback = mediaPlayer.getCurrentPosition();
            MediaTimestamp timestamp = mediaPlayer.getTimestamp();
            long timestampMediaUs = timestamp == null ? -1L : timestamp.getAnchorMediaTimeUs();
            long timestampSystemNs = timestamp == null ? -1L
                    : Build.VERSION.SDK_INT >= 29
                    ? timestamp.getAnchorSystemNanoTime()
                    : timestamp.getAnchorSytemNanoTime();
            float timestampRate = timestamp == null ? -1f : timestamp.getMediaClockRate();
            long nowSystemNs = System.nanoTime();
            if (audioStartPending || audioSeekPending) return pendingAudioPositionMs;
            long position = playbackPositionTracker.positionMillis(
                    timestampMediaUs, timestampSystemNs, timestampRate,
                    nowSystemNs, fallback);
            return Math.min(position, Math.max(0, mediaPlayer.getDuration()));
        } catch (IllegalStateException ignored) {
            return audioStartPending || audioSeekPending ? pendingAudioPositionMs : 0L;
        }
    }

    public long durationMillis() {
        if (!isReady()) return 0L;
        if (currentAudioIsMp3) return pcmAudioPlayer.durationMillis();
        try {
            return mediaPlayer.getDuration();
        } catch (IllegalStateException ignored) {
            return 0L;
        }
    }

    public void load(Uri uri) throws IOException {
        if (closed) throw new IOException("Audio controller is closed");
        if (uri == null) throw new IOException("Audio source is missing");
        clearSource();
        currentAudioIsMp3 = isMp3Audio(uri);
        audioPrepared = false;
        try {
            if (currentAudioIsMp3) {
                decodeMp3Audio(uri);
            } else {
                prepareMediaPlayerAudio(uri);
            }
        } catch (IOException | RuntimeException exception) {
            clearSource();
            throw exception;
        }
    }

    public void clearSource() {
        audioLoadGeneration++;
        if (audioDecodeTask != null) {
            audioDecodeTask.cancel(true);
            audioDecodeTask = null;
        }
        if (pcmAudioPlayer != null) {
            pcmAudioPlayer.release();
            pcmAudioPlayer = null;
        }
        if (decodedMp3PcmFile != null) {
            decodedMp3PcmFile.delete();
            decodedMp3PcmFile = null;
        }
        audioCommandGeneration++;
        audioStartPending = false;
        audioSeekPending = false;
        playbackSeekCoordinator.reset();
        pendingAudioPositionMs = 0L;
        playbackPositionTracker.reset();
        audioPrepared = false;
        currentAudioIsMp3 = false;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.reset();
            } catch (IllegalStateException ignored) {
                // Release is still required below.
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        clearSource();
        decodeExecutor.shutdownNow();
        if (hitSoundPlayer != null) hitSoundPlayer.close();
        releaseHitSounds();
    }

    private void failPendingAudioStart(MediaPlayer player) {
        audioCommandGeneration++;
        audioStartPending = false;
        audioSeekPending = false;
        playbackSeekCoordinator.reset();
        playbackPositionTracker.reset();
        if (player != null) {
            try {
                player.setOnSeekCompleteListener(null);
                if (player.isPlaying()) player.pause();
            } catch (IllegalStateException ignored) {
                // The player is already being released or recreated.
            }
        }
        notifyAudioStateChanged();
        listener.showMessage(activity.getString(R.string.audio_player_not_ready));
    }

    private void prepareMediaPlayerAudio(Uri uri) throws IOException {
        MediaPlayer player = new MediaPlayer();
        mediaPlayer = player;
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build());
        player.setDataSource(activity, uri);
        player.setOnPreparedListener(prepared -> {
            if (prepared != mediaPlayer || closed) return;
            audioPrepared = true;
            applyMusicVolume();
            notifyAudioStateChanged();
            listener.showMessage("Audio loaded");
        });
        player.setOnCompletionListener(completed -> {
            if (completed != mediaPlayer || closed) return;
            audioCommandGeneration++;
            audioStartPending = false;
            audioSeekPending = false;
            playbackSeekCoordinator.reset();
            playbackPositionTracker.reset();
            listener.onAudioCompleted();
        });
        player.setOnErrorListener((failed, what, extra) -> {
            if (failed != mediaPlayer || closed) return true;
            audioCommandGeneration++;
            audioStartPending = false;
            audioSeekPending = false;
            playbackSeekCoordinator.reset();
            playbackPositionTracker.reset();
            audioPrepared = false;
            notifyAudioStateChanged();
            listener.showMessage("Audio decoder error");
            return true;
        });
        player.prepareAsync();
    }

    private void decodeMp3Audio(Uri uri) throws IOException {
        int generation = ++audioLoadGeneration;
        File output = new File(activity.getCacheDir(), "xpe-mp3-" + generation + ".pcm");
        decodedMp3PcmFile = output;
        listener.showMessage("Decoding MP3 audio…");
        try {
            audioDecodeTask = decodeExecutor.submit(() -> {
                PcmAudioAsset asset;
                try {
                    asset = Mp3PcmDecoder.decode(activity, uri, output);
                } catch (Exception exception) {
                    mainHandler.post(() -> {
                        if (!isCurrentLoad(generation)) return;
                        audioDecodeTask = null;
                        output.delete();
                        if (decodedMp3PcmFile == output) decodedMp3PcmFile = null;
                        audioPrepared = false;
                        notifyAudioStateChanged();
                        listener.showMessage("MP3 decoder error: " + safeMessage(exception));
                    });
                    return;
                }
                mainHandler.post(() -> {
                    if (!isCurrentLoad(generation)) {
                        asset.file.delete();
                        return;
                    }
                    audioDecodeTask = null;
                    decodedMp3PcmFile = asset.file;
                    pcmAudioPlayer = new PcmAudioPlayer(asset, mainHandler,
                            new PcmAudioPlayer.Listener() {
                                @Override
                                public void onCompletion() {
                                    if (isCurrentLoad(generation) && currentAudioIsMp3) {
                                        listener.onAudioCompleted();
                                    }
                                }

                                @Override
                                public void onError(String message) {
                                    if (!isCurrentLoad(generation) || !currentAudioIsMp3) return;
                                    listener.onAudioCompleted();
                                    listener.showMessage("PCM playback error: " + message);
                                }
                            });
                    applyMusicVolume();
                    audioPrepared = true;
                    notifyAudioStateChanged();
                    listener.showMessage("MP3 decoded and loaded");
                });
            });
        } catch (RuntimeException exception) {
            output.delete();
            decodedMp3PcmFile = null;
            throw new IOException("Unable to start MP3 decoder", exception);
        }
    }

    private boolean isCurrentLoad(int generation) {
        return !closed && generation == audioLoadGeneration && !listener.isHostUnavailable();
    }

    private boolean isMp3Audio(Uri uri) {
        String displayName = AndroidPackageIo.displayName(activity.getContentResolver(), uri);
        String mimeType = null;
        try {
            mimeType = activity.getContentResolver().getType(uri);
        } catch (RuntimeException ignored) {
            // File URIs and some document providers expose only the display name.
        }
        return AudioSourceFormat.isMp3(displayName, mimeType)
                || AudioSourceFormat.isMp3(uri.toString(), mimeType);
    }

    private void applyMusicVolume() {
        float volume = (float) Math.max(0.0, Math.min(1.0, settings.musicVolume));
        if (mediaPlayer != null) {
            try {
                mediaPlayer.setVolume(volume, volume);
            } catch (IllegalStateException ignored) {
                // The player may be transitioning between sources.
            }
        }
        if (pcmAudioPlayer != null) pcmAudioPlayer.setVolume(volume);
    }

    private void initializeHitSounds() {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        SoundPool pool = new SoundPool.Builder()
                .setMaxStreams(64)
                .setAudioAttributes(attributes)
                .build();
        pool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
            if (soundPool == hitSoundPool && status == 0) {
                loadedHitSounds.add(sampleId);
            }
        });
        hitSoundPool = pool;
        int clickSoundId = pool.load(activity, R.raw.hitsound_click, 1);
        hitSoundIds.put(NoteType.TAP, clickSoundId);
        hitSoundIds.put(NoteType.HOLD, clickSoundId);
        hitSoundIds.put(NoteType.FLICK, pool.load(activity, R.raw.hitsound_flick, 1));
        hitSoundIds.put(NoteType.DRAG, pool.load(activity, R.raw.hitsound_drag, 1));
    }

    private void playHitSoundFallback(HitSoundTimeline.Cue cue) {
        SoundPool pool = hitSoundPool;
        if (closed || pool == null || cue == null) return;
        float volume = (float) Math.max(0.0, Math.min(1.0, settings.soundEffectVolume));
        if (!Float.isFinite(volume) || volume <= 0f) return;

        // TAP and HOLD use one resource and therefore remain one native command even in the
        // compatibility path. The PCM player above is the normal path and retains exact gain.
        playFallbackSample(pool, hitSoundIds.get(NoteType.TAP),
                saturatingCountSum(cue.count(NoteType.TAP), cue.count(NoteType.HOLD)), volume);
        playFallbackSample(pool, hitSoundIds.get(NoteType.FLICK),
                cue.count(NoteType.FLICK), volume);
        playFallbackSample(pool, hitSoundIds.get(NoteType.DRAG),
                cue.count(NoteType.DRAG), volume);
    }

    private void playFallbackSample(SoundPool pool, Integer soundId,
                                    int count, float volume) {
        if (count <= 0 || soundId == null || soundId <= 0
                || !loadedHitSounds.contains(soundId)) return;
        float combinedVolume = (float) Math.min(1.0, volume * count);
        pool.play(soundId, combinedVolume, combinedVolume, 1, 0, 1f);
    }

    private static int saturatingCountSum(int first, int second) {
        return (int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, first) + Math.max(0L, second));
    }

    private void releaseHitSounds() {
        SoundPool pool = hitSoundPool;
        hitSoundPool = null;
        hitSoundIds.clear();
        loadedHitSounds.clear();
        if (pool != null) pool.release();
    }

    private void notifyAudioStateChanged() {
        if (!listener.isHostUnavailable()) listener.onAudioStateChanged();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }
}
