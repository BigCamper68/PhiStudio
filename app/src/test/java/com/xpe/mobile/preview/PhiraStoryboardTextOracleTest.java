package com.xpe.mobile.preview;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import static org.junit.Assert.*;

/** Independent Rust glyph_brush_layout/ab_glyph multiline oracle, centered like prpr. */
@RunWith(Parameterized.class)
public final class PhiraStoryboardTextOracleTest {
    @Parameterized.Parameters(name = "{0}px case {1}")
    public static Collection<Object[]> cases() throws Exception {
        List<Object[]> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                PhiraStoryboardTextOracleTest.class.getResourceAsStream("/phira-storyboard-text-oracle.tsv"), StandardCharsets.UTF_8))) {
            String row;
            while ((row = reader.readLine()) != null) {
                String[] fields = row.split("\t", -1);
                result.add(new Object[]{Float.parseFloat(fields[0]), fields[1], fields});
            }
        }
        return result;
    }

    private final float scale;
    private final String hex;
    private final String[] oracle;
    public PhiraStoryboardTextOracleTest(float scale, String hex, String[] oracle) {
        this.scale = scale; this.hex = hex; this.oracle = oracle;
    }

    @Test public void matchesParagraphBoundsGlyphIdsAndEveryCenteredXY() throws Exception {
        String text = new String(HexFormat.of().parseHex(hex), StandardCharsets.UTF_8);
        PhiraFontMetrics.Layout layout = PhiraHudFontOracleTest.font(false).storyboard(text, scale);
        assertEquals(Float.parseFloat(oracle[2]), layout.boundsX, .001f);
        assertEquals(Float.parseFloat(oracle[3]), layout.boundsY, .001f);
        assertEquals(Float.parseFloat(oracle[4]), layout.width, .001f);
        assertEquals(Float.parseFloat(oracle[5]), layout.height, .001f);
        String[] ids = oracle[6].isEmpty() ? new String[0] : oracle[6].split(",");
        String[] positions = oracle[7].isEmpty() ? new String[0] : oracle[7].split(",");
        assertEquals(ids.length, layout.glyphIds.length);
        assertEquals(ids.length * 2, positions.length);
        float left = layout.left(0, .5f), baseline = layout.baseline(0, .5f);
        for (int i = 0; i < ids.length; i++) {
            assertEquals(Integer.parseInt(ids[i]), layout.glyphIds[i]);
            assertEquals("glyph X " + i, Float.parseFloat(positions[i * 2]), left + layout.glyphPositions[i * 2], .001f);
            assertEquals("glyph Y " + i, Float.parseFloat(positions[i * 2 + 1]), baseline + layout.glyphPositions[i * 2 + 1], .001f);
        }
    }
}
