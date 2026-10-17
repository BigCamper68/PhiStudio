package com.xpe.mobile.editor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class GraphProportionsTest {
    @Test
    public void complexMoveCircleIsRoundInWideAndTallViews() throws Exception {
        List<ComplexMoveGenerator.PathPoint> path = new ArrayList<>();
        for (int i = 0; i <= 360; i++) {
            double angle = Math.toRadians(i);
            path.add(new ComplexMoveGenerator.PathPoint(300 * Math.cos(angle), 300 * Math.sin(angle)));
        }
        for (int[] size : new int[][]{{1000, 240}, {360, 600}, {600, 400}}) {
            ComplexMovePreviewView view = new ComplexMovePreviewView(RuntimeEnvironment.getApplication());
            view.setPath(path);
            Bitmap image = draw(view, size[0], size[1]);
            int left = size[0], top = size[1], right = 0, bottom = 0, count = 0;
            for (int y = 0; y < size[1]; y++) {
                for (int x = 0; x < size[0]; x++) {
                    int color = image.getPixel(x, y);
                    if (Math.max(Color.red(color), Color.green(color)) < 200
                            || Color.blue(color) > 100) continue;
                    left = Math.min(left, x); right = Math.max(right, x);
                    top = Math.min(top, y); bottom = Math.max(bottom, y);
                    count++;
                }
            }
            assertTrue("path must be visible", count > 100);
            assertEquals(right - left, bottom - top, 2);
            if (size[0] == 1000) save(image, "complex-move-circle.png");
            image.recycle();
        }
    }

    @Test
    public void circularEasingUsesEqualUnitsInWideAndTallViews() throws Exception {
        for (int[] size : new int[][]{{1000, 180}, {250, 450}}) {
            EasingPreviewView view = new EasingPreviewView(RuntimeEnvironment.getApplication());
            view.setCurve(18, 0, 1, false, 0, 0, 1, 1); // Out Circ: quarter circle.
            Bitmap image = draw(view, size[0], size[1]);
            double radius = Math.min(size[0], size[1]) - 28.0;
            double left = (size[0] - radius) / 2;
            double top = (size[1] - radius) / 2;
            int count = 0;
            for (int y = 0; y < size[1]; y++) {
                for (int x = 0; x < size[0]; x++) {
                    int color = image.getPixel(x, y);
                    if (Color.red(color) < 220 || Color.green(color) < 110
                            || Color.blue(color) > 80) continue;
                    assertEquals("circular easing pixel radius", radius,
                            Math.hypot(x + 0.5 - left, y + 0.5 - top), 3.5);
                    count++;
                }
            }
            assertTrue("easing must be visible", count > 100);
            if (size[0] == 1000) save(image, "circular-easing.png");
            image.recycle();
        }
    }

    private static Bitmap draw(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(image));
        return image;
    }

    private static void save(Bitmap image, String name) throws Exception {
        File directory = new File("build/reports/preview-proportions");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
    }
}
