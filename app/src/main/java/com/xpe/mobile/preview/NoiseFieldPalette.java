package com.xpe.mobile.preview;

import com.xpe.mobile.model.NoiseField;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

/** RGB ownership only. Native GPU coverage, fades, subtraction and input are untouched. */
final class NoiseFieldPalette {
    private int width, height;
    private float aspect;
    private List<NoiseFieldFrame.Zone> previous;
    private int[] normal, subtract, parity, seeds, owners;
    private ByteBuffer pixels;
    private long revision;
    private boolean inactiveVisible;

    long revision() { return revision; }
    boolean inactiveVisible() { return inactiveVisible; }
    ByteBuffer pixels() { pixels.rewind(); return pixels; }
    boolean update(NoiseFieldFrame frame, int w, int h) {
        boolean colored = false;
        for (NoiseFieldFrame.Zone zone : frame.zones)
            colored |= zone.opacity > 0 && zone.color != NoiseField.DEFAULT_COLOR;
        if (!colored) { previous = null; return false; }
        if (w == width && h == height && aspect == frame.aspect && same(previous, frame.zones)) return true;
        width = w; height = h; aspect = frame.aspect;
        int size = w * h;
        if (normal == null || normal.length != size) {
            normal = new int[size]; subtract = new int[size]; parity = new int[size];
            seeds = new int[size]; owners = new int[size];
            pixels = ByteBuffer.allocateDirect(size * 8);
        }
        for (int layer = 0; layer < 2; layer++) {
            Arrays.fill(normal, -1); Arrays.fill(subtract, -1); Arrays.fill(parity, 0);
            for (int i = 0; i < frame.zones.size(); i++) {
                NoiseFieldFrame.Zone zone = frame.zones.get(i);
                if (zone.active == (layer == 0) && zone.opacity > 0) raster(zone, i);
            }
            boolean visible = false;
            for (int i = 0; i < size; i++) {
                boolean hasNormal = normal[i] >= 0, oddSubtract = parity[i] != 0;
                owners[i] = hasNormal == oddSubtract ? -1 : hasNormal ? normal[i] : subtract[i];
                seeds[i] = owners[i] < 0 ? -1 : i;
                visible |= owners[i] >= 0;
            }
            if (layer == 1) inactiveVisible = visible;
            // Extend visible owners into holes and the glow region. A carving field cannot tint its rim.
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (seeds[i] == i) continue;
                if (x > 0) nearest(i, x, y, i - 1);
                if (y > 0) {
                    nearest(i, x, y, i - w);
                    if (x > 0) nearest(i, x, y, i - w - 1);
                    if (x + 1 < w) nearest(i, x, y, i - w + 1);
                }
            }
            for (int y = h - 1; y >= 0; y--) for (int x = w - 1; x >= 0; x--) {
                int i = y * w + x;
                if (seeds[i] == i) continue;
                if (x + 1 < w) nearest(i, x, y, i + 1);
                if (y + 1 < h) {
                    nearest(i, x, y, i + w);
                    if (x > 0) nearest(i, x, y, i + w - 1);
                    if (x + 1 < w) nearest(i, x, y, i + w + 1);
                }
            }
            pixels.position(layer * size * 4);
            for (int i = 0; i < size; i++) {
                int color = seeds[i] < 0 ? NoiseField.DEFAULT_COLOR : frame.zones.get(owners[seeds[i]]).color;
                pixels.put((byte) (color >> 16)).put((byte) (color >> 8)).put((byte) color).put((byte) 255);
            }
        }
        previous = frame.zones; revision++; pixels.rewind(); return true;
    }
    private void raster(NoiseFieldFrame.Zone zone, int id) {
        double angle = zone.rotation * .017453292f, c = Math.cos(angle), s = Math.sin(angle);
        double hx = zone.width * .5, hy = zone.height * .5;
        double bx = Math.abs(c) * hx + Math.abs(s) * hy, by = (Math.abs(s) * hx + Math.abs(c) * hy) * zone.yScale;
        int left = bound((int) Math.floor((zone.x - bx + 1) * width / 2), width);
        int right = bound((int) Math.ceil((zone.x + bx + 1) * width / 2), width);
        int bottom = bound((int) Math.floor(((zone.y - by) * aspect + 1) * height / 2), height);
        int top = bound((int) Math.ceil(((zone.y + by) * aspect + 1) * height / 2), height);
        for (int y = bottom; y < top; y++) for (int x = left; x < right; x++) {
            double dx = (x + .5) * 2 / width - 1 - zone.x;
            double dy = (((y + .5) * 2 / height - 1) / aspect - zone.y) / zone.yScale;
            if (Math.abs(c * dx + s * dy) > hx || Math.abs(-s * dx + c * dy) > hy) continue;
            int pixel = y * width + x;
            if (zone.subtract) { subtract[pixel] = id; parity[pixel] ^= 1; }
            else normal[pixel] = id;
        }
    }
    private int bound(int value, int max) { return Math.max(0, Math.min(max, value)); }
    private void nearest(int index, int x, int y, int neighbor) {
        int candidate = seeds[neighbor];
        if (candidate >= 0 && (seeds[index] < 0 || distance(candidate, x, y) < distance(seeds[index], x, y)))
            seeds[index] = candidate;
    }
    private long distance(int seed, int x, int y) {
        long dx = seed % width - x, dy = seed / width - y; return dx * dx + dy * dy;
    }
    private static boolean same(List<NoiseFieldFrame.Zone> a, List<NoiseFieldFrame.Zone> b) {
        if (a == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            NoiseFieldFrame.Zone x = a.get(i), y = b.get(i);
            if (x.x != y.x || x.y != y.y || x.width != y.width || x.height != y.height || x.rotation != y.rotation
                    || x.opacity != y.opacity || x.active != y.active || x.subtract != y.subtract || x.color != y.color
                    || x.yScale != y.yScale) return false;
        }
        return true;
    }
}
