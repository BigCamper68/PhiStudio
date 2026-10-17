package com.xpe.mobile.packageio;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class PackageManifest {
    private static final Pattern BLOCK_SCALAR = Pattern.compile("[|>](?:[1-9][+-]?|[+-][1-9]?)?");
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
        String[] lines = text.split("(?<=\\n)", -1);
        int rootIndent = kind == Kind.YAML ? yamlRootIndent(text) : 0;
        int offset = 0;
        for (String rawLine : lines) {
            offset += rawLine.length();
            String line = withoutLineEnding(rawLine);
            // Only top-level YAML keys describe ChartInfo; intro blocks and nested maps do not.
            if (kind == Kind.YAML && indentation(line) != rootIndent) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int separator = trimmed.indexOf(':');
            if (separator <= 0) continue;
            String key = trimmed.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty() || values.containsKey(key)) continue;
            String scalar = trimmed.substring(separator + 1).trim();
            if (kind == Kind.YAML) {
                int comment = yamlCommentStart(scalar);
                if (comment >= 0) scalar = scalar.substring(0, comment).trim();
            }
            YamlBlockScalar block = kind == Kind.YAML
                    ? yamlBlockScalar(scalar, text.substring(offset), rootIndent) : null;
            values.put(key, block == null ? decodeScalar(scalar) : block.value);
        }
        return new PackageManifest(path, kind, sourceText, values);
    }

    private static String decodeScalar(String value) {
        if (value.equalsIgnoreCase("null") || value.equals("~")) return null;
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            String body = value.substring(1, value.length() - 1);
            StringBuilder decoded = new StringBuilder(body.length());
            for (int index = 0; index < body.length(); index++) {
                char character = body.charAt(index);
                if (character != '\\' || index + 1 >= body.length()) {
                    decoded.append(character);
                    continue;
                }
                char escape = body.charAt(++index);
                switch (escape) {
                    case '0': decoded.append('\0'); break;
                    case 'a': decoded.append('\u0007'); break;
                    case 'b': decoded.append('\b'); break;
                    case 't': decoded.append('\t'); break;
                    case 'n': decoded.append('\n'); break;
                    case 'v': decoded.append('\u000b'); break;
                    case 'f': decoded.append('\f'); break;
                    case 'r': decoded.append('\r'); break;
                    case 'e': decoded.append('\u001b'); break;
                    case 'N': decoded.append('\u0085'); break;
                    case '_': decoded.append('\u00a0'); break;
                    case 'L': decoded.append('\u2028'); break;
                    case 'P': decoded.append('\u2029'); break;
                    case '"': case '\\': case '/': case ' ': decoded.append(escape); break;
                    case 'x': case 'u': case 'U': {
                        int digits = escape == 'x' ? 2 : escape == 'u' ? 4 : 8;
                        if (index + digits < body.length()) {
                            try {
                                int codePoint = Integer.parseInt(body.substring(index + 1, index + 1 + digits), 16);
                                decoded.append(Character.toChars(codePoint));
                                index += digits;
                                break;
                            } catch (IllegalArgumentException ignored) {
                                // Keep invalid optional scalar escapes without silently losing data.
                            }
                        }
                        decoded.append('\\').append(escape);
                        break;
                    }
                    default: decoded.append('\\').append(escape);
                }
            }
            return decoded.toString();
        }
        if (value.length() >= 2 && value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'') {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }

    private static int indentation(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') count++;
        return count;
    }

    private static String withoutLineEnding(String line) {
        int end = line.length();
        if (end > 0 && line.charAt(end - 1) == '\n') end--;
        if (end > 0 && line.charAt(end - 1) == '\r') end--;
        return line.substring(0, end);
    }

    /** The value and exact body span of a top-level YAML literal/folded scalar. */
    static final class YamlBlockScalar {
        final String value;
        final int bodyLength;

        YamlBlockScalar(String value, int bodyLength) {
            this.value = value;
            this.bodyLength = bodyLength;
        }
    }

    static YamlBlockScalar yamlBlockScalar(String scalar, String body, int rootIndent) {
        int comment = yamlCommentStart(scalar);
        String header = (comment < 0 ? scalar : scalar.substring(0, comment)).trim();
        if (!BLOCK_SCALAR.matcher(header).matches()) return null;
        int blockIndent = 0;
        char chomp = ' ';
        for (int index = 1; index < header.length(); index++) {
            char character = header.charAt(index);
            if (character >= '1' && character <= '9') blockIndent = rootIndent + character - '0';
            else chomp = character;
        }
        String[] lines = body.split("(?<=\\n)", -1);
        if (blockIndent == 0) {
            blockIndent = rootIndent + 1;
            for (String rawLine : lines) {
                String line = withoutLineEnding(rawLine);
                if (line.trim().isEmpty()) {
                    blockIndent = Math.max(blockIndent, indentation(line));
                    continue;
                }
                if (indentation(line) > rootIndent)
                    blockIndent = Math.max(blockIndent, indentation(line));
                break;
            }
        }
        List<String> content = new ArrayList<>();
        List<Boolean> lineBreaks = new ArrayList<>();
        int bodyLength = 0;
        boolean hasContent = false;
        for (String rawLine : lines) {
            String line = withoutLineEnding(rawLine);
            int indent = indentation(line);
            if (!line.trim().isEmpty() && indent < blockIndent) break;
            String value = line.substring(Math.min(blockIndent, line.length()));
            content.add(value);
            lineBreaks.add(rawLine.endsWith("\n"));
            hasContent |= !value.isEmpty();
            bodyLength += rawLine.length();
        }
        StringBuilder value = new StringBuilder();
        boolean previousPlain = false;
        for (int index = 0; index < content.size(); index++) {
            String line = content.get(index);
            boolean plain = isPlainBlockLine(line);
            value.append(line);
            if (lineBreaks.get(index)) {
                String next = index + 1 < content.size() ? content.get(index + 1) : "";
                boolean nextPlain = isPlainBlockLine(next);
                if (header.charAt(0) == '>' && plain && nextPlain) value.append(' ');
                else if (!(header.charAt(0) == '>' && line.isEmpty() && previousPlain && nextPlain))
                    value.append('\n');
            }
            if (!line.isEmpty()) previousPlain = plain;
        }
        if (chomp != '+') {
            boolean endedWithBreak = value.length() > 0 && value.charAt(value.length() - 1) == '\n';
            while (value.length() > 0 && value.charAt(value.length() - 1) == '\n')
                value.setLength(value.length() - 1);
            if (chomp != '-' && hasContent && endedWithBreak) value.append('\n');
        }
        return new YamlBlockScalar(value.toString(), bodyLength);
    }

    private static boolean isPlainBlockLine(String line) {
        return !line.isEmpty() && line.charAt(0) != ' ' && line.charAt(0) != '\t';
    }

    static int yamlRootIndent(String source) {
        int minimum = Integer.MAX_VALUE;
        for (String line : source.split("\\r?\\n", -1)) {
            if (!line.isEmpty() && line.charAt(0) == '\ufeff') line = line.substring(1);
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.indexOf(':') <= 0) continue;
            minimum = Math.min(minimum, indentation(line));
        }
        return minimum == Integer.MAX_VALUE ? 0 : minimum;
    }

    /** A hash inside quotes or a plain token is data; whitespace-separated hashes are comments. */
    static int yamlCommentStart(String value) {
        boolean single = false;
        boolean doubleQuoted = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (doubleQuoted && character == '\\') {
                index++;
            } else if (!doubleQuoted && character == '\'' && (single || index == 0)) {
                if (single && index + 1 < value.length() && value.charAt(index + 1) == '\'') index++;
                else single = !single;
            } else if (!single && character == '"' && (doubleQuoted || index == 0)) {
                doubleQuoted = !doubleQuoted;
            } else if (!single && !doubleQuoted && character == '#'
                    && (index == 0 || Character.isWhitespace(value.charAt(index - 1)))) {
                return index;
            }
        }
        return -1;
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
