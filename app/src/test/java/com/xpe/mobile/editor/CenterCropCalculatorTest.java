package com.xpe.mobile.editor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class CenterCropCalculatorTest {
    @Test
    public void cropsWideSourceHorizontallyWithoutDistortion() {
        CenterCropCalculator.Crop crop = CenterCropCalculator.calculate(
                1920, 1080, 700f, 800f);

        assertEquals(0, crop.top);
        assertEquals(1080, crop.bottom);
        assertEquals(945, crop.right - crop.left);
        assertEquals((1920 - 945) / 2, crop.left);
    }

    @Test
    public void cropsTallSourceVerticallyWithoutDistortion() {
        CenterCropCalculator.Crop crop = CenterCropCalculator.calculate(
                1000, 2000, 1600f, 900f);

        assertEquals(0, crop.left);
        assertEquals(1000, crop.right);
        assertEquals(563, crop.bottom - crop.top);
        assertEquals((2000 - 563) / 2, crop.top);
    }

    @Test
    public void keepsMatchingAspectRatioUncropped() {
        CenterCropCalculator.Crop crop = CenterCropCalculator.calculate(
                1600, 900, 800f, 450f);

        assertEquals(0, crop.left);
        assertEquals(0, crop.top);
        assertEquals(1600, crop.right);
        assertEquals(900, crop.bottom);
    }
}
