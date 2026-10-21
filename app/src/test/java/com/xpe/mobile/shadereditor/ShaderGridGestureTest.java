package com.xpe.mobile.shadereditor;

import android.graphics.RectF;
import android.view.MotionEvent;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w800dp-h500dp-land-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public final class ShaderGridGestureTest extends ShaderEditorViewTestSupport {
    private RectF unselectedEffect() throws Exception {
        workbench.switchTab(3); layout(1200, 700);
        document.range(0, 0, .5);
        document.add("other", 2);
        shaders.select(1, false);
        editor.shaderEditorSeek(0);
        return drawFirstBlock();
    }

    @Test public void effectSelectionWaitsUntilTapRelease() throws Exception {
        RectF rect = unselectedEffect();
        touch(10, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY());
        assertEquals(1, document.selected);
        touch(30, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY());
        assertEquals(0, document.selected);
    }

    @Test public void swipesOverUnselectedEffectBodiesAndEndpointsKeepSelectionAndRanges() throws Exception {
        RectF rect = unselectedEffect();
        String before = document.json();
        for (boolean endpoint : new boolean[]{false, true}) for (float delta : new float[]{40, -40}) {
            editor.shaderEditorSeek(.25);
            rect = drawFirstBlock();
            float yy = endpoint ? rect.top : rect.centerY();
            double seconds = editor.shaderEditorSeconds();
            touch(10, MotionEvent.ACTION_DOWN, rect.centerX(), yy);
            assertEquals(1, document.selected);
            touch(30, MotionEvent.ACTION_MOVE, rect.centerX(), yy + delta);
            touch(50, MotionEvent.ACTION_UP, rect.centerX(), yy + delta);
            assertEquals(1, document.selected);
            assertEquals(before, document.json());
            assertNotEquals(seconds, editor.shaderEditorSeconds(), 0);
        }
    }

    @Test public void swipingASelectedBodyPansAndDoesNotEditIt() throws Exception {
        unselectedEffect(); shaders.select(0, false);
        editor.shaderEditorSeek(.25);
        RectF rect = drawFirstBlock(); String before = document.json();
        double seconds = editor.shaderEditorSeconds();
        touch(10, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY());
        touch(30, MotionEvent.ACTION_MOVE, rect.centerX(), rect.centerY() + 40);
        touch(50, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY() + 40);
        assertEquals(0, document.selected);
        assertTrue(editor.shaderEditorSeconds() > seconds);
        assertEquals(before, document.json());
    }

    @Test public void animationEventSwipePreservesItsPreviousSelectionAndTapSelectsOnRelease() throws Exception {
        workbench.switchTab(3); layout(1200, 700);
        JSONArray events = new JSONArray();
        for (double start : new double[]{0, 1}) events.put(new JSONObject()
                .put("startTime", document.timestamp(start)).put("endTime", document.timestamp(start + .5))
                .put("start", 0).put("end", 1).put("easingType", 1));
        document.value("power", events);
        shaders.page = 2; shaders.parameter = "power"; shaders.eventIndex = 1; shaders.buildForm();
        layout(1200, 700); editor.shaderEditorSeek(.25);
        RectF rect = drawFirstBlock(); String before = document.json();
        touch(10, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY());
        assertEquals(1, shaders.eventIndex);
        touch(30, MotionEvent.ACTION_MOVE, rect.centerX(), rect.centerY() + 40);
        touch(50, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY() + 40);
        assertEquals(1, shaders.eventIndex); assertEquals(before, document.json());
        editor.shaderEditorSeek(.25); rect = drawFirstBlock();
        touch(100, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY());
        assertEquals(1, shaders.eventIndex);
        touch(120, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY());
        assertEquals(0, shaders.eventIndex);
    }
}
