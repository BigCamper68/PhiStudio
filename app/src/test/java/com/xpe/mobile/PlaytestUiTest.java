package com.xpe.mobile;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowSystemClock;
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
