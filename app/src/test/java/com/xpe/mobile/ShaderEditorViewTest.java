package com.xpe.mobile;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.shadereditor.ShaderDocument;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w800dp-h500dp-land-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public final class ShaderEditorViewTest extends ShaderEditorViewTestSupport {
    @Test
    public void fourFooterButtonsFitInOrderAndOpenShaders() {
        LinearLayout row = (LinearLayout) ((ViewGroup) workbench.getChildAt(2)).getChildAt(0);
        for (int width : new int[] {800, 1200, 1920}) {
            layout(width, 700);
            assertEquals(4, row.getChildCount());
            String[] names = {"Events", "Storyboard", "Shaders", "Menu"};
            for (int i = 0; i < 4; i++) {
                assertEquals(names[i], ((Button) row.getChildAt(i)).getText().toString());
                assertTrue(row.getChildAt(i).getWidth() > 0);
                assertEquals(row.getWidth() / 4f, row.getChildAt(i).getWidth(), 1);
                assertTrue(row.getChildAt(i).getRight() <= row.getWidth());
            }
        }
        row.getChildAt(2).performClick();
        assertEquals(View.GONE, editor.getVisibility());
        assertEquals(View.VISIBLE, shaders.getVisibility());
        assertTrue(row.getChildAt(2).isSelected());
    }

    @Test
    public void toolbarAndCompactInspectorFitWithoutReducingControlSizes() {
        workbench.switchTab(3);
        layout(800, 360);
        View toolbar = shaders.getChildAt(0), panel = shaders.getChildAt(4);
        assertEquals(shaders.dp(48), toolbar.getHeight());
        assertEquals(shaders.dp(38), button("Play").getHeight());
        assertEquals(shaders.dp(58), button("Play").getWidth());
        assertEquals(0, button("Play").getLeft());
        assertEquals(shaders.dp(88), panel.getHeight());
        View seek = ((ViewGroup) toolbar).getChildAt(1);
        assertEquals(toolbar.getWidth(), seek.getRight());
        assertTrue(seek.getWidth() >= shaders.dp(160));
        android.graphics.Rect visible = new android.graphics.Rect();
        assertTrue(fields(shaders).get(0).getGlobalVisibleRect(visible));
        assertEquals(fields(shaders).get(0).getHeight(), visible.height());
        int before = shaders.timeline.getHeight();
        button("Expand").performClick();
        layout(800, 360);
        assertTrue(shaders.timeline.getHeight() < before);
        button("Compact").performClick();
        layout(800, 360);
        assertEquals(before, shaders.timeline.getHeight());
    }

    @Test
    public void shaderNavigationRemainsVisibleWhileKeyboardShrinksTheWindow() {
        workbench.switchTab(3);
        layout(800, 700);
        fields(shaders).get(0).requestFocus();
        layout(800, 240);
        assertEquals(View.VISIBLE, workbench.getChildAt(2).getVisibility());
        assertTrue(workbench.getChildAt(2).getHeight() > 0);
        LinearLayout row = (LinearLayout) ((ViewGroup) workbench.getChildAt(2)).getChildAt(0);
        row.getChildAt(3).performClick();
        assertEquals(View.GONE, shaders.getVisibility());
        assertEquals(2, editor.getWorkbenchTab());
    }

    @Test public void statusShowsThreeDecimalsAndUpdatesOnConsecutiveFrames() throws Exception {
        workbench.switchTab(3);layout(1200,700);
        java.lang.reflect.Field label=ShaderEditorView.class.getDeclaredField("status");label.setAccessible(true);
        TextView status=(TextView)label.get(shaders);
        View preview=shaders.getChildAt(1);
        Bitmap bitmap=Bitmap.createBitmap(preview.getWidth(),preview.getHeight(),Bitmap.Config.ARGB_8888);
        for(double seconds:new double[]{1.234567,1.345678,1.5}) {
            editor.shaderEditorSeek(seconds);preview.draw(new Canvas(bitmap));
            String expected=String.format(java.util.Locale.US,"%.3f",document.axis(seconds,true));
            assertTrue(status.getText().toString(),status.getText().toString().contains(expected));
        }
        bitmap.recycle();
    }
}
