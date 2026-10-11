package com.xpe.mobile.editor;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.StoryboardEventType;
import com.xpe.mobile.R;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class ArrangePlacementTest {
    private EditorView editor;
    private java.lang.reflect.Method drawFooter;
    @Before public void setup() throws Exception {
        editor = new EditorView(RuntimeEnvironment.getApplication());
        editor.setChart(ChartDocument.fromJson("{}"));
        drawFooter = EditorChromeView.class.getDeclaredMethod("drawBottomBar", Canvas.class);
        drawFooter.setAccessible(true);
        layout(1200, 700);
    }
    private void layout(int width, int height) {
        editor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        editor.layout(0, 0, width, height);
    }
    private void draw() throws Exception {
        editor.buttons.clear();
        drawFooter.invoke(editor, new Canvas());
    }
    private static final class Labels extends Canvas {
        final java.util.List<String> texts = new java.util.ArrayList<>();
        @Override public void drawText(String text, float x, float y, Paint paint) { texts.add(text); }
    }
    @Test public void bothXGridButtonsAreOnTheSecondPageWithMinusImmediatelyBeforePlus() throws Exception {
        openArrange();
        Labels first = new Labels(); editor.buttons.clear(); drawFooter.invoke(editor, first);
        String plus = editor.getResources().getString(R.string.controls_x_grid_increase);
        String minus = editor.getResources().getString(R.string.controls_x_grid_decrease);
        assertFalse(first.texts.contains(plus)); assertFalse(first.texts.contains(minus));
        EditorChromeView.ButtonSpec next = editor.buttons.get(editor.buttons.size() - 1);
        click(next.bounds.centerX(), next.bounds.centerY());
        Labels second = new Labels(); editor.buttons.clear(); drawFooter.invoke(editor, second);
        assertTrue(second.texts.contains(minus)); assertTrue(second.texts.contains(plus));
        assertEquals(second.texts.indexOf(minus) + 1, second.texts.indexOf(plus));
        int original = editor.verticalGridLines;
        EditorChromeView.ButtonSpec increase = editor.buttons.get(4);
        click(increase.bounds.centerX(), increase.bounds.centerY());
        assertTrue(editor.verticalGridLines > original);
        draw();
        EditorChromeView.ButtonSpec decrease = editor.buttons.get(3);
        click(decrease.bounds.centerX(), decrease.bounds.centerY());
        assertEquals(original, editor.verticalGridLines);
    }
    @Test @Config(qualifiers = "w744dp-h360dp-land-xxhdpi")
    public void arrangeHasExactlyTwoPagesAtPhoneDensitiesAndArrowsFollowTheControlsOnTheRight() throws Exception {
        float d = editor.density;
        assertEquals(3, d, .001f);
        for (int widthDp : new int[]{640, 680, 744, 800, 1024, 1280}) {
            layout(Math.round(widthDp * d), Math.round(360 * d));
            openArrange();
            Labels first = new Labels(); editor.buttons.clear(); drawFooter.invoke(editor, first);
            assertFalse(first.texts.contains(editor.getResources().getString(R.string.controls_x_grid_increase)));
            assertFalse(first.texts.contains(editor.getResources().getString(R.string.controls_x_grid_decrease)));
            EditorChromeView.ButtonSpec next = editor.buttons.get(editor.buttons.size() - 1);
            assertEquals(6 * d, editor.buttons.get(3).bounds.left, .01f);
            assertTrue(next.bounds.left > editor.buttons.get(3).bounds.right);
            click(next.bounds.centerX(), next.bounds.centerY());
            Labels second = new Labels(); editor.buttons.clear(); drawFooter.invoke(editor, second);
            assertTrue(second.texts.contains(editor.getResources().getString(R.string.controls_x_grid_decrease)));
            assertTrue(second.texts.contains(editor.getResources().getString(R.string.controls_x_grid_increase)));
            assertEquals(second.texts.indexOf(editor.getResources().getString(R.string.controls_x_grid_decrease)) + 1,
                    second.texts.indexOf(editor.getResources().getString(R.string.controls_x_grid_increase)));
            // The second page is the last; only Back is an enabled arrow.
            EditorChromeView.ButtonSpec back = editor.buttons.get(editor.buttons.size() - 1);
            EditorChromeView.ButtonSpec lastAction = editor.buttons.get(editor.buttons.size() - 2);
            assertEquals(lastAction.bounds.right + 8 * d, back.bounds.left, .01f);
            assertEquals(6 * d, editor.buttons.get(3).bounds.left, .01f);
            assertTrue(back.bounds.left < editor.editorSplitX() - 80 * d);
            for (EditorChromeView.ButtonSpec button : editor.buttons)
                assertTrue(button.bounds.right <= editor.editorSplitX() + .01f);
        }
    }
    @Test public void footerHasOnlyTwoControlRowsAndNoSelectionOrHintText() throws Exception {
        Labels labels = new Labels(); drawFooter.invoke(editor, labels);
        assertEquals(76 * editor.density, editor.bottomBarHeight(), .001f);
        for (String label : labels.texts) {
            assertFalse(label.contains("No selection"));
            assertFalse(label.contains("Box+ adds"));
            assertFalse(label.contains("Move cycles"));
        }
    }
    @Test public void storyboardCreateHasExactlyTheOrdinaryToolsAndNoNewFamilyButtons() throws Exception {
        editor.setWorkbenchTab(1);
        Labels labels = new Labels(); editor.buttons.clear(); drawFooter.invoke(editor, labels);
        assertTrue(labels.texts.contains(editor.getResources().getString(R.string.controls_event)));
        for (String label : labels.texts) assertFalse(label.startsWith("New "));
        // Selecting a header must not add or switch to contextual creation controls.
        editor.storyboardTimeline.draw(new Canvas(), editor.editorSplitX(), editor.toolbarHeight(),
                editor.getWidth(), editor.getHeight() - editor.bottomBarHeight());
        float x = editor.editorSplitX() + 20 * editor.density, y = editor.toolbarHeight() + 12 * editor.density;
        click(x, y);
        Labels after = new Labels(); editor.buttons.clear(); drawFooter.invoke(editor, after);
        for (String label : after.texts) assertFalse(label.startsWith("New "));
        assertTrue(after.texts.contains(editor.getResources().getString(R.string.controls_event)));
    }
    private void click(float x, float y) {
        long time = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(time, time + 10, MotionEvent.ACTION_UP, x, y, 0);
        editor.onTouchEvent(down); editor.onTouchEvent(up);
        down.recycle(); up.recycle();
    }
    private void openArrange() throws Exception {
        draw();
        EditorChromeView.ButtonSpec tab = editor.buttons.get(2);
        float d = editor.density;
        assertEquals(150 * d, tab.bounds.left, .001f);
        assertEquals(editor.getHeight() - editor.bottomBarHeight() + 5 * d, tab.bounds.top, .001f);
        click(tab.bounds.centerX(), tab.bounds.centerY());
        draw();
    }
    @Test public void arrangeIsTheThirdLeftTabAndItsControlsStayLeftOfTheWorkbench() throws Exception {
        for (int width : new int[]{800, 1200, 1920}) {
            layout(width, 700);
            openArrange();
            for (EditorChromeView.ButtonSpec button : editor.buttons) {
                assertTrue(button.bounds.left >= 0);
                assertTrue(button.bounds.right <= editor.editorSplitX() + .001f);
            }
        }
    }
    @Test public void restoredArrangeButtonsChangeBindingAndLayerThroughActualTouches() throws Exception {
        openArrange();
        boolean original = editor.isXYBindingEnabled();
        // Three tabs, then XY; Layer - is initially disabled, so Layer + is the next hit target.
        EditorChromeView.ButtonSpec binding = editor.buttons.get(3);
        click(binding.bounds.centerX(), binding.bounds.centerY());
        assertEquals(!original, editor.isXYBindingEnabled());
        draw();
        EditorChromeView.ButtonSpec layerPlus = editor.buttons.get(4);
        click(layerPlus.bounds.centerX(), layerPlus.bounds.centerY());
        assertEquals(1, editor.getEventLayerIndex());
    }
    @Test public void restoredArrangeLineNavigationStillCancelsOldStoryboardSelection() throws Exception {
        editor.getChart().judgeLines.add(new JudgeLine());
        editor.setWorkbenchTab(1);
        ExtendedLineEvents.NumericEvent event = new ExtendedLineEvents.NumericEvent();
        event.startTime = new BeatTime(4, 0, 1); event.endTime = new BeatTime(6, 0, 1);
        assertEquals(EditorView.StoryboardApplyResult.APPLIED,
                editor.addStoryboardEvent(StoryboardEventType.SCALE_X, event));
        editor.storyboardTimeline.select(StoryboardEventType.SCALE_X, event);
        assertSame(event, editor.storyboardTimeline.selected());
        openArrange();
        // At layer/line zero the enabled controls are XY, Layer +, Line +.
        EditorChromeView.ButtonSpec linePlus = editor.buttons.get(5);
        click(linePlus.bounds.centerX(), linePlus.bounds.centerY());
        assertEquals(1, editor.getLineIndex());
        assertEquals(1, editor.getWorkbenchTab());
        assertNull(editor.storyboardTimeline.selected());
        assertTrue(editor.getChart().judgeLines.get(0).extended.scaleXEvents.contains(event));
    }
}
