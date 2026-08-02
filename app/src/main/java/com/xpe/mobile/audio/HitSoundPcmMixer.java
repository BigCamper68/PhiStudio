package com.xpe.mobile.audio;

import com.xpe.mobile.model.NoteType;

import java.util.EnumMap;
import java.util.Map;

/** Mixes every voice in one timeline cue into one sample-aligned PCM buffer. */
final class HitSoundPcmMixer {
    static final int OUTPUT_SAMPLE_RATE = 48_000;
    static final int OUTPUT_CHANNEL_COUNT = 2;
    static final int OUTPUT_BYTES_PER_FRAME = OUTPUT_CHANNEL_COUNT * 2;
    private static final NoteType[] NOTE_TYPES = NoteType.values();

    private final Map<NoteType, PcmHitSound> sounds;

    static HitSoundPcmMixer forBuiltInSamples(PcmHitSound click,
                                              PcmHitSound flick,
                                              PcmHitSound drag) {
        if (click == null || flick == null || drag == null) {
            throw new IllegalArgumentException("Every built-in hit-sound sample is required");
        }
        EnumMap<NoteType, PcmHitSound> sounds = new EnumMap<>(NoteType.class);
        sounds.put(NoteType.TAP, click);
        sounds.put(NoteType.HOLD, click);
        sounds.put(NoteType.FLICK, flick);
        sounds.put(NoteType.DRAG, drag);
        return new HitSoundPcmMixer(sounds);
    }

    HitSoundPcmMixer(Map<NoteType, PcmHitSound> sourceSounds) {
        EnumMap<NoteType, PcmHitSound> converted = new EnumMap<>(NoteType.class);
        if (sourceSounds != null) {
            for (Map.Entry<NoteType, PcmHitSound> entry : sourceSounds.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                converted.put(entry.getKey(), entry.getValue().convertTo(
                        OUTPUT_SAMPLE_RATE, OUTPUT_CHANNEL_COUNT));
            }
        }
        sounds = converted;
    }

    /**
     * Produces one stereo buffer whose voices all begin at frame zero.
     *
     * <p>Multiplicity is preserved by summing every note before the final PCM saturation. TAP
     * and HOLD point to the same source sample, so a simultaneous TAP+HOLD is both sample-exact
     * and louder than either note alone.
     */
    byte[] mix(HitSoundTimeline.Cue cue, float volume) {
        if (cue == null || cue.noteCount() == 0 || !Float.isFinite(volume) || volume <= 0f) {
            return new byte[0];
        }
        double gain = Math.min(1.0, volume);
        int frameCount = 0;
        for (NoteType type : NOTE_TYPES) {
            if (cue.count(type) == 0) continue;
            PcmHitSound sound = sounds.get(type);
            if (sound != null) frameCount = Math.max(frameCount, sound.frameCount());
        }
        if (frameCount == 0) return new byte[0];

        byte[] output = new byte[Math.multiplyExact(frameCount, OUTPUT_BYTES_PER_FRAME)];
        for (int frame = 0; frame < frameCount; frame++) {
            for (int channel = 0; channel < OUTPUT_CHANNEL_COUNT; channel++) {
                long mixed = 0L;
                for (NoteType type : NOTE_TYPES) {
                    int count = cue.count(type);
                    PcmHitSound sound = sounds.get(type);
                    if (count > 0 && sound != null && frame < sound.frameCount()) {
                        mixed = saturatingAdd(mixed, (long) sound.sample(frame, channel) * count);
                    }
                }
                short sample = clampToPcm16(Math.round(mixed * gain));
                int byteIndex = (frame * OUTPUT_CHANNEL_COUNT + channel) * 2;
                output[byteIndex] = (byte) sample;
                output[byteIndex + 1] = (byte) (sample >>> 8);
            }
        }
        return output;
    }

    private static long saturatingAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        if (second < 0L && first < Long.MIN_VALUE - second) return Long.MIN_VALUE;
        return first + second;
    }

    private static short clampToPcm16(long value) {
        if (value > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (value < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) value;
    }
}
