package com.xpe.mobile.editor;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.View;
import com.xpe.mobile.R;
import com.xpe.mobile.model.*;
import java.util.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ArrangePlacementTest {
    private EditorView editor;
    private long clock = 1000;

    @Before public void setup() throws Exception {
        editor = new EditorView(RuntimeEnvironment.getApplication());
        editor.setChart(ChartDocument.fromJson("{}"));
        layout(1200, 700);
    }
    private void layout(int width, int height) {
        editor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        editor.layout(0, 0, width, height);
    }
    private static final class Labels extends Canvas {
        final Map<String, PointF> texts = new LinkedHashMap<>();
        @Override public void drawText(String text, float x, float y, Paint paint) {
            texts.put(text, new PointF(x, y));
        }
    }
    private Labels draw() {
        Labels result = new Labels();
        editor.buttons.clear();
        editor.editorBottomBarUi.drawBottomBar(result);
        return result;
    }
    private void touch(int action, float x, float y, long elapsed) {
        MotionEvent event = MotionEvent.obtain(clock, clock + elapsed, action, x, y, 0);
        editor.onTouchEvent(event); event.recycle();
    }
    private void click(float x, float y) {
        touch(MotionEvent.ACTION_DOWN, x, y, 0);
        touch(MotionEvent.ACTION_UP, x, y, 20);
        clock += 100;
    }
    private float rowY() { return editor.getHeight() - editor.bottomBarHeight() + 53 * editor.density; }
    private void swipe(boolean left) {
        float start = left ? editor.editorSplitX() - 14 * editor.density : 14 * editor.density;
        float end = left ? 14 * editor.density : editor.editorSplitX() - 14 * editor.density;
        touch(MotionEvent.ACTION_DOWN, start, rowY(), 0);
        touch(MotionEvent.ACTION_MOVE, end, rowY(), 400);
        touch(MotionEvent.ACTION_UP, end, rowY(), 2400);
        clock += 3000;
        draw();
    }
    private EditorChromeView.ButtonSpec visibleButton(int id) {
        Labels labels = draw();
        PointF point = labels.texts.get(editor.getResources().getString(id));
        if (point != null) for (EditorChromeView.ButtonSpec button : editor.buttons)
            if (button.bounds.contains(point.x, point.y)) return button;
        return null;
    }
    private EditorChromeView.ButtonSpec reveal(int id) {
        for (int i = 0; i < 30; i++) {
            EditorChromeView.ButtonSpec button = visibleButton(id);
            if (button != null) return button;
            swipe(true);
        }
        throw new AssertionError("Unreachable action: " + editor.getResources().getString(id));
    }
    private void clickAction(int id) {
        EditorChromeView.ButtonSpec button = reveal(id);
        click(button.bounds.centerX(), button.bounds.centerY());
    }
    private void openArrange() {
        draw();
        EditorChromeView.ButtonSpec tab = editor.buttons.get(2);
        assertEquals(150 * editor.density, tab.bounds.left, .001f);
        click(tab.bounds.centerX(), tab.bounds.centerY());
        draw();
    }
    private List<String> actionLabels() {
        List<String> result = new ArrayList<>();
        for (EditorChromeView.BottomAction action : editor.editorBottomBarUi.bottomActions()) result.add(action.label);
        return result;
    }
    private String label(int id) { return editor.getResources().getString(id); }

    @Test public void beatGridComesFirstAndXGridButtonsAreReachableBySwiping() {
        openArrange();
        List<String> actions = actionLabels();
        assertEquals(label(R.string.controls_beat_grid_decrease), actions.get(0));
        assertEquals(label(R.string.controls_beat_grid_increase), actions.get(1));
        assertEquals(actions.indexOf(label(R.string.controls_x_grid_decrease)) + 1,
                actions.indexOf(label(R.string.controls_x_grid_increase)));
        int original = editor.verticalGridLines;
        clickAction(R.string.controls_x_grid_increase);
        assertTrue(editor.verticalGridLines > original);
        clickAction(R.string.controls_x_grid_decrease);
        assertEquals(original, editor.verticalGridLines);
    }

    @Test @Config(qualifiers = "w744dp-h360dp-land-xxhdpi")
    public void allArrangeActionsRemainReachableAndHitBoundsStayInsideTheRowAtPhoneDensities() {
        float d = editor.density;
        assertEquals(3, d, .001f);
        for (int widthDp : new int[]{640, 680, 744, 800, 1024, 1280}) {
            layout(Math.round(widthDp * d), Math.round(360 * d));
            openArrange();
            assertNotNull(visibleButton(R.string.controls_beat_grid_increase));
            reveal(editor.xyBindingEnabled ? R.string.xy_binding_button_on : R.string.xy_binding_button_off);
            for (EditorChromeView.ButtonSpec button : editor.buttons) {
                assertTrue(button.bounds.left >= 0);
                assertTrue(button.bounds.right <= editor.editorSplitX());
            }
            assertFalse(draw().texts.containsKey("<"));
            assertFalse(draw().texts.containsKey(">"));
            for (int i = 0; i < 6; i++) swipe(false);
            assertNotNull(visibleButton(R.string.controls_beat_grid_increase));
        }
    }

    @Test public void aButtonRunsOnlyOnReleaseAndHorizontalOrVerticalSwipesNeverActivateIt() {
        openArrange();
        EditorChromeView.ButtonSpec plus = visibleButton(R.string.controls_beat_grid_increase);
        int original = editor.subdivision;
        float x = plus.bounds.centerX(), y = plus.bounds.centerY();
        touch(MotionEvent.ACTION_DOWN, x, y, 0);
        assertEquals(original, editor.subdivision);
        touch(MotionEvent.ACTION_UP, x, y, 20); clock += 100;
        assertTrue(editor.subdivision > original);
        original = editor.subdivision;
        swipe(true);
        assertEquals(original, editor.subdivision);
        openArrange();
        plus = visibleButton(R.string.controls_beat_grid_increase);
        x = plus.bounds.centerX(); y = plus.bounds.centerY();
        double beat = editor.currentBeat;
        touch(MotionEvent.ACTION_DOWN, x, y, 0);
        touch(MotionEvent.ACTION_MOVE, x, y - 100 * editor.density, 100);
        touch(MotionEvent.ACTION_UP, x, y - 100 * editor.density, 200);
        assertEquals(original, editor.subdivision);
        assertEquals(beat, editor.currentBeat, 0);
    }

    @Test public void cancelAndSecondPointerDiscardPendingActions() {
        openArrange();
        EditorChromeView.ButtonSpec plus = visibleButton(R.string.controls_beat_grid_increase);
        float x = plus.bounds.centerX(), y = plus.bounds.centerY();
        int original = editor.subdivision;
        touch(MotionEvent.ACTION_DOWN, x, y, 0);
        touch(MotionEvent.ACTION_CANCEL, x, y, 10);
        touch(MotionEvent.ACTION_UP, x, y, 20); clock += 100;
        touch(MotionEvent.ACTION_DOWN, x, y, 0);
        touch(MotionEvent.ACTION_POINTER_DOWN, x, y, 10);
        touch(MotionEvent.ACTION_UP, x, y, 20);
        assertEquals(original, editor.subdivision);
    }

    @Test public void swipingAcrossEditActionsCannotDeleteOrSubdivideTheSelection() {
        LineEvent source = new LineEvent(); source.type = EventType.ALPHA;
        source.startTime = new BeatTime(1, 0, 1); source.endTime = new BeatTime(5, 0, 1);
        editor.currentLayer().events(source.type).clear();
        editor.currentLayer().events(source.type).add(source);
        editor.selectedEvent = source; editor.selectedEvents.add(source); editor.activateEditPanel();
        EditorChromeView.ButtonSpec delete = reveal(R.string.controls_delete);
        float x = delete.bounds.centerX(), y = delete.bounds.centerY();
        touch(MotionEvent.ACTION_DOWN, x, y, 0);
        touch(MotionEvent.ACTION_MOVE, x - 100 * editor.density, y, 200);
        touch(MotionEvent.ACTION_UP, x - 100 * editor.density, y, 2400);
        assertEquals(Collections.singletonList(source), editor.currentLayer().events(source.type));
        assertSame(source, editor.selectedEvent);
        assertFalse(editor.history.canUndo());
    }

    @Test public void fastSwipesFlingWithinBoundsAndTouchingStopsWithoutActivatingAButton() throws Exception {
        openArrange();
        float x = editor.editorSplitX() - 50 * editor.density, y = rowY();
        int subdivision = editor.subdivision;
        touch(MotionEvent.ACTION_DOWN, x, y, 0);
        touch(MotionEvent.ACTION_MOVE, x - 30 * editor.density, y, 20);
        touch(MotionEvent.ACTION_UP, x - 60 * editor.density, y, 40); clock += 100;
        java.lang.reflect.Field field = EditorBottomBarUi.class.getDeclaredField("scroller");
        field.setAccessible(true);
        android.widget.OverScroller scroller = (android.widget.OverScroller) field.get(editor.editorBottomBarUi);
        assertFalse(scroller.isFinished());
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(50));
        draw();
        for (EditorChromeView.ButtonSpec button : editor.buttons) {
            assertTrue(button.bounds.left >= 0);
            assertTrue(button.bounds.right <= editor.editorSplitX());
        }
        touch(MotionEvent.ACTION_DOWN, 40 * editor.density, y, 0);
        touch(MotionEvent.ACTION_UP, 40 * editor.density, y, 20);
        assertTrue(scroller.isFinished());
        assertEquals(subdivision, editor.subdivision);
        layout(2800, 700); draw();
        assertNotNull(visibleButton(R.string.controls_beat_grid_increase));
    }

    @Test public void footerHasOnlyTwoControlRowsAndNoSelectionOrHintText() {
        Labels labels = draw();
        assertEquals(76 * editor.density, editor.bottomBarHeight(), .001f);
        for (String label : labels.texts.keySet()) {
            assertFalse(label.contains("No selection"));
            assertFalse(label.contains("Box+ adds"));
            assertFalse(label.contains("Move cycles"));
        }
    }

    @Test public void storyboardCreateKeepsTheSameToolsAndCreationPriority() {
        editor.setWorkbenchTab(1);
        List<String> expected = Arrays.asList(label(R.string.controls_tap), label(R.string.controls_hold),
                label(R.string.controls_drag), label(R.string.controls_flick), label(R.string.controls_event),
                label(R.string.controls_select));
        assertEquals(expected, actionLabels());
        clickAction(R.string.controls_event);
        assertEquals(EditorSurfaceView.Tool.EVENT, editor.tool);
    }

    @Test public void propertiesAndFrequentEditsPrecedeSpecializedActions() {
        Note note = new Note(); editor.currentLine().notes.add(note);
        editor.selectedNote = note; editor.selectedNotes.add(note);
        editor.activateEditPanel();
        List<String> actions = actionLabels();
        assertEquals(label(R.string.properties_button), actions.get(0));
        assertEquals(label(R.string.controls_copy), actions.get(1));
        assertTrue(actions.indexOf(label(R.string.controls_batch_edit)) < actions.indexOf(label(R.string.controls_width_increase)));
        assertEquals(label(R.string.controls_clear), actions.get(actions.size() - 1));
    }

    @Test public void arrangeBindingAndLayerRespondToActualTouchesAfterScrolling() {
        openArrange();
        boolean original = editor.isXYBindingEnabled();
        clickAction(original ? R.string.xy_binding_button_on : R.string.xy_binding_button_off);
        assertEquals(!original, editor.isXYBindingEnabled());
        openArrange();
        clickAction(R.string.controls_layer_increase);
        assertEquals(1, editor.getEventLayerIndex());
    }

    @Test public void lineNavigationCancelsOldStoryboardSelection() {
        editor.getChart().judgeLines.add(new JudgeLine());
        editor.setWorkbenchTab(1);
        ExtendedLineEvents.NumericEvent event = new ExtendedLineEvents.NumericEvent();
        event.startTime = new BeatTime(4, 0, 1); event.endTime = new BeatTime(6, 0, 1);
        assertEquals(EditorView.StoryboardApplyResult.APPLIED,
                editor.addStoryboardEvent(StoryboardEventType.SCALE_X, event));
        editor.storyboardTimeline.select(StoryboardEventType.SCALE_X, event);
        openArrange(); clickAction(R.string.controls_line_increase);
        assertEquals(1, editor.getLineIndex());
        assertEquals(1, editor.getWorkbenchTab());
        assertNull(editor.storyboardTimeline.selected());
        assertTrue(editor.getChart().judgeLines.get(0).extended.scaleXEvents.contains(event));
    }

    @Test public void switchingTabsResetsTheScrollToTheImportantActions() {
        openArrange();
        reveal(editor.xyBindingEnabled ? R.string.xy_binding_button_on : R.string.xy_binding_button_off);
        draw();
        EditorChromeView.ButtonSpec create = editor.buttons.get(0);
        click(create.bounds.centerX(), create.bounds.centerY());
        assertNotNull(visibleButton(R.string.controls_tap));
        openArrange();
        assertNotNull(visibleButton(R.string.controls_beat_grid_increase));
    }
}
