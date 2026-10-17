package com.xpe.mobile.editor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import com.xpe.mobile.model.ChartDocument;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** IME resize can briefly leave no canvas between the fixed toolbar and footer. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33, 34}, qualifiers = "w1200dp-h700dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class EditorKeyboardResizeTest {
    private EditorView editor;
    private Bitmap illustration;

    @Before public void setup() throws Exception {
        editor = new EditorView(RuntimeEnvironment.getApplication());
        editor.setChart(ChartDocument.fromJson("{}"));
        illustration = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        editor.setBackgroundIllustration(illustration, null);
    }

    private void layout(int height) {
        editor.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        editor.layout(0, 0, 1200, height);
    }

    private static final class BackgroundCanvas extends Canvas {
        int illustrationDraws;
        @Override public void drawBitmap(Bitmap bitmap, Rect source, RectF destination, Paint paint) {
            assertTrue(destination.width() > 0);
            assertTrue(destination.height() > 0);
            illustrationDraws++;
        }
    }

    @Test public void dockedKeyboardCanCollapseBackgroundWithoutCrashingOrRecyclingIt() {
        for (int height : new int[]{700, 124, 123, 60, 0, 700}) {
            layout(height);
            BackgroundCanvas canvas = new BackgroundCanvas();
            editor.drawNoteBackground(canvas, 0, editor.toolbarHeight(), editor.editorSplitX(),
                    height - editor.bottomBarHeight());
            assertEquals(height > 124 ? 1 : 0, canvas.illustrationDraws);
            assertFalse(illustration.isRecycled());
            assertSame(illustration, editor.backgroundIllustration);
        }
    }

    @Test public void wholeEditorCanDrawAcrossKeyboardOpeningAndClosing() {
        Canvas canvas = new Canvas(Bitmap.createBitmap(1200, 700, Bitmap.Config.ARGB_8888));
        for (int height : new int[]{700, 240, 124, 123, 60, 0, 700}) {
            layout(height);
            editor.onDraw(canvas);
            assertFalse(illustration.isRecycled());
        }
    }
}
