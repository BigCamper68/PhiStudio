package com.xpe.mobile.shadereditor;

import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.*;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w800dp-h500dp-land-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public final class ShaderFormUiTest extends ShaderEditorViewTestSupport {
    @Test
    public void previewIsLeftTimelineIsRightAndParametersAreBelowBoth() {
        workbench.switchTab(3);
        for (int[] size : new int[][] {{800, 360}, {1200, 700}, {1920, 1080}, {800, 240}}) {
            layout(size[0], size[1]);
            View preview = shaders.getChildAt(1), timeline = shaders.timeline,
                 bottom = shaders.getChildAt(4);
            assertEquals(preview.getRight(), timeline.getLeft());
            assertEquals(preview.getTop(), timeline.getTop());
            assertEquals(preview.getBottom(), bottom.getTop());
            assertEquals(timeline.getBottom(), bottom.getTop());
            assertEquals(size[0], bottom.getWidth());
            assertEquals(shaders.getBottom(), workbench.getChildAt(2).getTop());
            assertTrue(timeline.getHeight() > 0);
        }
    }

    @Test
    public void effectRangeApplyIsUndoableAndInvalidInputCannotChangeIt() {
        workbench.switchTab(3);
        fields(shaders).get(0).setText("2");
        fields(shaders).get(1).setText("8");
        button("Apply").performClick();
        assertEquals(1, document.start(0), 0);
        assertEquals(4, document.end(0), 0);
        assertTrue(updates > 0);
        fields(shaders).get(0).setText("99");
        button("Apply").performClick();
        assertEquals(1, document.start(0), 0);
        button("Undo").performClick();
        assertEquals(0, document.start(0), 0);
        assertEquals(2, document.end(0), 0);
    }

    @Test
    public void animationUsesAllNamedEasingsAndAppliesValues() {
        workbench.switchTab(3);
        button("Parameters").performClick();
        button("Animate").performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        List<Spinner> spinners = new ArrayList<>();
        collectSpinners(shaders, spinners);
        Spinner easing = spinners.stream()
                             .filter(s -> s.getAdapter().getCount() == 29)
                             .findFirst()
                             .orElseThrow();
        assertEquals(29, easing.getAdapter().getCount());
        List<EditText> values = fields(shaders);
        values.get(2).setText("0");
        values.get(3).setText("2");
        easing.setSelection(4);
        button("Apply").performClick();
        try {
            assertEquals(.5,
                document.compile(document.json()).effects.get(0).uniforms.get("power").at(1)[0],
                1e-6);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    public void bodySelectsWithoutMovingAndHandlesResizeWithOneUndo() throws Exception {
        workbench.switchTab(3);
        layout(1200, 700);
        document.range(0, 1, 2);
        editor.shaderEditorSeek(1);
        android.graphics.RectF rect = drawFirstBlock();
        float x = rect.centerX(), y = rect.centerY();
        String before = document.json();
        touch(10, MotionEvent.ACTION_DOWN, x, y);
        touch(30, MotionEvent.ACTION_MOVE, x, y - scale());
        touch(50, MotionEvent.ACTION_UP, x, y - scale());
        assertEquals(before, document.json());
        rect = drawFirstBlock();
        y = rect.top;
        touch(100, MotionEvent.ACTION_DOWN, x, y);
        touch(130, MotionEvent.ACTION_MOVE, x, y - scale() / 2);
        touch(150, MotionEvent.ACTION_MOVE, x, y - scale());
        touch(170, MotionEvent.ACTION_UP, x, y - scale() * 1.5f);
        assertEquals(1, document.start(0), 1e-6);
        assertEquals(2.75, document.end(0), 1e-6);
        document.undo();
        assertEquals(before, document.json());
    }

    @Test
    public void canceledHandleGestureRestoresRange() throws Exception {
        workbench.switchTab(3);
        layout(1200, 700);
        document.range(0, 1, 2);
        editor.shaderEditorSeek(1);
        android.graphics.RectF rect = drawFirstBlock();
        String before = document.json();
        touch(10, MotionEvent.ACTION_DOWN, rect.centerX(), rect.top);
        touch(30, MotionEvent.ACTION_MOVE, rect.centerX(), rect.top - scale());
        touch(50, MotionEvent.ACTION_CANCEL, rect.centerX(), rect.top - scale());
        assertEquals(before, document.json());
    }

    @Test
    public void ordinaryScaleBaselineAndSettingsApplyToShaderTimeline() throws Exception {
        workbench.switchTab(3);
        layout(1200, 700);
        editor.scaleTimeline(1.5f);
        document.range(0, 1, 2);
        editor.shaderEditorSeek(1);
        android.graphics.RectF rect = drawFirstBlock();
        assertEquals(shaders.timeline.getHeight() - shaders.dp(28), rect.bottom, .01);
        assertEquals(2 * editor.timelineScale(), rect.height(), .01);
        com.xpe.mobile.config.EditorSettings settings = editor.getSettings();
        settings.timelineScrollSpeed = 2;
        editor.applySettings(settings);
        // Gutter belongs to empty timeline, regardless of the number of effect
        // lanes.
        touch(10, MotionEvent.ACTION_DOWN, 5, 100);
        touch(30, MotionEvent.ACTION_MOVE, 5, 100 + scale());
        touch(50, MotionEvent.ACTION_UP, 5, 100 + scale());
        assertEquals(2, editor.shaderEditorSeconds(), .001);
    }

    @Test
    public void leavingDuringUncommittedGestureRollsBackAndBackReturnsToPreviousTab() {
        workbench.switchTab(1);
        workbench.switchTab(3);
        String snapshot = document.json();
        document.beginGesture();
        document.range(0, 1, 4);
        assertTrue(workbench.back());
        assertEquals(snapshot, document.json());
        assertEquals(1, editor.getWorkbenchTab());
        assertEquals(View.GONE, shaders.getVisibility());
    }
}
