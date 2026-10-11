package com.xpe.mobile.audio;

import com.xpe.mobile.model.NoteType;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

/** Fixed-memory overlap mixer. Dense hits add gain, never append audio to a task queue. */
final class HitSoundStreamMixer {
    static final int RATE = 48_000;
    static final int BLOCK_FRAMES = 240;
    private static final NoteType[] TYPES = NoteType.values();
    private final PcmHitSound[] sounds = new PcmHitSound[TYPES.length];
    private final long[] pending = new long[TYPES.length];
    private long[] ring;
    private Map<String,PcmHitSound> customSounds = Collections.emptyMap();
    private final Map<String,Long> pendingCustom = new HashMap<>();
    private int cursor;
    private int remainingFrames;

    HitSoundStreamMixer(PcmHitSound click, PcmHitSound flick, PcmHitSound drag) {
        sounds[NoteType.TAP.ordinal()] = click.convertTo(RATE, 2);
        sounds[NoteType.HOLD.ordinal()] = sounds[NoteType.TAP.ordinal()];
        sounds[NoteType.FLICK.ordinal()] = flick.convertTo(RATE, 2);
        sounds[NoteType.DRAG.ordinal()] = drag.convertTo(RATE, 2);
        setCustomSounds(Collections.emptyMap());
    }

    void setCustomSounds(Map<String,PcmHitSound> samples) {
        Map<String,PcmHitSound> converted = new HashMap<>();
        int frames = BLOCK_FRAMES;
        for (PcmHitSound sound : sounds) if (sound != null) frames = Math.max(frames, sound.frameCount());
        for (var entry : samples.entrySet()) {
            PcmHitSound sound = entry.getValue().convertTo(RATE, 2);
            converted.put(entry.getKey(), sound);
            frames = Math.max(frames, sound.frameCount());
        }
        customSounds = Collections.unmodifiableMap(converted);
        ring = new long[frames * 2];
        clear();
    }

    void add(HitSoundTimeline.Cue cue) {
        for (NoteType type : TYPES) {
            int i = type.ordinal();
            pending[i] = Math.min(Integer.MAX_VALUE, pending[i] + cue.count(type));
        }
        for (HitSoundTimeline.CustomSound custom : cue.customSounds()) {
            if (customSounds.containsKey(custom.path())) {
                pendingCustom.put(custom.path(), Math.min(Integer.MAX_VALUE,
                        pendingCustom.getOrDefault(custom.path(), 0L) + custom.count()));
            } else for (NoteType type : TYPES) {
                int i = type.ordinal();
                pending[i] = Math.min(Integer.MAX_VALUE, pending[i] + custom.fallbackCount(type));
            }
        }
    }

    boolean hasAudio() {
        if (remainingFrames > 0) return true;
        if (!pendingCustom.isEmpty()) return true;
        for (long count : pending) if (count > 0) return true;
        return false;
    }

    void clear() {
        Arrays.fill(pending, 0);
        pendingCustom.clear();
        Arrays.fill(ring, 0);
        cursor = 0;
        remainingFrames = 0;
    }

    void render(byte[] output, float volume) {
        for (int type = 0; type < sounds.length; type++) {
            long count = pending[type];
            pending[type] = 0;
            PcmHitSound sound = sounds[type];
            addSound(sound, count);
        }
        for (var entry : pendingCustom.entrySet()) addSound(customSounds.get(entry.getKey()), entry.getValue());
        pendingCustom.clear();
        double gain = Float.isFinite(volume) ? Math.max(0, Math.min(1, volume)) : 0;
        for (int i = 0; i < output.length; i += 2) {
            long value = Math.round(ring[cursor] * gain);
            short sample = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
            output[i] = (byte) sample;
            output[i + 1] = (byte) (sample >>> 8);
            ring[cursor] = 0;
            if (++cursor == ring.length) cursor = 0;
        }
        remainingFrames = Math.max(0, remainingFrames - output.length / 4);
    }

    private void addSound(PcmHitSound sound, long count) {
        if (sound == null || count == 0) return;
        remainingFrames = Math.max(remainingFrames, sound.frameCount());
        int index = cursor;
        for (int frame = 0; frame < sound.frameCount(); frame++) for (int channel = 0; channel < 2; channel++) {
            ring[index] += sound.sample(frame, channel) * count;
            if (++index == ring.length) index = 0;
        }
    }
}
