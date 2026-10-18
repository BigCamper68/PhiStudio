package com.xpe.mobile;

import android.app.AlertDialog;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.os.Handler;
import com.xpe.mobile.audio.EditorAudioController;
import com.xpe.mobile.audio.HitSoundTimeline;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.model.*;
import com.xpe.mobile.playtest.PlaytestClock;
import com.xpe.mobile.playtest.PlaytestOptions;
import com.xpe.mobile.preview.PlaytestJudge;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadow.api.Shadow;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlaytestUiTest {
    private MainActivity activity() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).get(); a.setTheme(R.style.AppTheme);
        a.editorView = new EditorView(a);
        ChartDocument c = ChartDocument.fromJson("{}"); c.judgeLines.clear(); c.judgeLines.add(new JudgeLine());
        Note n = new Note(); n.startTime = n.endTime = new BeatTime(200, 0, 1); c.judgeLines.get(0).notes.add(n);
        a.editorView.setChart(c); a.getPreferences(0).edit().clear().commit(); return a;
    }
    private AlertDialog dialog(MainActivity a, double head, java.util.function.DoubleConsumer start) {
        AlertDialog d = PlaytestOptionsDialog.show(a, a.editorView.getChart(), head, head * 2, start);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        return d;
    }
    private RadioButton radio(AlertDialog d, String tag) { return d.getWindow().getDecorView().findViewWithTag(tag); }
    private EditText field(AlertDialog d, String tag) { return d.getWindow().getDecorView().findViewWithTag(tag); }

    @Test @Config(qualifiers = "ru") public void playtestActionsStayEnglishOnARussianDevice() throws Exception {
        MainActivity a = activity(); AlertDialog d = dialog(a, 0, s -> fail());
        assertEquals("Cancel", d.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        assertEquals("Start", d.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString()); d.dismiss();
    }

    @Test public void lastSelectionsSurviveCancelReopenAndPreferenceReload() throws Exception {
        MainActivity a = activity(); AlertDialog d = dialog(a, 6.375, s -> fail());
        assertTrue(radio(d, "start.beginning").isChecked()); assertTrue(radio(d, "countdown.3").isChecked());
        radio(d, "start.custom").performClick(); radio(d, "custom.beat").performClick();
        field(d, "playtest.beat").setText("32.5"); radio(d, "countdown.5").performClick(); d.dismiss();
        PlaytestOptions saved = PlaytestOptions.load(a.getPreferences(0));
        assertEquals(PlaytestOptions.CUSTOM, saved.start); assertTrue(saved.customBeat);
        assertEquals("32.5", saved.beat); assertEquals(5, saved.countdown);
        d = dialog(a, 8, s -> fail()); assertTrue(radio(d, "start.custom").isChecked());
        assertTrue(radio(d, "custom.beat").isChecked()); assertTrue(radio(d, "countdown.5").isChecked());
        assertEquals("32.5", field(d, "playtest.beat").getText().toString()); d.dismiss();
        assertFalse(a.editorView.isChartDirty());
    }
    @Test public void currentPlayheadLabelAndStartAreRecomputedOnEveryOpening() throws Exception {
        MainActivity a = activity(); final double[] seconds = {-1};
        AlertDialog d = dialog(a, 6.375, s -> seconds[0] = s);
        assertEquals("From playhead (Beat 12.75)", radio(d, "start.playhead").getText().toString());
        radio(d, "start.playhead").performClick(); d.getButton(-1).performClick(); assertEquals(6.375, seconds[0], 0);
        d = dialog(a, 10, s -> seconds[0] = s); assertTrue(radio(d, "start.playhead").isChecked());
        assertEquals("From playhead (Beat 20)", radio(d, "start.playhead").getText().toString());
        d.getButton(-1).performClick(); assertEquals(10, seconds[0], 0);
    }
    @Test public void customTimeAndBeatHaveIndependentRememberedValues() throws Exception {
        MainActivity a = activity(); final double[] seconds = {-1};
        AlertDialog d = dialog(a, 0, s -> seconds[0] = s);
        radio(d, "start.custom").performClick(); field(d, "playtest.time").setText("1:23.125");
        radio(d, "custom.beat").performClick(); field(d, "playtest.beat").setText("32.5");
        d.getButton(-1).performClick(); assertEquals(16.25, seconds[0], 1e-10);
        d = dialog(a, 0, s -> seconds[0] = s); radio(d, "custom.time").performClick();
        assertEquals("1:23.125", field(d, "playtest.time").getText().toString());
        d.getButton(-1).performClick(); assertEquals(83.125, seconds[0], 1e-10);
    }
    @Test public void invalidCustomPositionStaysOpenAndShowsAnError() throws Exception {
        MainActivity a = activity(); AlertDialog d = dialog(a, 0, s -> fail());
        radio(d, "start.custom").performClick(); field(d, "playtest.time").setText("NaN");
        d.getButton(-1).performClick(); assertTrue(d.isShowing()); assertNotNull(field(d, "playtest.time").getError());
        field(d, "playtest.time").setText("99999"); d.getButton(-1).performClick();
        assertTrue(d.isShowing()); assertNotNull(field(d, "playtest.time").getError()); d.dismiss();
    }
    @Test public void fullscreenInputProducesManualResultsAndPausingClearsPointers() throws Exception {
        MainActivity a = activity(); ChartDocument c = a.editorView.getChart();
        c.judgeLines.get(0).notes.clear(); Note n = new Note(); c.judgeLines.get(0).notes.add(n); c.markEdited();
        long revision = c.revision();
        PlaytestView screen = new PlaytestView(a, c, 0, 0, () -> { });
        screen.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY));
        screen.layout(0, 0, 960, 540); frame(screen);
        assertEquals(PlaytestClock.State.PLAYING, screen.state()); assertEquals(0, screen.judge().judged());
        MotionEvent event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, 480, 450, 0);
        screen.dispatchTouchEvent(event); event.recycle(); frame(screen);
        assertEquals(1, screen.judge().count(PlaytestJudge.Grade.PERFECT));
        screen.pause(); assertEquals(PlaytestClock.State.PAUSED, screen.state());
        double before = screen.seconds(); ShadowSystemClock.advanceBy(Duration.ofSeconds(10)); frame(screen);
        assertEquals(before, screen.seconds(), 0); screen.close(); assertEquals(revision, c.revision());
        assertFalse(a.editorView.isChartDirty());
    }
    @Test public void androidPointerDownAndMoveKeepIndependentChordTimings() throws Exception {
        MainActivity a = activity(); ChartDocument c = a.editorView.getChart();
        c.judgeLines.get(0).notes.clear();
        for (int i = 0; i < 2; i++) {
            Note n = new Note(); n.startTime = n.endTime = new BeatTime(2, 0, 1);
            c.judgeLines.get(0).notes.add(n);
        }
        c.markEdited(); PlaytestView screen = screen(a, c); frame(screen);
        ShadowSystemClock.advanceBy(Duration.ofMillis(910)); long down = SystemClock.uptimeMillis();
        pointers(screen, down, MotionEvent.ACTION_DOWN, new int[]{3}, new float[]{480});
        ShadowSystemClock.advanceBy(Duration.ofMillis(20));
        // Android may reorder pointer indices: identity belongs to pointer IDs.
        pointers(screen, down, MotionEvent.ACTION_POINTER_DOWN, new int[]{9, 3}, new float[]{480, 480});
        ShadowSystemClock.advanceBy(Duration.ofMillis(20));
        pointers(screen, down, MotionEvent.ACTION_MOVE, new int[]{3, 9}, new float[]{480, 480});
        frame(screen);
        assertEquals(1, screen.judge().count(PlaytestJudge.Grade.GOOD));
        assertEquals(1, screen.judge().count(PlaytestJudge.Grade.PERFECT));
        pointers(screen, down, MotionEvent.ACTION_POINTER_UP, new int[]{9, 3}, new float[]{480, 480});
        pointers(screen, down, MotionEvent.ACTION_UP, new int[]{3}, new float[]{480}); frame(screen);
        assertEquals(2, screen.judge().judged()); screen.close();
    }
    @Test public void androidChordCanFinishBothContactsBeforeTheNextDraw() throws Exception {
        MainActivity a = activity(); ChartDocument c = a.editorView.getChart();
        c.judgeLines.get(0).notes.clear();
        for (int x : new int[]{0, 135}) { Note n = new Note(); n.positionX = x; c.judgeLines.get(0).notes.add(n); }
        c.markEdited(); PlaytestView screen = screen(a, c); frame(screen);
        long down = SystemClock.uptimeMillis();
        pointers(screen, down, MotionEvent.ACTION_DOWN, new int[]{9}, new float[]{523.2f});
        pointers(screen, down, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new int[]{9, 3}, new float[]{523.2f, 422.4f});
        pointers(screen, down, MotionEvent.ACTION_POINTER_UP, new int[]{9, 3}, new float[]{523.2f, 422.4f});
        pointers(screen, down, MotionEvent.ACTION_UP, new int[]{3}, new float[]{422.4f}); frame(screen);
        assertEquals(2, screen.judge().count(PlaytestJudge.Grade.PERFECT));
        assertEquals(1000000, screen.judge().score()); frame(screen);
        assertEquals(2, screen.judge().judged()); screen.close();
    }
    @Test @Config(shadows = RecordingAudioController.class)
    public void oneChordReachesTheAudioConsumerAsOneIndivisibleSubmission() throws Exception {
        MainActivity a = activity(); ChartDocument c = a.editorView.getChart();
        c.judgeLines.get(0).notes.clear();
        c.judgeLines.get(0).notes.add(new Note()); c.judgeLines.get(0).notes.add(new Note()); c.markEdited();
        RecordingAudioController audio = recordAudio(a);
        PlaytestView screen = screen(a, c); frame(screen); long down = SystemClock.uptimeMillis();
        pointers(screen, down, MotionEvent.ACTION_DOWN, new int[]{3}, new float[]{480});
        pointers(screen, down, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new int[]{3, 9}, new float[]{480, 480});
        frame(screen);
        assertEquals(2, screen.judge().count(PlaytestJudge.Grade.PERFECT));
        assertEquals("The audio consumer must not run between the two hits", 1, audio.cues.size());
        assertEquals(2, audio.cues.get(0).noteCount());
        assertEquals(2, audio.cues.get(0).count(NoteType.TAP));
        frame(screen); assertEquals(1, audio.cues.size()); screen.close();
    }
    @Test @Config(shadows = RecordingAudioController.class)
    public void holdHeadsShareOneAudioOnsetAndTailsDoNotReplayIt() throws Exception {
        MainActivity a = activity(); ChartDocument c = a.editorView.getChart(); c.judgeLines.get(0).notes.clear();
        for (int i = 0; i < 2; i++) {
            Note n = new Note(); n.type = NoteType.HOLD; n.endTime = new BeatTime(2, 0, 1);
            c.judgeLines.get(0).notes.add(n);
        }
        c.markEdited(); RecordingAudioController audio = recordAudio(a);
        PlaytestView screen = screen(a, c); frame(screen); long down = SystemClock.uptimeMillis();
        pointers(screen, down, MotionEvent.ACTION_DOWN, new int[]{3}, new float[]{480});
        pointers(screen, down, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new int[]{3, 9}, new float[]{480, 480}); frame(screen);
        assertEquals(0, screen.judge().judged()); assertEquals(1, audio.cues.size());
        assertEquals(2, audio.cues.get(0).count(NoteType.HOLD));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); frame(screen);
        assertEquals(2, screen.judge().count(PlaytestJudge.Grade.PERFECT));
        assertEquals(1, audio.cues.size()); screen.close();
    }
    private RecordingAudioController recordAudio(MainActivity a) {
        a.audioController = new EditorAudioController(a, new Handler(android.os.Looper.getMainLooper()),
                new EditorAudioController.Listener() {
                    public void onAudioStateChanged() { }
                    public void onAudioCompleted() { }
                    public void showMessage(String message) { }
                    public boolean isHostUnavailable() { return false; }
                });
        return Shadow.extract(a.audioController);
    }
    @Implements(value = EditorAudioController.class, isInAndroidSdk = false)
    public static class RecordingAudioController {
        final List<HitSoundTimeline.Cue> cues = new ArrayList<>();
        @Implementation protected void __constructor__(Activity a, Handler h, EditorAudioController.Listener listener) { }
        @Implementation protected boolean isReady() { return false; }
        @Implementation protected long durationMillis() { return 0; }
        @Implementation protected void pause() { }
        @Implementation protected void playHitSound(HitSoundTimeline.Cue cue) {
            cues.add(cue);
            // Let the consumer run for a complete PCM block before the next UI call.
            ShadowSystemClock.advanceBy(Duration.ofMillis(5));
        }
    }
    private PlaytestView screen(MainActivity a, ChartDocument c) {
        PlaytestView screen = new PlaytestView(a, c, 0, 0, () -> { });
        screen.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY));
        screen.layout(0, 0, 960, 540); return screen;
    }
    private void pointers(View screen, long down, int action, int[] ids, float[] x) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = ids[i];
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords(); coords[i].x = x[i]; coords[i].y = 450;
            coords[i].pressure = coords[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, ids.length,
                properties, coords, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        assertTrue(screen.dispatchTouchEvent(event)); event.recycle();
    }
    private void frame(View v) {
        Bitmap b = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888); v.draw(new Canvas(b)); b.recycle();
    }
    @Test public void silentPlaytestIncludesTheVisualTailOfFakeNotes() throws Exception {
        MainActivity a = activity(); ChartDocument c = a.editorView.getChart();
        c.judgeLines.get(0).notes.clear(); Note fake = new Note(); fake.fake = true;
        fake.startTime = fake.endTime = new BeatTime(6, 0, 1);
        c.judgeLines.get(0).notes.add(fake); c.markEdited();
        PlaytestView screen = new PlaytestView(a, c, 0, 0, () -> { });
        screen.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY));
        screen.layout(0, 0, 960, 540); frame(screen);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2)); frame(screen);
        assertEquals(PlaytestClock.State.PLAYING, screen.state()); assertEquals(0, screen.judge().total());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2)); frame(screen);
        assertEquals(PlaytestClock.State.FINISHED, screen.state()); screen.close();
    }
}
