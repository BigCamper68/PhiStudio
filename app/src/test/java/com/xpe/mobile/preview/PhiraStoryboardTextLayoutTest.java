package com.xpe.mobile.preview;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PhiraStoryboardTextLayoutTest {
    @Test public void shorterRowsAreLeftAlignedInsideTheCenteredParagraph() throws Exception {
        PhiraFontMetrics font = PhiraHudFontOracleTest.font(false);
        PhiraFontMetrics.Layout block = font.storyboard("A\nLong second line", 76.8f);
        assertEquals(0, block.positions[0], 0);
        assertEquals(block.positions[0], block.positions[1], 0);
        assertEquals(block.left(0, .5f), -block.boundsX - block.width / 2, 0);
        assertTrue(block.width > font.storyboard("A", 76.8f).width);
        assertEquals(300, font.lineGap);
        assertEquals(76.8f * (1421f/1121f), block.glyphPositions[3], .0001f);
    }

    @Test public void scalesWithViewportWidthAcrossAspectsAndPreservesSignedLineTransforms() throws Exception {
        PhiraFontMetrics font = PhiraHudFontOracleTest.font(false);
        PhiraFontMetrics.Layout reference = font.storyboard("A\nТекст 中文", 76.8f);
        for (float[] viewport : new float[][]{{1920,1080},{2100,1400},{2520,1080},{1080,1920},{1024,576},{640,480}}) {
            float w = viewport[0], factor = w / 1920;
            PhiraFontMetrics.Layout actual = font.storyboard("A\nТекст 中文", PhiraRenderMetrics.textSize(w, 1));
            assertEquals(reference.width * factor, actual.width, .001f);
            assertEquals(reference.height * factor, actual.height, .001f);
            for (int i = 0; i < actual.glyphIds.length; i++) {
                float x = actual.left(0,.5f) + actual.glyphPositions[i*2];
                float y = actual.baseline(0,.5f) + actual.glyphPositions[i*2+1];
                assertEquals((reference.left(0,.5f)+reference.glyphPositions[i*2])*factor, x, .001f);
                assertEquals((reference.baseline(0,.5f)+reference.glyphPositions[i*2+1])*factor, y, .001f);
                double[] moved = PhiraHudLayout.transform(x,y,73,-29,90,-2,.5,0,0,0,0);
                assertArrayEquals(new double[]{73-y*.5,-29-x*2}, moved, .001);
            }
        }
    }

    @Test public void hudSingleLineAnchorsRemainUnchangedAfterMultilineSupport() throws Exception {
        PhiraFontMetrics font = PhiraHudFontOracleTest.font(false);
        PhiraFontMetrics.Layout hud = font.layout("Title", 40.96f, Float.POSITIVE_INFINITY);
        assertEquals(0, hud.boundsX, 0);
        assertEquals(0, hud.boundsY, 0);
        assertEquals(hud.ascent, hud.height, 0);
        assertEquals(100+hud.ascent, hud.baseline(100,0), .0001f);
        assertEquals(100, hud.baseline(100,1), .0001f);
        assertEquals(100-hud.width, hud.left(100,1), .0001f);
    }

    @Test public void leadingBlankLinesUsePrprCorrectionAndTrailingBreaksDoNotAddBlankInk() throws Exception {
        PhiraFontMetrics font = PhiraHudFontOracleTest.font(false);
        PhiraFontMetrics.Layout plain = font.storyboard("A",40.96f);
        PhiraFontMetrics.Layout trailing = font.storyboard("A\n\n",40.96f);
        assertEquals(plain.height,trailing.height,.0001f);
        assertEquals(plain.baseline(0,.5f),trailing.baseline(0,.5f),.0001f);
        PhiraFontMetrics.Layout leading = font.storyboard("\nA",40.96f);
        assertEquals(plain.height+3*40.96f*300/1121,leading.height,.0001f);
        assertTrue(leading.boundsY > 0);
        assertEquals(4,font.storyboard("A\\nB",40.96f).glyphIds.length);
        assertEquals(2,font.storyboard("A\r\nB",40.96f).glyphIds.length);
    }
}
