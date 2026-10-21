package com.xpe.mobile.editor;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.widget.*;
import com.xpe.mobile.R;
import com.xpe.mobile.model.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowPath;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w800dp-h500dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class EasingSelectorUiTest extends EditorToolsTestSupport {
    @Test public void everyDropdownEntryHasANameAndCurveAndAll29ChoicesHaveFiniteVisibleGraphs() {
        LinearLayout parent = new LinearLayout(activity);
        EasingSelector picker = EasingSelector.addTo(parent, "Easing", 1);
        for (int type = 1; type <= 29; type++) {
            String label = picker.spinner.getItemAtPosition(type - 1).toString();
            assertFalse(label.matches(".*[0-9].*"));
            View dropdown = picker.spinner.getAdapter().getDropDownView(type - 1, null, parent);
            assertEquals(1, children(dropdown, EasingPreviewView.class).size());
            picker.spinner.setSelection(type - 1); Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(type, picker.type());
            List<ShadowPath.Point> points = curve(picker.preview);
            assertTrue(points.size() >= 32);
            for (ShadowPath.Point point : points) {
                assertTrue(Float.isFinite(point.getX())); assertTrue(Float.isFinite(point.getY()));
                assertTrue(point.getX() >= 0 && point.getX() <= 300);
                assertTrue(point.getY() >= 0 && point.getY() <= 148);
            }
        }
    }

    @Test public void overshootIsVisibleAndChangingAWindowUpdatesTheGraph() {
        EasingSelector picker = new EasingSelector(activity, "Curve", 20); // Out Back
        List<ShadowPath.Point> full = curve(picker.preview);
        assertTrue(full.stream().anyMatch(point -> point.getX() > full.get(full.size() - 1).getX() + 2));
        EditText left = new EditText(activity), right = new EditText(activity);
        left.setText("0"); right.setText("1"); picker.bindWindow(left, right, () -> true);
        left.setText("0.25"); right.setText("0.75");
        List<ShadowPath.Point> window = curve(picker.preview);
        assertNotEquals(full.get(full.size() / 2).getX(), window.get(window.size() / 2).getX(), .01);
    }

    @Test public void propertiesAndEveryGenerationToolUseTheSharedPickerWithAGraph() {
        dialogs.showEventProperties(source); settle();
        assertEquals(1, children(page(), EasingSelector.class).size());
        assertTrue(workbench.back());
        ExtendedLineEvents.NumericEvent storyboard = new ExtendedLineEvents.NumericEvent();
        storyboard.startTime = new BeatTime(1, 0, 1); storyboard.endTime = new BeatTime(2, 0, 1);
        editor.addStoryboardEvent(StoryboardEventType.SCALE_X, storyboard);
        dialogs.showStoryboardProperties(StoryboardEventType.SCALE_X, storyboard, false); settle();
        assertEquals(1, children(page(), EasingSelector.class).size());
        assertTrue(workbench.back());
        dialogs.showComplexMove(); settle();
        assertEquals(2, children(page(), EasingSelector.class).size());
        assertTrue(workbench.back());
        Note first = new Note(), last = new Note(); last.startTime = new BeatTime(3, 0, 1);
        editor.getCurrentJudgeLine().notes.add(first); editor.getCurrentJudgeLine().notes.add(last);
        dialogs.showCurveNotes(); settle();
        assertEquals(1, children(page(), EasingSelector.class).size());
    }

    @Test public void eventPropertiesApplyNamedCurve29AndUndoRestoresTheOriginalCurve() {
        dialogs.showEventProperties(source); settle();
        spinner(R.string.field_event_easing_type).setSelection(28); settle();
        button(R.string.action_apply).performClick();
        assertEquals(29, source.easingType);
        assertEquals(Easing.apply(29, .3), source.easedProgressAt(.3), 1e-9);
        editor.performUndo(); assertEquals(19, source.easingType);
    }

    @Test public void batchProgressionUsesANamedCurveAndBatchEasingHasNoNumericIds() {
        source.bezier = true; source.easingLeft = .2; source.easingRight = .8;
        dialogs.showAdvancedBatchEdit(); settle();
        assertEquals(2, children(page(), EasingSelector.class).size());
        spinner(R.string.batch_data_type).setSelection(BatchEditOperation.EventField.EASING_TYPE.ordinal()); settle();
        assertEquals(0, children(page(), EditText.class).stream().filter(View::isShown).count());
        EasingSelector picker = children(page(), EasingSelector.class).stream().filter(View::isShown).findFirst().orElseThrow();
        picker.spinner.setSelection(28); settle();
        button(R.string.action_apply).performClick();
        assertEquals(29, source.easingType);
        assertFalse(source.bezier); assertEquals(0, source.easingLeft, 0); assertEquals(1, source.easingRight, 0);
        assertEquals(Easing.apply(29, .3), source.easedProgressAt(.3), 1e-9);
        editor.performUndo(); assertEquals(19, source.easingType);
        assertTrue(source.bezier); assertEquals(.2, source.easingLeft, 0);
    }

    @Test public void batchValueProgressionStillAppliesTheSelectedCurveAndHiddenCurveModeDoesNotLoseValues() {
        LineEvent second = source.copy(); second.startTime = new BeatTime(3, 0, 1); second.endTime = new BeatTime(4, 0, 1);
        editor.getCurrentJudgeLine().layer(0).events(source.type).add(second);
        try {
            java.lang.reflect.Field field = Class.forName("com.xpe.mobile.editor.EditorSurfaceView").getDeclaredField("selectedEvents");
            field.setAccessible(true);
            @SuppressWarnings("unchecked") Set<LineEvent> selected = (Set<LineEvent>) field.get(editor);
            selected.add(second);
        } catch (Exception error) { throw new AssertionError(error); }
        dialogs.showAdvancedBatchEdit(); settle();
        field(R.string.batch_lower_bound).setText("10"); field(R.string.batch_upper_bound).setText("100");
        spinner(R.string.batch_easing).setSelection(4);
        spinner(R.string.batch_edit_mode).setSelection(1); // To
        spinner(R.string.batch_data_type).setSelection(2); settle();
        spinner(R.string.batch_data_type).setSelection(0); settle();
        assertEquals("100", field(R.string.batch_upper_bound).getText().toString());
        button(R.string.action_apply).performClick();
        assertEquals(10, source.start, 0); assertEquals(100, second.start, 0);
    }

    @Test public void batchSpeedEasingShowsTheFixedLinearCurve() {
        dialogs.showAdvancedBatchEdit(); settle();
        spinner(R.string.batch_data_type).setSelection(BatchEditOperation.EventField.EASING_TYPE.ordinal()); settle();
        spinner(R.string.batch_event_type).setSelection(EventType.SPEED.ordinal()); settle();
        EasingSelector picker = children(page(), EasingSelector.class).stream().filter(View::isShown).findFirst().orElseThrow();
        assertFalse(picker.spinner.isEnabled()); assertEquals(1, picker.type());
        assertTrue(pageText().contains("fixed to Linear"));
    }

    private List<ShadowPath.Point> curve(EasingPreviewView preview) {
        preview.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(148, View.MeasureSpec.EXACTLY));
        preview.layout(0, 0, 300, 148);
        List<ShadowPath.Point> points = new ArrayList<>();
        preview.draw(new Canvas() {
            @Override public void drawPath(Path path, Paint paint) { points.addAll(Shadows.shadowOf(path).getPoints()); }
        });
        return points;
    }
}
