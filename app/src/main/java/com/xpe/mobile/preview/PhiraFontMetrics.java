package com.xpe.mobile.preview;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** ab_glyph logical metrics; Android Paint uses em size, not ab_glyph height. */
public final class PhiraFontMetrics {
    public final int unitsPerEm, ascent, descent, lineGap;
    private final int[] advances, bearings, codepoints, glyphs, kernKeys, kernValues;

    public PhiraFontMetrics(InputStream source) throws IOException {
        DataInputStream in = new DataInputStream(source);
        int magic = in.readInt();
        if (magic != 0x50484631 && magic != 0x50484632) throw new IOException("Invalid Phira font metrics");
        unitsPerEm = in.readInt();
        ascent = in.readInt();
        descent = in.readInt();
        lineGap = magic == 0x50484632 ? in.readInt() : 0;
        if (unitsPerEm <= 0 || ascent <= descent) throw new IOException("Invalid font height");
        advances = new int[count(in)];
        for (int i = 0; i < advances.length; i++) advances[i] = in.readInt();
        bearings = new int[advances.length];
        if (magic == 0x50484632) {
            for (int i = 0; i < bearings.length; i++) bearings[i] = in.readInt();
        }
        int size = count(in);
        codepoints = new int[size];
        glyphs = new int[size];
        for (int i = 0; i < size; i++) { codepoints[i] = in.readInt(); glyphs[i] = in.readInt(); }
        size = count(in);
        kernKeys = new int[size];
        kernValues = new int[size];
        for (int i = 0; i < size; i++) { kernKeys[i] = in.readInt(); kernValues[i] = in.readInt(); }
    }

    private static int count(DataInputStream in) throws IOException {
        int value = in.readInt();
        if (value < 0 || value > 1_000_000) throw new IOException("Invalid metric table size");
        return value;
    }

    public float emSize(float scale) { return scale * (unitsPerEm / (float) (ascent - descent)); }
    public float baseline(float scale) { return scale / (ascent - descent) * ascent; }

    private int glyph(int cp) {
        int index = Arrays.binarySearch(codepoints, cp);
        return index < 0 ? 0 : glyphs[index];
    }

    private float kerning(int left, int right, float factor) {
        int key = left << 16 | right;
        // Exported keys are in unsigned glyph-id order. TTF has at most 65535 glyphs.
        int low = 0, high = kernKeys.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (Integer.compareUnsigned(kernKeys[mid], key) < 0) low = mid + 1;
            else high = mid;
        }
        return low < kernKeys.length && kernKeys[low] == key ? factor * kernValues[low] : 0;
    }

    public Layout layout(String value, float scale, float maxWidth) {
        String text = value == null ? "" : value;
        int[] cps = text.codePoints().filter(cp -> !Character.isISOControl(cp)).toArray();
        float[] positions = new float[cps.length];
        float[] ends = new float[cps.length];
        float factor = scale / (ascent - descent), caret = 0;
        int previous = -1;
        for (int i = 0; i < cps.length; i++) {
            int id = glyph(cps[i]);
            if (previous >= 0) caret += kerning(previous, id, factor);
            positions[i] = caret;
            caret += advances[id] * factor;
            ends[i] = caret;
            previous = id;
        }
        if (caret <= maxWidth) return finish(new Layout(cps, positions, caret, baseline(scale), emSize(scale)));
        float ellipsis = advances[glyph('…')] * factor;
        if (ellipsis > maxWidth) return finish(new Layout(new int[0], new float[0], 0, baseline(scale), emSize(scale)));
        int index = 0;
        while (index < ends.length && ends[index] <= maxWidth - ellipsis) index++;
        float width = (index == 0 ? 0 : ends[index - 1]) + ellipsis;
        // Deliberately mirror DrawText::measure_inner's char-boundary round-back,
        // including its extra shortened prefix and separately reported bounds.
        int prefix = Math.max(0, index - 2);
        StringBuilder shortened = new StringBuilder();
        for (int i = 0; i < prefix; i++) shortened.appendCodePoint(cps[i]);
        shortened.append('…');
        Layout drawn = layout(shortened.toString(), scale, Float.POSITIVE_INFINITY);
        return finish(new Layout(drawn.codepoints, drawn.positions, width, drawn.ascent, drawn.emSize));
    }

    /** Unbounded, left-aligned multiline DrawText, including logical glyph_bounds. */
    public Layout storyboard(String value, float scale) {
        String text = value == null ? "" : value;
        int[] raw = text.codePoints().toArray();
        int[] cps = new int[raw.length];
        float[] x = new float[raw.length], y = new float[raw.length];
        float factor = scale / (ascent - descent), a = factor * ascent, d = factor * descent;
        float gap = factor * lineGap, lineHeight = a - d + gap;
        float caret = 0, row = 0, minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        int previous = -1, count = 0;
        for (int i = 0; i < raw.length; i++) {
            int cp = raw[i], id = glyph(cp);
            if (previous >= 0) caret += kerning(previous, id, factor);
            previous = id;
            if (!Character.isISOControl(cp)) {
                cps[count] = cp; x[count] = caret; y[count] = row;
                float baseline = row + a;
                minX = Math.min(minX, caret - factor * bearings[id]);
                maxX = Math.max(maxX, caret + factor * advances[id]);
                minY = Math.min(minY, baseline - a);
                maxY = Math.max(maxY, baseline - d);
                caret += factor * advances[id];
                count++;
            }
            boolean crlf = cp == '\r' && i + 1 < raw.length && raw[i + 1] == '\n';
            if (!crlf && hardBreak(cp)) {
                caret = 0; previous = -1; row += lineHeight;
            }
        }
        if (count == 0) { minX = minY = maxX = maxY = 0; }
        minX = Math.max(0, minX); minY = Math.max(0, minY);
        int leading = 0;
        while (leading < text.length() && text.charAt(leading) == '\n') leading++;
        float height = maxY - minY + leading * gap * 3 + d;
        Layout result = finish(new Layout(Arrays.copyOf(cps, count), Arrays.copyOf(x, count),
                maxX - minX, a, emSize(scale), height, minX, minY));
        for (int i = 0; i < count; i++) result.glyphPositions[i * 2 + 1] = y[i];
        return result;
    }

    private static boolean hardBreak(int cp) {
        return cp == '\n' || cp == '\r' || cp == 0x0b || cp == 0x0c
                || cp == 0x85 || cp == 0x2028 || cp == 0x2029;
    }

    private Layout finish(Layout layout) {
        layout.glyphIds = new int[layout.codepoints.length];
        layout.glyphPositions = new float[layout.codepoints.length * 2];
        for (int i = 0; i < layout.codepoints.length; i++) {
            layout.glyphIds[i] = glyph(layout.codepoints[i]);
            layout.glyphPositions[i * 2] = layout.positions[i];
        }
        return layout;
    }

    public static final class Layout {
        public final int[] codepoints;
        public int[] glyphIds;
        public float[] glyphPositions;
        public final float[] positions;
        public final String[] glyphTexts;
        public final float width, height, boundsX, boundsY, ascent, emSize;
        Layout(int[] codepoints, float[] positions, float width, float ascent, float emSize) {
            this(codepoints, positions, width, ascent, emSize, ascent, 0, 0);
        }
        Layout(int[] codepoints, float[] positions, float width, float ascent, float emSize,
               float height, float boundsX, float boundsY) {
            this.codepoints = codepoints; this.positions = positions;
            glyphTexts = new String[codepoints.length];
            for (int i = 0; i < codepoints.length; i++) glyphTexts[i] = new String(Character.toChars(codepoints[i]));
            this.width = width; this.ascent = ascent; this.emSize = emSize;
            this.height = height; this.boundsX = boundsX; this.boundsY = boundsY;
        }
        public float left(float x, float anchor) { return x - boundsX - width * anchor; }
        public float baseline(float y, float anchor) { return y - boundsY - height * anchor + ascent; }
    }
}
