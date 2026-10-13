package com.xpe.mobile.model;

import java.util.Locale;

/** Numeric and Unicode text transitions used by storyboard events. */
final class StoryboardTextTween {
    static String tween(String start, String end, double progress) {
        String first = start == null ? "" : start;
        String second = end == null ? "" : end;
        if (first.contains("%P%") && second.contains("%P%")) {
            String firstNumber = first.replace("%P%", "");
            String secondNumber = second.replace("%P%", "");
            if (progress >= 1.0) return secondNumber;
            if (progress <= 0.0) return firstNumber;
            double from = parseNumber(firstNumber);
            double to = parseNumber(secondNumber);
            double value = from + progress * (to - from);
            boolean integers = isInteger(from) && isInteger(to);
            return integers
                    ? String.format(Locale.US, "%.0f", value)
                    : String.format(Locale.US, "%.3f", value);
        }
        if (first.isEmpty() && second.isEmpty()) return "";
        if (second.isEmpty()) {
            String stripped = first.replace("%P%", "");
            return tween("", stripped, 1.0 - progress);
        }
        if (first.isEmpty()) return takeCodePoints(second,
                (int) Math.round(progress * second.codePointCount(0, second.length())));
        int firstLength = first.codePointCount(0, first.length());
        int secondLength = second.codePointCount(0, second.length());
        if (second.startsWith(first)) {
            int count = firstLength
                    + (int) Math.floor((secondLength - firstLength) * progress);
            return takeCodePoints(second, count);
        }
        if (first.startsWith(second)) {
            int count = secondLength
                    + (int) Math.round((firstLength - secondLength) * (1.0 - progress));
            return takeCodePoints(first, count);
        }
        return first.contains("%P%") ? first.replace("%P%", "") : first;
    }

    private static String takeCodePoints(String value, int count) {
        int available = value.codePointCount(0, value.length());
        int safe = Math.max(0, Math.min(available, count));
        return value.substring(0, value.offsetByCodePoints(0, safe));
    }

    private static double parseNumber(String value) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : 0.0;
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static boolean isInteger(double value) {
        return Math.rint(value) == value;
    }

    static double clamp(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
