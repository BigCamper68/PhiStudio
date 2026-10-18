package com.xpe.mobile.audio;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.xpe.mobile.model.*;
import java.io.File;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlaytestAudioTest {
    @Test @Config(shadows = RecordingHitSoundPlayer.class)
    public void startingMusicKeepsThePreparedManualOutputButPauseAndExitStopIt() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).get(); Handler h = new Handler(Looper.getMainLooper());
        EditorAudioController c = new EditorAudioController(a, h, new EditorAudioController.Listener() {
            public void onAudioStateChanged() { }
            public void onAudioCompleted() { }
            public void showMessage(String message) { }
            public boolean isHostUnavailable() { return false; }
        });
        try {
            Field field = EditorAudioController.class.getDeclaredField("hitSoundPlayer"); field.setAccessible(true);
            RecordingHitSoundPlayer output = Shadow.extract(field.get(c));
            c.prepareManualHitSounds(); assertEquals(0, output.preparations);
            c.setManualJudgements(true); c.prepareManualHitSounds();
            assertEquals(1, output.preparations); int stopped = output.stops;
            c.start(0, 1);
            assertEquals("Music must not flush the warmed hit-sound queue", stopped, output.stops);
            assertEquals(2, output.preparations);
            c.pause(); assertEquals(stopped + 1, output.stops);
            c.prepareManualHitSounds(); assertEquals(3, output.preparations);
            c.setManualJudgements(false); c.prepareManualHitSounds();
            assertEquals(3, output.preparations); assertEquals(stopped + 2, output.stops);
        } finally { c.close(); }
    }
    @Implements(value = HitSoundPlayer.class, isInAndroidSdk = false)
    public static class RecordingHitSoundPlayer {
        int preparations, stops;
        @Implementation protected void __constructor__(Context c, Handler h, HitSoundPlayer.Fallback f) { }
        @Implementation protected void prepare() { preparations++; }
        @Implementation protected void stop() { stops++; }
        @Implementation protected void close() { }
        @Implementation protected void setCustomSounds(java.util.Map<String, PcmHitSound> samples) { }
    }
    @Test public void manualModeMutesScheduledHitsWithMusicAndRestoresPreviewOnExit() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).get(); Handler h = new Handler(Looper.getMainLooper());
        EditorAudioController c = new EditorAudioController(a, h, new EditorAudioController.Listener() {
            public void onAudioStateChanged() { }
            public void onAudioCompleted() { }
            public void showMessage(String message) { }
            public boolean isHostUnavailable() { return false; }
        });
        try {
            PcmAudioPlayer p = new PcmAudioPlayer(new PcmAudioAsset(new File(a.getCacheDir(), "inert.pcm"), 44100, 2, 0), h,
                    new PcmAudioPlayer.Listener() { public void onCompletion() { } public void onError(String message) { } });
            Field player = EditorAudioController.class.getDeclaredField("pcmAudioPlayer"); player.setAccessible(true); player.set(c, p);
            ChartDocument chart = ChartDocument.fromJson("{}"); chart.judgeLines.get(0).notes.add(new Note());
            HitSoundTimeline timeline = HitSoundTimeline.build(chart, 0, 0); c.configureHitSounds(timeline, -21, 32);
            Field schedule = PcmAudioPlayer.class.getDeclaredField("timeline"); schedule.setAccessible(true);
            assertTrue(c.schedulesHitSounds()); assertEquals(1, ((HitSoundTimeline) schedule.get(p)).size());
            c.setManualJudgements(true); assertFalse(c.schedulesHitSounds());
            assertEquals(0, ((HitSoundTimeline) schedule.get(p)).size());
            c.configureHitSounds(timeline, 100, 0); assertEquals(0, ((HitSoundTimeline) schedule.get(p)).size());
            c.setManualJudgements(false); assertTrue(c.schedulesHitSounds());
            assertEquals(1, ((HitSoundTimeline) schedule.get(p)).size());
        } finally { c.close(); }
    }
    @Test public void liveCuesKeepCustomAndBuiltinOverridesAndIgnoreFakes() {
        Note n = new Note(); n.type = NoteType.HOLD;
        assertEquals(1, HitSoundTimeline.forNote(n).count(NoteType.HOLD));
        n.hitSound = "flick.mp3"; assertEquals(1, HitSoundTimeline.forNote(n).count(NoteType.FLICK));
        n.hitSound = "custom/hit.wav"; HitSoundTimeline.Cue cue = HitSoundTimeline.forNote(n);
        assertEquals(1, cue.noteCount()); assertEquals("custom/hit.wav", cue.customSounds().get(0).path());
        assertEquals(1, cue.customSounds().get(0).fallbackCount(NoteType.HOLD));
        n.fake = true; assertEquals(0, HitSoundTimeline.forNote(n).noteCount());
    }
}
