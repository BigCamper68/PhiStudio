package com.xpe.mobile.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/** Numeric and Unicode text transitions used by storyboard events. */
final class StoryboardTextTween {
    private static final Pattern DECIMAL = Pattern.compile(
            "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

    static String tween(String start, String end, double progress) {
        String first = start == null ? "" : start;
        String second = end == null ? "" : end;
        // String::tween receives f32 in prpr. Keeping its intermediates as f32
        // matters at glyph-count boundaries and when parsing %P% endpoints.
        float t = (float) progress;
        if (first.contains("%P%") && second.contains("%P%")) {
            String firstNumber = first.replace("%P%", "");
            String secondNumber = second.replace("%P%", "");
            if (t >= 1.0f) return secondNumber;
            if (t <= 0.0f) return firstNumber;
            float from = parseNumber(firstNumber);
            float to = parseNumber(secondNumber);
            float value = from + t * (to - from);
            boolean integers = isInteger(from) && isInteger(to);
            return formatNumber(value, integers ? 0 : 3);
        }
        if (first.isEmpty() && second.isEmpty()) return "";
        if (second.isEmpty()) {
            String stripped = first.replace("%P%", "");
            return tween("", stripped, 1.0f - t);
        }
        if (first.isEmpty()) return takeCodePoints(second,
                positiveCount(t * second.codePointCount(0, second.length()), true));
        int firstLength = first.codePointCount(0, first.length());
        int secondLength = second.codePointCount(0, second.length());
        if (second.startsWith(first)) {
            int count = firstLength + Math.min(secondLength - firstLength,
                    positiveCount((secondLength - firstLength) * t, false));
            return takeCodePoints(second, count);
        }
        if (first.startsWith(second)) {
            int count = secondLength + Math.min(firstLength - secondLength,
                    positiveCount((firstLength - secondLength) * (1.0f - t), true));
            return takeCodePoints(first, count);
        }
        return first.contains("%P%") ? first.replace("%P%", "") : first;
    }

    private static String takeCodePoints(String value, int count) {
        int available = value.codePointCount(0, value.length());
        int safe = Math.max(0, Math.min(available, count));
        return value.substring(0, value.offsetByCodePoints(0, safe));
    }

    private static int positiveCount(float value, boolean round) {
        // Rust's float-to-usize conversion saturates negatives and NaN to zero.
        if (!(value > 0.0f)) return 0;
        return round ? Math.round(value) : (int) Math.floor(value);
    }

    private static float parseNumber(String value) {
        String unsigned = value.startsWith("+") || value.startsWith("-")
                ? value.substring(1) : value;
        if (unsigned.equalsIgnoreCase("nan")) return Float.NaN;
        if (unsigned.equalsIgnoreCase("inf") || unsigned.equalsIgnoreCase("infinity")) {
            return value.startsWith("-") ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
        }
        // Float.parseFloat also accepts whitespace, hexadecimal and f/d suffixes;
        // Rust's FromStr rejects those strings and uses the fallback zero.
        if (!DECIMAL.matcher(value).matches()) return 0.0f;
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException ignored) {
            return 0.0f;
        }
    }

    private static boolean isInteger(float value) {
        return Float.isFinite(value) && Math.rint(value) == value;
    }

    private static String formatNumber(float value, int decimals) {
        if (Float.isNaN(value)) return "NaN";
        if (Float.isInfinite(value)) return value < 0.0f ? "-inf" : "inf";
        // Rust formatting rounds ties to even on the exact binary value. Java's
        // Formatter rounds halfway values up, and Float.toString loses that value.
        String result = new BigDecimal((double) value)
                .setScale(decimals, RoundingMode.HALF_EVEN).toPlainString();
        if (Float.floatToRawIntBits(value) < 0 && result.charAt(0) != '-') {
            return "-" + result; // Preserve the sign when a negative value rounds to zero.
        }
        return result;
    }

    static double clamp(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
