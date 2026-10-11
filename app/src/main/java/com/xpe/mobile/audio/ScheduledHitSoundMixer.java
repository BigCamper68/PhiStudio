package com.xpe.mobile.audio;

import com.xpe.mobile.model.NoteType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

/** Mixes music and hits into the SAME output frames. No UI clock or second output buffer. */
final class ScheduledHitSoundMixer {
    private static final NoteType[] TYPES = NoteType.values();
    private final int channels;
    private final PcmHitSound[] sounds;
    private final Map<String,PcmHitSound> customSounds = new HashMap<>();
    private final List<HitSoundTimeline.Cue> cues;
    private final long[] cueFrames;
    private final long[] ring;
    private long nextFrame;
    private int nextCue;
    private int cursor;

    ScheduledHitSoundMixer(PcmAudioAsset asset, HitSoundTimeline timeline,
                           long audioOffsetMs, PcmHitSound[] sourceSounds, long startFrame) {
        this(asset, timeline, audioOffsetMs, sourceSounds, startFrame, 1f);
    }

    ScheduledHitSoundMixer(PcmAudioAsset asset, HitSoundTimeline timeline,
                           long audioOffsetMs, PcmHitSound[] sourceSounds, long startFrame,
                           float speed) {
        this(asset, timeline, audioOffsetMs, sourceSounds, Collections.emptyMap(), startFrame, speed);
    }

    ScheduledHitSoundMixer(PcmAudioAsset asset, HitSoundTimeline timeline,
                           long audioOffsetMs, PcmHitSound[] sourceSounds,
                           Map<String,PcmHitSound> customSamples, long startFrame, float speed) {
        if (!Float.isFinite(speed) || speed <= 0) throw new IllegalArgumentException("Invalid speed");
        channels = asset.channelCount;
        sounds = new PcmHitSound[TYPES.length];
        int maxFrames = 1;
        for (int i = 0; i < sounds.length; i++) {
            if (sourceSounds != null && sourceSounds[i] != null) {
                sounds[i] = sourceSounds[i].convertTo(asset.sampleRate, channels);
                maxFrames = Math.max(maxFrames, sounds[i].frameCount());
            }
        }
        for (String path : timeline.customPaths()) {
            PcmHitSound source = customSamples.get(path);
            if (source == null) continue;
            PcmHitSound converted = source.convertTo(asset.sampleRate, channels);
            customSounds.put(path, converted);
            maxFrames = Math.max(maxFrames, converted.frameCount());
        }
        ring = new long[maxFrames * channels];
        cues = timeline.allCues();
        cueFrames = new long[cues.size()];
        for (int i = 0; i < cues.size(); i++) {
            long ms = addSaturated(cues.get(i).timeMs(), audioOffsetMs);
            long sourceFrame = Math.round(ms * (double) asset.sampleRate / 1000.0);
            // Output remains at native 1x. Move cue start times, never stretch the samples.
            cueFrames[i] = addSaturated(startFrame, Math.round((sourceFrame - (double) startFrame) / speed));
            if (sourceFrame < startFrame) cueFrames[i] = Math.min(startFrame - 1, cueFrames[i]);
        }
        reset(startFrame);
    }

    static long addSaturated(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        if (b < 0 && a < Long.MIN_VALUE - b) return Long.MIN_VALUE;
        return a + b;
    }

    private void reset(long frame) {
        nextFrame = frame;
        cursor = 0;
        Arrays.fill(ring, 0);
        int lo = 0, hi = cueFrames.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cueFrames[mid] < frame) lo = mid + 1;
            else hi = mid;
        }
        nextCue = lo;
    }

    void mix(byte[] music, int length, long firstFrame, float musicVolume, float hitVolume) {
        if (firstFrame != nextFrame) reset(firstFrame);
        double musicGain = gain(musicVolume), hitGain = gain(hitVolume);
        int frameSize = channels * 2;
        if (length < 0 || length > music.length || length % frameSize != 0) {
            throw new IllegalArgumentException("Complete PCM frames are required");
        }
        for (int byteIndex = 0; byteIndex < length; byteIndex += frameSize, nextFrame++) {
            while (nextCue < cues.size() && cueFrames[nextCue] <= nextFrame) {
                addCue(cues.get(nextCue++));
            }
            for (int channel = 0; channel < channels; channel++) {
                int index = byteIndex + channel * 2;
                short original = (short) ((music[index] & 255) | music[index + 1] << 8);
                long value = Math.round(original * musicGain + ring[cursor] * hitGain);
                short sample = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
                music[index] = (byte) sample;
                music[index + 1] = (byte) (sample >>> 8);
                ring[cursor] = 0;
                if (++cursor == ring.length) cursor = 0;
            }
        }
    }

    private void addCue(HitSoundTimeline.Cue cue) {
        for (NoteType type : TYPES) {
            int count = cue.count(type);
            PcmHitSound sound = sounds[type.ordinal()];
            addSound(sound, count);
        }
        for (HitSoundTimeline.CustomSound custom : cue.customSounds()) {
            PcmHitSound sound = customSounds.get(custom.path());
            if (sound != null) addSound(sound, custom.count());
            else for (NoteType type : TYPES) {
                addSound(sounds[type.ordinal()], custom.fallbackCount(type));
            }
        }
    }

    private void addSound(PcmHitSound sound, int count) {
        if (count == 0 || sound == null) return;
        int index = cursor;
        for (int frame = 0; frame < sound.frameCount(); frame++) {
            for (int channel = 0; channel < channels; channel++) {
                ring[index] += (long) sound.sample(frame, channel) * count;
                if (++index == ring.length) index = 0;
            }
        }
    }

    private static double gain(float value) {
        return Float.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }
}
