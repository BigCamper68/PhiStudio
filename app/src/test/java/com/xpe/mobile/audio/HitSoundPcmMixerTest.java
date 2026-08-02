package com.xpe.mobile.audio;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;

import org.junit.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public final class HitSoundPcmMixerTest {
    @Test
    public void mixesEverySimultaneousVoiceAtFrameZero() {
        HitSoundTimeline.Cue cue = cue(NoteType.TAP, NoteType.TAP, NoteType.FLICK);
        Map<NoteType, PcmHitSound> sounds = new EnumMap<>(NoteType.class);
        sounds.put(NoteType.TAP, stereo(1_000, -1_000, 20_000, -20_000));
        sounds.put(NoteType.FLICK, stereo(500, 1_000));

        byte[] mixed = new HitSoundPcmMixer(sounds).mix(cue, 0.5f);

        assertEquals(2 * HitSoundPcmMixer.OUTPUT_BYTES_PER_FRAME, mixed.length);
        assertEquals(1_250, sample(mixed, 0, 0));
        assertEquals(-500, sample(mixed, 0, 1));
        assertEquals(20_000, sample(mixed, 1, 0));
        assertEquals(-20_000, sample(mixed, 1, 1));
    }

    @Test
    public void tapAndHoldShareOneSampleAndIncreaseItsGain() {
        HitSoundTimeline.Cue singleCue = cue(NoteType.TAP);
        HitSoundTimeline.Cue doubleCue = cue(NoteType.TAP, NoteType.HOLD);
        PcmHitSound click = stereo(4_000, -4_000);
        HitSoundPcmMixer mixer = HitSoundPcmMixer.forBuiltInSamples(
                click, stereo(1, 1), stereo(1, 1));

        byte[] single = mixer.mix(singleCue, 1.0f);
        byte[] doubled = mixer.mix(doubleCue, 1.0f);

        assertEquals(4_000, sample(single, 0, 0));
        assertEquals(8_000, sample(doubled, 0, 0));
        assertEquals(-8_000, sample(doubled, 0, 1));
    }

    @Test
    public void saturatesOnlyAfterVolumeAndVoiceSumming() {
        HitSoundTimeline.Cue cue = cue(NoteType.TAP, NoteType.TAP);
        Map<NoteType, PcmHitSound> sounds = new EnumMap<>(NoteType.class);
        sounds.put(NoteType.TAP, stereo(25_000, -25_000));
        HitSoundPcmMixer mixer = new HitSoundPcmMixer(sounds);

        byte[] fullVolume = mixer.mix(cue, 1.0f);
        byte[] halfVolume = mixer.mix(cue, 0.5f);

        assertEquals(Short.MAX_VALUE, sample(fullVolume, 0, 0));
        assertEquals(Short.MIN_VALUE, sample(fullVolume, 0, 1));
        assertEquals(25_000, sample(halfVolume, 0, 0));
        assertEquals(-25_000, sample(halfVolume, 0, 1));
    }

    private static HitSoundTimeline.Cue cue(NoteType... types) {
        ChartDocument chart = new ChartDocument();
        BpmChange bpm = new BpmChange();
        bpm.bpm = 120.0;
        chart.bpmChanges.add(bpm);
        JudgeLine line = new JudgeLine();
        for (NoteType type : types) {
            Note note = new Note();
            note.type = type;
            note.startTime = BeatTime.fromDouble(1.0, 4);
            note.endTime = note.startTime;
            line.notes.add(note);
        }
        chart.judgeLines.add(line);
        return HitSoundTimeline.build(chart, 0, 0).cuesBetween(499L, 500L).get(0);
    }

    private static PcmHitSound stereo(int... samples) {
        short[] pcm = new short[samples.length];
        for (int index = 0; index < samples.length; index++) {
            pcm[index] = (short) samples[index];
        }
        return new PcmHitSound(HitSoundPcmMixer.OUTPUT_SAMPLE_RATE,
                HitSoundPcmMixer.OUTPUT_CHANNEL_COUNT, pcm);
    }

    private static short sample(byte[] pcm, int frame, int channel) {
        int index = (frame * HitSoundPcmMixer.OUTPUT_CHANNEL_COUNT + channel) * 2;
        return (short) ((pcm[index] & 0xff) | pcm[index + 1] << 8);
    }
}
