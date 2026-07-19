package com.xpe.mobile.packageio;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class PackageManifest {
    public enum Kind {
        YAML,
        TEXT
    }

    private final String path;
    private final Kind kind;
    private final String sourceText;
    private final Map<String, String> fields;

    private PackageManifest(String path, Kind kind, String sourceText, Map<String, String> fields) {
        this.path = path;
        this.kind = kind;
        this.sourceText = sourceText;
        this.fields = Collections.unmodifiableMap(fields);
    }

    public static PackageManifest parse(String path, Kind kind, String sourceText) {
        String text = sourceText == null ? "" : sourceText;
        if (!text.isEmpty() && text.charAt(0) == '\ufeff') text = text.substring(1);
        Map<String, String> values = new LinkedHashMap<>();
        String[] lines = text.split("\\r?\\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int separator = trimmed.indexOf(':');
            if (separator <= 0) continue;
            String key = trimmed.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty() || values.containsKey(key)) continue;
            values.put(key, decodeScalar(trimmed.substring(separator + 1).trim()));
        }
        return new PackageManifest(path, kind, sourceText, values);
    }

    private static String decodeScalar(String value) {
        if (value.equals("null") || value.equals("~")) return null;
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            String body = value.substring(1, value.length() - 1);
            return body.replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                    .replace("\\\\", "\\");
        }
        if (value.length() >= 2 && value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'') {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }

    public String getPath() {
        return path;
    }

    public Kind getKind() {
        return kind;
    }

    public String getSourceText() {
        return sourceText;
    }

    public Map<String, String> getFields() {
        return fields;
    }

    public String get(String key) {
        return fields.get(key.toLowerCase(Locale.ROOT));
    }
}
