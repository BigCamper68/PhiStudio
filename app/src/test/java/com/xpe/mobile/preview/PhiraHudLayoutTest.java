package com.xpe.mobile.preview;

import org.junit.Test;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.Assert.*;

public final class PhiraHudLayoutTest {
    @Test public void ellipsisMatchesRustReportedBoundsAndDrawnUnicodePrefix() throws Exception {
        PhiraFontMetrics font = PhiraHudFontOracleTest.font(false);
        try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                getClass().getResourceAsStream("/phira-hud-ellipsis-oracle.tsv"), java.nio.charset.StandardCharsets.UTF_8))) {
            String row;
            int checked = 0;
            while ((row = in.readLine()) != null) {
                String[] f = row.split("\t", -1);
                PhiraFontMetrics.Layout result = font.layout(f[2], Float.parseFloat(f[0]), Float.parseFloat(f[1]));
                assertEquals(Float.parseFloat(f[3]), result.width, .0001f);
                StringBuilder drawn = new StringBuilder();
                for (int cp : result.codepoints) drawn.appendCodePoint(cp);
                assertEquals(f[4], drawn.toString());
                assertEquals(result.codepoints.length, result.glyphIds.length);
                for (int i = 0; i < result.positions.length; i++) {
                    assertEquals(result.positions[i], result.glyphPositions[i * 2], 0);
                    assertEquals(0, result.glyphPositions[i * 2 + 1], 0);
                }
                checked++;
            }
            assertEquals(12, checked);
        }
    }
    @Test public void bundledFontsAreByteIdenticalToPhira() throws Exception {
        assertHash("phigros.ttf", "15d2d38a1041a99148ae9940c88833caea28291b311cbc61126325c9cca02dd2");
        assertHash("phira.ttf", "64faa1f96a8293f64afde3e1d1ab7724485998836a7b8218865951eb193f7975");
    }

    private static void assertHash(String file, String expected) throws Exception {
        assertEquals(expected, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(PhiraHudFontOracleTest.asset(file).toPath()))));
    }

    @Test public void normalHudUsesLogicalAscentAndAnchorAcrossAspectRatios() throws Exception {
        PhiraFontMetrics numbers = PhiraHudFontOracleTest.font(true), ui = PhiraHudFontOracleTest.font(false);
        for (float[] size : new float[][]{{1920,1080},{2100,1400},{2520,1080},{1080,1920},{1024,576},{640,480}}) {
            float w = size[0], h = size[1], ox = 43, oy = 29;
            PhiraRenderMetrics.Hud m = PhiraRenderMetrics.hud(w, h);
            PhiraFontMetrics.Layout s = numbers.layout("0123456", m.scoreTextSize, Float.POSITIVE_INFINITY);
            PhiraHudLayout.Box score = PhiraHudLayout.score(s, ox + w - m.marginX, oy + m.scoreTop, false);
            assertEquals(ox + w * .985f, score.x, .001f);
            assertEquals(oy + h * .022f, score.top(), .001f);
            assertEquals(score.x - s.width/2, score.scaleX, .001f);
            assertEquals(score.y + s.ascent/2, score.scaleY, .001f);
            assertEquals(score.y, score.rotationY, .001f);
            PhiraFontMetrics.Layout n = numbers.layout("123", m.comboTextSize, Float.POSITIVE_INFINITY);
            PhiraHudLayout.Box number = PhiraHudLayout.comboNumber(n, ox+w/2, oy+m.comboTop, 0, false);
            PhiraFontMetrics.Layout label = numbers.layout("AUTOPLAY", m.comboLabelTextSize, Float.POSITIVE_INFINITY);
            PhiraHudLayout.Box combo = PhiraHudLayout.comboLabel(label, number, m.comboLabelGap, n.ascent, 0, false);
            assertEquals(number.bottom() + w * .005f, combo.top(), .001f);
            assertEquals(combo.y + n.ascent * .2f, combo.rotationY, .001f);
            PhiraFontMetrics.Layout title = ui.layout("Тест чарта", m.bottomTextSize, Float.POSITIVE_INFINITY);
            PhiraHudLayout.Box bottom = PhiraHudLayout.bottom(title, title, ox+m.marginX,
                    oy+m.bottomTextBottom, false, false);
            // With prpr's anchor(0, 1), the baseline equals bt, without subtracting descent.
            assertEquals(oy+h*.972f, bottom.top()+title.ascent, .001f);
            assertEquals(ox+w*.015f + title.width/2, bottom.scaleX, .001f);
        }
    }

    @Test public void fixedModeUsesDefaultFontMeasurementButPgrDrawing() throws Exception {
        PhiraFontMetrics numbers = PhiraHudFontOracleTest.font(true), ui = PhiraHudFontOracleTest.font(false);
        PhiraFontMetrics.Layout num = numbers.layout("123", 76.8f, Float.POSITIVE_INFINITY);
        float uiAscent = ui.baseline(76.8f);
        PhiraHudLayout.Box number = PhiraHudLayout.comboNumber(num, 960, 21.6f, uiAscent, true);
        assertEquals(21.6f + (uiAscent - num.ascent)/2, number.top(), .0001f);
        assertEquals(number.y, number.rotationY, 0);
        PhiraFontMetrics.Layout label = numbers.layout("AUTOPLAY", 30.72f, Float.POSITIVE_INFINITY);
        PhiraHudLayout.Box combo = PhiraHudLayout.comboLabel(label, number, 9.6f, num.ascent, ui.baseline(30.72f), true);
        assertEquals(number.bottom()+9.6f+(ui.baseline(30.72f)-label.ascent)/2, combo.top(), .0001f);
        PhiraHudLayout.Box score = PhiraHudLayout.score(num, 1891.2f, 23.76f, true);
        assertEquals(score.rotationX, score.scaleX, 0);
        assertEquals(score.rotationY, score.scaleY, 0);
    }

    @Test public void attachedUiUsesTranslationAfterRotationAfterScaleWithDistinctPivots() {
        double[] point = PhiraHudLayout.transform(90,20,7,-9,90,2,-1,100,10,80,30);
        // S: (100,40); R around (100,10): (70,10); then T: (77,1).
        assertArrayEquals(new double[]{77,1}, point, 1e-8);
        assertArrayEquals(new double[]{15,17}, PhiraHudLayout.transform(10,20,5,-3,0,1,1,80,30,100,10), 1e-8);
    }

    @Test public void narrowTitleIsEmptyAndUnicodeEllipsisNeverSplitsSurrogates() throws Exception {
        PhiraFontMetrics font = PhiraHudFontOracleTest.font(false);
        assertEquals(0, font.layout("Title", 40, 0).codepoints.length);
        PhiraFontMetrics.Layout clipped = font.layout("中文Тестチャート title", 40, 100);
        assertTrue(clipped.width <= 100);
        assertEquals('…', clipped.codepoints[clipped.codepoints.length - 1]);
        for (int cp : clipped.codepoints) assertFalse(cp >= 0xd800 && cp <= 0xdfff);
        PhiraFontMetrics.Layout full = font.layout("Very long level name", 40, Float.POSITIVE_INFINITY);
        assertTrue(full.width > 100);
    }
}
