package com.xpe.mobile.audio;

import android.content.Context;
import com.xpe.mobile.R;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.NoteType;
import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.packageio.PackageHitSoundCatalog;
import com.xpe.mobile.video.VideoExportOptions;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Offline consumer of the same sample-accurate mixer used by preview playback. */
public final class ExportAudioMixer implements AutoCloseable {
    public final int sampleRate, channels;
    public final long totalFrames;
    private final RandomAccessFile music;
    private final ScheduledHitSoundMixer mixer;
    private final VideoExportOptions options;
    private final long firstFrame;
    private long emittedFrames;

    public ExportAudioMixer(Context context, PcmAudioAsset decoded, ChartDocument chart,
                            ChartPackage pack, long packageOffsetMs, VideoExportOptions options, EditorSettings settings)
            throws IOException {
        this.options = options;
        sampleRate = decoded == null ? 48_000 : decoded.sampleRate;
        channels = decoded == null ? 2 : decoded.channelCount;
        firstFrame = Math.round(options.startMs * (double) sampleRate / 1000);
        totalFrames = (options.durationUs() * sampleRate + 999_999) / 1_000_000;
        PcmAudioAsset format = decoded == null
                ? new PcmAudioAsset(new File(context.getCacheDir(), "unused.pcm"), sampleRate, channels, 0)
                : decoded;
        HitSoundTimeline timeline = options.hits ? HitSoundTimeline.build(chart,
                settings.tapFlickHitsoundOffsetMs, settings.dragHitsoundOffsetMs) : HitSoundTimeline.empty();
        PcmHitSound[] sounds = new PcmHitSound[NoteType.values().length];
        Map<String, PcmHitSound> custom = new HashMap<>();
        long maxTail = 0;
        if (options.hits) {
            sounds[NoteType.TAP.ordinal()] = HitSoundPcmLoader.load(context, R.raw.hitsound_click_pcm);
            sounds[NoteType.HOLD.ordinal()] = sounds[NoteType.TAP.ordinal()];
            sounds[NoteType.FLICK.ordinal()] = HitSoundPcmLoader.load(context, R.raw.hitsound_flick_pcm);
            sounds[NoteType.DRAG.ordinal()] = HitSoundPcmLoader.load(context, R.raw.hitsound_drag_pcm);
            if (timeline.customPaths().size() > CustomHitSoundRepository.MAX_FILES)
                throw new IOException("Too many custom hit sounds (maximum 32)");
            long cacheBytes = 0;
            for (String path : timeline.customPaths()) {
                checkCancelled();
                PcmHitSound sound;
                try { sound = CustomHitSoundLoader.load(context, PackageHitSoundCatalog.resolve(pack, path)); }
                catch (IOException exception) { throw new IOException("Hit sound " + path + ": " + exception.getMessage(), exception); }
                cacheBytes += (long) sound.frameCount() * sound.channelCount * 2;
                if (cacheBytes > CustomHitSoundRepository.MAX_CACHE_BYTES)
                    throw new IOException("Custom hit sounds exceed 32 MiB");
                custom.put(path, sound);
            }
            for (PcmHitSound sound : sounds) if (sound != null)
                maxTail = Math.max(maxTail, (long) Math.ceil(sound.frameCount() * (double) sampleRate / sound.sampleRate));
            for (PcmHitSound sound : custom.values())
                maxTail = Math.max(maxTail, (long) Math.ceil(sound.frameCount() * (double) sampleRate / sound.sampleRate));
        }
        long warmStart = firstFrame - maxTail;
        mixer = new ScheduledHitSoundMixer(format, timeline,
                (long) chart.offsetMs + packageOffsetMs, sounds, custom, warmStart, 1f);
        byte[] warmup = new byte[4096 * channels * 2];
        for (long frame = warmStart; frame < firstFrame;) {
            checkCancelled();
            int frames = (int) Math.min(4096, firstFrame - frame);
            Arrays.fill(warmup, (byte) 0);
            mixer.mix(warmup, frames * channels * 2, frame, 0, options.hitVolume);
            frame += frames;
        }
        music = options.music && decoded != null ? new RandomAccessFile(decoded.file, "r") : null;
        if (music != null) {
            try { music.seek(Math.max(0, firstFrame) * channels * 2); }
            catch (IOException exception) { music.close(); throw exception; }
        }
    }

    /** Fills complete PCM frames, padding with silence after the song ends. */
    public int read(byte[] output, int maxBytes) throws IOException {
        checkCancelled();
        int frameSize = channels * 2;
        int frames = (int) Math.min(totalFrames - emittedFrames, Math.min(output.length, maxBytes) / frameSize);
        int bytes = frames * frameSize;
        Arrays.fill(output, 0, bytes, (byte) 0);
        if (music != null) {
            int read = (int) Math.min(frames, Math.max(0, -(firstFrame + emittedFrames))) * frameSize;
            while (read < bytes) {
                int count = music.read(output, read, bytes - read);
                if (count < 0) break;
                read += count;
            }
        }
        mixer.mix(output, bytes, firstFrame + emittedFrames,
                options.music ? options.musicVolume : 0, options.hits ? options.hitVolume : 0);
        emittedFrames += frames;
        return bytes;
    }

    public long emittedFrames() { return emittedFrames; }
    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Export cancelled");
    }
    @Override public void close() throws IOException { if (music != null) music.close(); }
}
