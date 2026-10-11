package com.xpe.mobile.preview;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import static org.junit.Assert.*;

/** Compare every glyph origin with an independently executed Rust glyph_brush_layout. */
@RunWith(Parameterized.class)
public final class PhiraHudFontOracleTest {
    @Parameterized.Parameters(name = "{0}: {1}px {2}")
    public static Collection<Object[]> cases() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        try (InputStream input = PhiraHudFontOracleTest.class.getResourceAsStream("/phira-hud-font-oracle.tsv");
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                rows.add(new Object[]{fields[0], Float.parseFloat(fields[1]), fields[2],
                        Float.parseFloat(fields[3]), Float.parseFloat(fields[4]), fields[5]});
            }
        }
        return rows;
    }

    static File asset(String name) {
        File file = new File("src/main/assets/fonts/" + name);
        return file.isFile() ? file : new File("app/src/main/assets/fonts/" + name);
    }

    static PhiraFontMetrics font(boolean numeric) throws Exception {
        try (InputStream in = new FileInputStream(asset(numeric ? "phigros.metrics" : "phira.metrics"))) {
            return new PhiraFontMetrics(in);
        }
    }

    private final String name, text, positions;
    private final float scale, width, ascent;
    public PhiraHudFontOracleTest(String name, float scale, String text, float width, float ascent, String positions) {
        this.name = name; this.scale = scale; this.text = text;
        this.width = width; this.ascent = ascent; this.positions = positions;
    }

    @Test public void matchesReferenceGlyphOriginsAdvanceAndBaseline() throws Exception {
        PhiraFontMetrics metrics = font(name.equals("numbers"));
        PhiraFontMetrics.Layout actual = metrics.layout(text, scale, Float.POSITIVE_INFINITY);
        assertEquals(width, actual.width, .0001f);
        assertEquals(ascent, actual.ascent, .0001f);
        String[] expected = positions.split(",");
        assertEquals(expected.length, actual.positions.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("glyph " + i, Float.parseFloat(expected[i]), actual.positions[i], .0001f);
        }
        assertEquals(scale * metrics.unitsPerEm / (metrics.ascent - metrics.descent), actual.emSize, .0001f);
    }
}
