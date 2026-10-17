package com.xpe.mobile.editor;

/** Supported vertical note-grid counts and exact chart-coordinate snapping. */
public final class VerticalGrid {
    private static final int[] COUNTS = {3, 5, 7, 9, 11, 13, 17, 21, 25, 33};

    private VerticalGrid() {
    }

    public static int defaultCount() {
        return 11;
    }

    public static int changeCount(int current, int delta) {
        int nearest = 0;
        int nearestDistance = Integer.MAX_VALUE;
        for (int index = 0; index < COUNTS.length; index++) {
            int distance = Math.abs(COUNTS[index] - current);
            if (distance < nearestDistance) {
                nearest = index;
                nearestDistance = distance;
            }
        }
        return COUNTS[Math.max(0, Math.min(COUNTS.length - 1, nearest + delta))];
    }

    public static double snap(double value, double minimum, double maximum, int lineCount) {
        int safeCount = Math.max(2, lineCount);
        double clamped = Math.max(minimum, Math.min(maximum, value));
        double step = (maximum - minimum) / (safeCount - 1.0);
        return minimum + Math.round((clamped - minimum) / step) * step;
    }

    public static float screenX(int index, int lineCount, float left, float right) {
        int safeCount = Math.max(2, lineCount);
        int safeIndex = Math.max(0, Math.min(safeCount - 1, index));
        return left + safeIndex * (right - left) / (safeCount - 1f);
    }
}
