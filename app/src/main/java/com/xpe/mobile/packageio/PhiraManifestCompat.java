package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps info.yml complete enough for Phira and legacy .pez importers. */
final class PhiraManifestCompat {
    private static final Pattern LEVEL_NUMBER = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");
    private static final Set<String> KNOWN_FIELDS = new HashSet<>(Arrays.asList(
            "id", "uploader", "name", "difficulty", "level", "charter", "composer",
            "illustrator", "chart", "format", "music", "illustration", "unlockvideo",
            "previewstart", "previewend", "aspectratio", "backgrounddim", "linelength",
            "offset", "tip", "tags", "intro", "holdpartialcover", "noteuniformscale",
            "forceaspectratio", "userpe170speed", "useattachuifix", "created", "updated",
            "chartupdated"));

    private PhiraManifestCompat() {
    }

    static String normalize(String source, ChartDocument chart, String chartPath,
                            String audioPath, String illustrationPath,
                            long packageOffsetMs, boolean useRpe170Speed) {
        String original = source == null ? "" : source;
        // Unknown keys and free-form lines may belong to a future package format. Keep those
        // manifests byte-for-byte instead of rewriting information PhiStudio does not own.
        if (!original.trim().isEmpty() && !isKnownManifest(original)) return original;

        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        LinkedHashMap<String, Field> fields = fields(chart, chartPath, audioPath,
                illustrationPath, packageOffsetMs, useRpe170Speed);

        StringBuilder output = new StringBuilder(original.length() + 384);
        String[] lines = original.split("\\r?\\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String key = topLevelKey(line);
            Field replacement = key == null ? null : fields.get(key.toLowerCase(Locale.ROOT));
            if (replacement != null) {
                output.append(replacement.key).append(": ").append(replacement.value);
                replacement.written = true;
            } else {
                output.append(line);
            }
            if (index + 1 < lines.length) output.append(newline);
        }

        if (output.length() > 0 && !endsWithNewline(output)) output.append(newline);
        for (Field field : fields.values()) {
            if (!field.written && field.value != null) {
                output.append(field.key).append(": ").append(field.value).append(newline);
            }
        }
        return output.toString();
    }

    private static boolean isKnownManifest(String source) {
        String[] lines = source.split("\\r?\\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")
                    || Character.isWhitespace(line.charAt(0))) {
                continue;
            }
            String key = topLevelKey(line);
            if (key == null || !KNOWN_FIELDS.contains(key.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static LinkedHashMap<String, Field> fields(ChartDocument chart, String chartPath,
                                                        String audioPath, String illustrationPath,
                                                        long packageOffsetMs,
                                                        boolean useRpe170Speed) {
        ChartDocument value = chart == null ? new ChartDocument() : chart;
        LinkedHashMap<String, Field> fields = new LinkedHashMap<>();
        put(fields, "name", quote(defaultText(value.name, "Untitled")));
        put(fields, "difficulty", decimal(difficulty(value.level)));
        put(fields, "level", quote(defaultText(value.level, "UK Lv.10")));
        put(fields, "charter", quote(defaultText(value.charter, "Unknown")));
        put(fields, "composer", quote(defaultText(value.composer, "Unknown")));
        put(fields, "illustrator", quote("Unknown"));
        put(fields, "chart", pathScalar(defaultText(chartPath, "chart.json")));
        put(fields, "format", "rpe");
        if (audioPath != null && !audioPath.trim().isEmpty()) {
            put(fields, "music", pathScalar(audioPath));
        }
        if (illustrationPath != null && !illustrationPath.trim().isEmpty()) {
            put(fields, "illustration", pathScalar(illustrationPath));
        }
        put(fields, "previewStart", "0");
        put(fields, "aspectRatio", "1.7777778");
        put(fields, "backgroundDim", "0.6");
        put(fields, "lineLength", "6");
        put(fields, "offset", decimal(packageOffsetMs / 1000.0));
        put(fields, "tags", "[]");
        put(fields, "intro", quote(""));
        put(fields, "holdPartialCover", "false");
        put(fields, "noteUniformScale", "false");
        put(fields, "forceAspectRatio", "false");
        put(fields, "useRpe170Speed", Boolean.toString(useRpe170Speed));
        return fields;
    }

    private static void put(Map<String, Field> fields, String key, String value) {
        fields.put(key.toLowerCase(Locale.ROOT), new Field(key, value));
    }

    private static String topLevelKey(String line) {
        if (line == null || line.isEmpty() || Character.isWhitespace(line.charAt(0))) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null;
        int separator = line.indexOf(':');
        if (separator <= 0) return null;
        String key = line.substring(0, separator).trim();
        return key.matches("[A-Za-z][A-Za-z0-9_]*") ? key : null;
    }

    private static double difficulty(String level) {
        Matcher matcher = LEVEL_NUMBER.matcher(level == null ? "" : level);
        double result = 10.0;
        while (matcher.find()) {
            try {
                result = Double.parseDouble(matcher.group());
            } catch (NumberFormatException ignored) {
                // Keep the previous valid number.
            }
        }
        return Double.isFinite(result) ? result : 10.0;
    }

    private static String defaultText(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String pathScalar(String value) {
        String safe = defaultText(value, "");
        return safe.matches("[A-Za-z0-9._/-]+") ? safe : quote(safe);
    }

    private static String quote(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", " ") + "\"";
    }

    private static String decimal(double value) {
        if (!Double.isFinite(value)) return "0";
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static boolean endsWithNewline(StringBuilder value) {
        if (value.length() == 0) return false;
        char last = value.charAt(value.length() - 1);
        return last == '\n' || last == '\r';
    }

    private static final class Field {
        final String key;
        final String value;
        boolean written;

        Field(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }
}
