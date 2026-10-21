package com.xpe.mobile.editor;

import android.graphics.Rect;
import android.view.View;
import android.widget.*;
import com.xpe.mobile.R;
import com.xpe.mobile.model.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w800dp-h500dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class EventCloneUiTest extends EditorToolsTestSupport {
    @Test public void initialFormDefaultsToCopyAndAsksForNamedDestinationsWithoutTuples() {
        dialogs.showEventClone(); settle();
        assertEquals(0, spinner(R.string.clone_source_mode).getSelectedItemPosition());
        assertFalse(button(R.string.clone_copy_action).isEnabled());
        assertTrue(pageText().contains("Choose the lines"));
        assertFalse(pageText().contains("U.L.E.D"));
        assertFalse(pageText().contains("indices separated"));
        assertEquals(0, children(page(), EditText.class).stream().filter(View::isShown).count());
        assertFalse(editor.isChartDirty());
    }

    @Test public void destinationOrderAndBeatDelayArePreviewedAndAppliedInOneUndo() {
        dialogs.showEventClone(); settle(); choose(2, 1);
        spinner(R.string.clone_timing_mode).setSelection(1); settle();
        field(R.string.clone_delay).setText("0:1/2"); settle();
        String description = activity.getString(R.string.clone_move_later,
                activity.getString(R.string.clone_line_label, 2, "Destination 2"));
        children(page(), Button.class).stream().filter(b -> description.equals(b.getContentDescription()))
                .findFirst().orElseThrow().performClick(); settle();
        assertTrue(pageText().contains("shift +0:1/2"));
        assertTrue(editor.getChart().judgeLines.get(1).layer(0).events(source.type).isEmpty());
        assertFalse(editor.isChartDirty());
        button(R.string.clone_copy_action).performClick();
        assertSame(source, copyOn(0));
        assertEquals(1, copyOn(1).startTime.toDouble(), 0);
        assertEquals(1.5, copyOn(2).startTime.toDouble(), 0);
        assertEquals(19, copyOn(1).easingType);
        editor.performUndo();
        assertSame(source, copyOn(0));
        assertTrue(editor.getChart().judgeLines.get(1).layer(0).events(source.type).isEmpty());
        assertTrue(editor.getChart().judgeLines.get(2).layer(0).events(source.type).isEmpty());
        editor.performRedo(); assertEquals(1.5, copyOn(2).startTime.toDouble(), 0);
    }

    @Test public void moveIsExplicitAndUndoRestoresTheOriginalIdentity() {
        dialogs.showEventClone(); settle(); choose(1);
        spinner(R.string.clone_source_mode).setSelection(1); settle();
        assertTrue(pageText().contains("will be removed"));
        button(R.string.clone_move_action).performClick();
        assertTrue(editor.getChart().judgeLines.get(0).layer(0).events(source.type).isEmpty());
        assertNotSame(source, copyOn(1));
        editor.performUndo(); assertSame(source, copyOn(0));
    }

    @Test public void negativeTimeShiftsCanPlaceFollowingCopiesEarlierWithoutGoingBeforeBeatZero() {
        dialogs.showEventClone(); settle(); choose(1, 2);
        spinner(R.string.clone_timing_mode).setSelection(1); settle();
        field(R.string.clone_delay).setText("-0.5"); settle();
        assertTrue(button(R.string.clone_copy_action).isEnabled());
        button(R.string.clone_copy_action).performClick();
        assertEquals(1, copyOn(1).startTime.toDouble(), 0);
        assertEquals(.5, copyOn(2).startTime.toDouble(), 0);
    }

    @Test public void gradualOffsetsUseTheNamedCurveAcrossDestinationLines() {
        dialogs.showEventClone(); settle(); choose(1, 2, 3);
        check(R.string.clone_customize_values).setChecked(true); settle();
        labeled(text(R.string.event_type_move_x), Spinner.class).setSelection(2); settle();
        field(R.string.clone_first_offset).setText("0"); field(R.string.clone_last_offset).setText("100");
        spinner(R.string.clone_offset_curve).setSelection(4); settle(); // In Quad
        assertTrue(pageText().contains("X +25"));
        button(R.string.clone_copy_action).performClick();
        assertEquals(10, copyOn(1).start, 0);
        assertEquals(35, copyOn(2).start, 1e-9);
        assertEquals(120, copyOn(3).end, 1e-9);
        assertEquals(19, copyOn(2).easingType); // Offset progression never changes the source curve.
    }

    @Test public void disabledOptionalFieldsAreIgnoredAndRandomPreviewMatchesAppliedValues() {
        dialogs.showEventClone(); settle(); choose(1, 2, 3);
        check(R.string.clone_customize_values).setChecked(true); settle();
        labeled(text(R.string.event_type_move_x), Spinner.class).setSelection(1); settle();
        field(R.string.clone_fixed_offset).setText("50");
        check(R.string.clone_variation_toggle).setChecked(true);
        field(R.string.clone_multipliers).setText("1 -1"); field(R.string.clone_variation).setText("3"); settle();
        String preview = pageText();
        button(R.string.clone_copy_action).performClick();
        for (int index = 1; index <= 3; index++) {
            double offset = copyOn(index).start - source.start;
            assertTrue(preview.contains("X " + (offset > 0 ? "+" : "") + String.format(Locale.ROOT, "%.4g", offset)));
        }
    }

    @Test public void overlapAndInvalidDelaysBlockMutationAndDisablingCustomizationKeepsValues() {
        dialogs.showEventClone(); settle(); choose(0);
        assertFalse(button(R.string.clone_copy_action).isEnabled());
        assertTrue(pageText().contains("overlap"));
        button(R.string.clone_choose_lines).performClick(); settle();
        button(R.string.controls_clear).performClick();
        children(page(), CheckBox.class).stream().filter(c -> c.getText().toString().startsWith("Line 1 ·"))
                .findFirst().orElseThrow().setChecked(true);
        button(R.string.clone_use_lines).performClick(); settle();
        spinner(R.string.clone_timing_mode).setSelection(1); settle();
        field(R.string.clone_delay).setText("bad"); settle();
        assertFalse(button(R.string.clone_copy_action).isEnabled());
        spinner(R.string.clone_timing_mode).setSelection(0); settle();
        check(R.string.clone_customize_values).setChecked(true); settle();
        labeled(text(R.string.event_type_move_x), Spinner.class).setSelection(1); settle();
        field(R.string.clone_fixed_offset).setText("bad"); settle();
        assertFalse(button(R.string.clone_copy_action).isEnabled());
        check(R.string.clone_customize_values).setChecked(false); settle();
        assertTrue(button(R.string.clone_copy_action).isEnabled());
        button(R.string.clone_copy_action).performClick(); assertEquals(source.start, copyOn(1).start, 0);
    }

    @Test public void cancellingDestinationChangesKeepsTheDraftAndChangedSourcesCannotBeCloned() throws Exception {
        dialogs.showEventClone(); settle(); choose(1);
        button(R.string.clone_choose_lines).performClick(); settle();
        button(R.string.controls_clear).performClick();
        button(R.string.action_cancel).performClick(); settle();
        assertTrue(button(R.string.clone_copy_action).isEnabled());
        select(null);
        button(R.string.clone_copy_action).performClick();
        assertTrue(pageText().contains("source selection changed"));
        assertSame(source, copyOn(0));
        assertTrue(editor.getChart().judgeLines.get(1).layer(0).events(source.type).isEmpty());
    }

    @Test public void lineSearchAndPaginationKeepCheckboxSelections() {
        for (int i = 4; i < 25; i++) {
            JudgeLine line = new JudgeLine(); line.name = "Destination " + i;
            line.eventLayers.clear(); line.eventLayers.add(new EventLayer()); editor.getChart().judgeLines.add(line);
        }
        dialogs.showEventClone(); settle();
        button(R.string.clone_choose_lines).performClick(); settle();
        assertEquals(12, children(page(), CheckBox.class).size());
        button(R.string.clone_next).performClick(); settle();
        children(page(), CheckBox.class).stream().filter(c -> c.getText().toString().startsWith("Line 14 ·"))
                .findFirst().orElseThrow().setChecked(true);
        field(R.string.clone_search_lines).setText("Destination 2"); settle();
        children(page(), CheckBox.class).stream().filter(c -> c.getText().toString().startsWith("Line 2 ·"))
                .findFirst().orElseThrow().setChecked(true);
        field(R.string.clone_search_lines).setText(""); settle();
        button(R.string.clone_next).performClick(); settle();
        assertTrue(children(page(), CheckBox.class).stream().filter(c -> c.getText().toString().startsWith("Line 14 ·"))
                .findFirst().orElseThrow().isChecked());
        button(R.string.clone_use_lines).performClick(); settle();
        button(R.string.clone_copy_action).performClick();
        assertEquals(1, copyOn(14).startTime.toDouble(), 0); assertEquals(1, copyOn(2).startTime.toDouble(), 0);
    }

    @Test @Config(qualifiers = "w640dp-h240dp-land-mdpi")
    public void controlsAndCurvePreviewRemainReachableOnNarrowLandscapeScreens() {
        assertControlsReachable(640, 240);
    }

    @Test @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    public void controlsAndCurvePreviewRemainReachableOnStandardLandscapeScreens() {
        assertControlsReachable(800, 360);
    }

    @Test @Config(qualifiers = "w1200dp-h700dp-land-mdpi")
    public void controlsAndCurvePreviewRemainReachableOnWideLandscapeScreens() {
        assertControlsReachable(1200, 700);
    }

    private void assertControlsReachable(int width, int height) {
        dialogs.showEventClone(); settle(); choose(1, 2, 3);
        check(R.string.clone_customize_values).setChecked(true); settle();
        labeled(text(R.string.event_type_move_x), Spinner.class).setSelection(2); settle();
        for (int[] size : new int[][]{{width, height}}) {
            layout(width, height);
            List<View> controls = new ArrayList<>();
            controls.add(button(R.string.clone_copy_action)); controls.add(button(R.string.clone_choose_lines));
            controls.add(spinner(R.string.clone_offset_curve));
            controls.add(children(page(), EasingSelector.class).get(0).preview);
            for (View control : controls) {
                control.requestRectangleOnScreen(new Rect(0, 0, control.getWidth(), control.getHeight()), true);
                Rect visible = new Rect();
                StringBuilder geometry = new StringBuilder("size=" + Arrays.toString(size));
                for (View ancestor = control; ancestor != null;
                        ancestor = ancestor.getParent() instanceof View ? (View) ancestor.getParent() : null)
                    geometry.append("\n").append(ancestor.getClass().getSimpleName()).append(' ')
                            .append(ancestor.getWidth()).append('x').append(ancestor.getHeight())
                            .append(" top=").append(ancestor.getTop()).append(" scroll=").append(ancestor.getScrollY());
                assertTrue(geometry.toString(), control.getGlobalVisibleRect(visible));
                assertTrue(control.getWidth() > 0);
                assertTrue(visible.width() >= control.getWidth() - 1);
                assertTrue(visible.height() >= Math.min(24, control.getHeight()));
            }
        }
    }
}
