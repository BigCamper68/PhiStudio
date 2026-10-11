package com.xpe.mobile.audio;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Handler;
import com.xpe.mobile.R;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.model.NoteType;
import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.packageio.PackageHitSoundCatalog;
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

/** One sample-addressed PCM transport for every supported music format and scheduled hits. */
public final class EditorAudioController implements AutoCloseable {
    public interface Listener {
        void onAudioStateChanged();
        void onAudioCompleted();
        void showMessage(String message);
        boolean isHostUnavailable();
    }
    private final Activity activity;
    private final Handler mainHandler;
    private final Listener listener;
    private final ExecutorService decodeExecutor;
    private final Map<NoteType, Integer> hitSoundIds = new EnumMap<>(NoteType.class);
    private final Set<Integer> loadedHitSounds = Collections.synchronizedSet(new LinkedHashSet<>());
    private final HitSoundPlayer hitSoundPlayer;
    private final PcmHitSound[] scheduledSamples;
    private final CustomHitSoundRepository customHitSounds;
    private final HitSoundPlaybackGate hitSoundStartGate = new HitSoundPlaybackGate();
    private final Map<String,Integer> customPoolIds = new java.util.HashMap<>();
    private ChartPackage hitSoundPackage;
    private SoundPool hitSoundPool;
    private PcmAudioPlayer pcmAudioPlayer;
    private Future<?> audioDecodeTask;
    private File decodedPcmFile;
    private EditorSettings settings = new EditorSettings();
    private HitSoundTimeline timeline = HitSoundTimeline.empty();
    private long audioOffsetMs;
    private boolean closed;
    private int audioLoadGeneration;

    public EditorAudioController(Activity activity, Handler mainHandler, Listener listener) {
        if (activity == null || mainHandler == null || listener == null) {
            throw new IllegalArgumentException("Activity, handler and listener are required");
        }
        this.activity = activity;
        this.mainHandler = mainHandler;
        this.listener = listener;
        decodeExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "phistudio-audio-decode");
            thread.setDaemon(true);
            return thread;
        });
        initializeHitSounds();
        HitSoundPlayer player;
        try { player = new HitSoundPlayer(activity, mainHandler, this::playHitSoundFallback); }
        catch (IOException | RuntimeException exception) { player = null; }
        hitSoundPlayer = player;
        PcmHitSound[] samples;
        try {
            samples = new PcmHitSound[NoteType.values().length];
            samples[NoteType.TAP.ordinal()] = HitSoundPcmLoader.load(activity, R.raw.hitsound_click_pcm);
            samples[NoteType.HOLD.ordinal()] = samples[NoteType.TAP.ordinal()];
            samples[NoteType.FLICK.ordinal()] = HitSoundPcmLoader.load(activity, R.raw.hitsound_flick_pcm);
            samples[NoteType.DRAG.ordinal()] = HitSoundPcmLoader.load(activity, R.raw.hitsound_drag_pcm);
        } catch (IOException | RuntimeException exception) { samples = null; }
        scheduledSamples = samples;
        customHitSounds = new CustomHitSoundRepository(decodeExecutor,
                callback -> mainHandler.post(callback),
                file -> CustomHitSoundLoader.load(activity, file), this::customHitSoundsChanged);
    }

    public void applySettings(EditorSettings value) {
        settings = value == null ? new EditorSettings() : value.copy();
        if (pcmAudioPlayer != null) {
            pcmAudioPlayer.setVolume((float) settings.musicVolume);
            pcmAudioPlayer.setHitVolume((float) settings.soundEffectVolume);
        }
        if (hitSoundPlayer != null) hitSoundPlayer.setVolume((float) settings.soundEffectVolume);
    }

    public boolean isReady() { return !closed && pcmAudioPlayer != null; }
    public boolean isPlaying() { return isReady() && (hitSoundStartGate.isWaiting() || pcmAudioPlayer.isPlaying()); }
    public boolean schedulesHitSounds() { return isReady() && scheduledSamples != null; }

    public void configureHitSounds(HitSoundTimeline schedule, long chartOffsetMs, long packageOffsetMs) {
        timeline = schedule == null ? HitSoundTimeline.empty() : schedule;
        audioOffsetMs = ScheduledHitSoundMixer.addSaturated(chartOffsetMs, packageOffsetMs);
        customHitSounds.ensure(timeline.customPaths());
        if (pcmAudioPlayer != null) {
            pcmAudioPlayer.configureHitSounds(timeline, audioOffsetMs, scheduledSamples, customHitSounds.samples());
        }
    }

    public void setHitSoundPackage(ChartPackage pack) {
        pause();
        hitSoundPackage = pack;
        timeline = HitSoundTimeline.empty();
        for (Integer id : customPoolIds.values()) {
            loadedHitSounds.remove(id);
            if (hitSoundPool != null) hitSoundPool.unload(id);
        }
        customPoolIds.clear();
        customHitSounds.setPackage(pack);
        if (hitSoundPlayer != null) hitSoundPlayer.setCustomSounds(Collections.emptyMap());
        customHitSounds.ensure(PackageHitSoundCatalog.referencedPaths(pack));
    }

    private void customHitSoundsChanged(java.util.List<String> failures) {
        if (closed) return;
        if (!failures.isEmpty()) listener.showMessage("Custom hit sounds unavailable; using note defaults: "
                + String.join(", ", failures.subList(0, Math.min(3, failures.size()))));
        if (pcmAudioPlayer != null) {
            pcmAudioPlayer.configureHitSounds(timeline, audioOffsetMs, scheduledSamples, customHitSounds.samples());
        }
        if (hitSoundPlayer != null) {
            try { hitSoundPlayer.setCustomSounds(customHitSounds.samples()); }
            catch (OutOfMemoryError exception) {
                hitSoundPlayer.setCustomSounds(Collections.emptyMap());
                listener.showMessage("Not enough memory for standalone custom hit sounds; using note defaults");
            }
        }
        for (String path : customHitSounds.samples().keySet()) {
            if (hitSoundPool == null || customPoolIds.containsKey(path)) continue;
            try {
                File file = PackageHitSoundCatalog.resolve(hitSoundPackage, path);
                customPoolIds.put(path, hitSoundPool.load(file.getAbsolutePath(), 1));
            } catch (IOException | RuntimeException ignored) { }
        }
        HitSoundPlaybackGate.Request pending = hitSoundStartGate.takeIfReady(
                isReady() && !customHitSounds.isLoading());
        if (pending != null) start(pending.positionMs, pending.speed);
        notifyAudioStateChanged();
    }

    public void playHitSound(HitSoundTimeline.Cue cue) {
        // Scheduled music playback owns hits. UI-triggered sounds are only the no-music fallback.
        if (closed || cue == null || cue.noteCount() == 0 || schedulesHitSounds()) return;
        if (hitSoundPlayer != null) hitSoundPlayer.play(cue);
        else playHitSoundFallback(cue);
    }

    public void start(long positionMs, float speed) {
        if (hitSoundPlayer != null) hitSoundPlayer.stop();
        if (isReady()) {
            if (hitSoundStartGate.request(positionMs, speed, customHitSounds.isLoading())) {
                pcmAudioPlayer.start(positionMs, speed);
            } else {
                pcmAudioPlayer.pause();
                listener.showMessage("Preparing custom hit sounds…");
            }
            notifyAudioStateChanged();
        }
    }

    public void pause() {
        hitSoundStartGate.cancel();
        if (hitSoundPlayer != null) hitSoundPlayer.stop();
        if (pcmAudioPlayer != null) pcmAudioPlayer.pause();
    }

    public void seek(long positionMs) {
        hitSoundStartGate.cancel();
        if (hitSoundPlayer != null) hitSoundPlayer.stop();
        if (isReady()) {
            pcmAudioPlayer.seek(positionMs);
            notifyAudioStateChanged();
        }
    }

    public long positionMillis() {
        return hitSoundStartGate.isWaiting() ? hitSoundStartGate.positionMillis()
                : isReady() ? pcmAudioPlayer.positionMillis() : 0;
    }
    public long durationMillis() { return isReady() ? pcmAudioPlayer.durationMillis() : 0; }

    public void load(Uri uri) throws IOException {
        if (closed) throw new IOException("Audio controller is closed");
        if (uri == null) throw new IOException("Audio source is missing");
        clearSource();
        final int generation = ++audioLoadGeneration;
        File output = File.createTempFile("phistudio-audio-", ".pcm", activity.getCacheDir());
        decodedPcmFile = output;
        listener.showMessage("Preparing audio…");
        try {
            audioDecodeTask = decodeExecutor.submit(() -> {
                try {
                    PcmAudioAsset asset = AudioPcmDecoder.decode(activity, uri, output);
                    mainHandler.post(() -> {
                        if (!isCurrentLoad(generation)) { asset.file.delete(); return; }
                        audioDecodeTask = null;
                        decodedPcmFile = asset.file;
                        pcmAudioPlayer = new PcmAudioPlayer(asset, mainHandler, new PcmAudioPlayer.Listener() {
                            public void onCompletion() {
                                if (isCurrentLoad(generation)) listener.onAudioCompleted();
                            }
                            public void onError(String message) {
                                if (!isCurrentLoad(generation)) return;
                                listener.onAudioCompleted();
                                listener.showMessage("PCM playback error: " + message);
                            }
                        });
                        pcmAudioPlayer.configureHitSounds(timeline, audioOffsetMs, scheduledSamples,
                                customHitSounds.samples());
                        applySettings(settings);
                        notifyAudioStateChanged();
                        listener.showMessage("Audio loaded");
                    });
                } catch (Exception exception) {
                    output.delete();
                    mainHandler.post(() -> {
                        if (!isCurrentLoad(generation)) return;
                        audioDecodeTask = null;
                        if (decodedPcmFile == output) decodedPcmFile = null;
                        notifyAudioStateChanged();
                        listener.showMessage("Audio decoder error: " + safeMessage(exception));
                    });
                }
            });
        } catch (RuntimeException exception) {
            output.delete();
            decodedPcmFile = null;
            throw new IOException("Unable to start audio decoder", exception);
        }
    }

    public void clearSource() {
        hitSoundStartGate.cancel();
        audioLoadGeneration++;
        if (hitSoundPlayer != null) hitSoundPlayer.stop();
        if (audioDecodeTask != null) {
            audioDecodeTask.cancel(true);
            audioDecodeTask = null;
        }
        if (pcmAudioPlayer != null) {
            pcmAudioPlayer.release();
            pcmAudioPlayer = null;
        }
        if (decodedPcmFile != null) {
            decodedPcmFile.delete();
            decodedPcmFile = null;
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        customHitSounds.close();
        clearSource();
        decodeExecutor.shutdownNow();
        if (hitSoundPlayer != null) hitSoundPlayer.close();
        releaseHitSounds();
    }

    private boolean isCurrentLoad(int generation) {
        return !closed && generation == audioLoadGeneration && !listener.isHostUnavailable();
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
        for (HitSoundTimeline.CustomSound custom : cue.customSounds()) {
            Integer id = customPoolIds.get(custom.path());
            if (id != null && loadedHitSounds.contains(id)) {
                playFallbackSample(pool, id, custom.count(), volume);
            } else for (NoteType type : NoteType.values()) {
                playFallbackSample(pool, hitSoundIds.get(type), custom.fallbackCount(type), volume);
            }
        }
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
