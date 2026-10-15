package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Synchronizes explicit metadata edits; merely importing a package preserves authored META/YAML. */
final class PackageMetadataManifestWriter {
    private static final String[] KEYS = {"name", "level", "composer", "charter"};

    static String text(ChartPackage pack, ChartDocument chart, String previous) throws IOException {
        String source = previous;
        if (source == null) {
            File file = PackageImporter.resolveInside(pack.getWorkspace(), BackgroundDimManifestWriter.path(pack));
            if (!file.isFile()) return null;
            source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        }
        for (String key : KEYS) {
            if (chart.isPackageMetadataEdited(key)) source = update(source, key, display(chart, key));
        }
        return source;
    }

    private static String display(ChartDocument chart, String key) {
        switch (key) {
            case "name": return chart.getDisplayName();
            case "level": return chart.getDisplayLevel();
            case "composer": return chart.getDisplayComposer();
            case "charter": return chart.getDisplayCharter();
            default: throw new IllegalArgumentException("Unknown display metadata field: " + key);
        }
    }

    private static String update(String original, String key, String value) {
        int rootIndent = PackageManifest.yamlRootIndent(original);
        Pattern pattern = Pattern.compile("(?im)^(\\ufeff?[ \\t]{" + rootIndent + "}" + key
                + "[ \\t]*:[ \\t]*)([^\\r\\n]*)(\\r?$)");
        Matcher matcher = pattern.matcher(original);
        String scalar = JSONObject.quote(value == null ? "" : value);
        if (!matcher.find()) {
            String newline = original.contains("\r\n") ? "\r\n" : "\n";
            return original + (original.isEmpty() || original.endsWith("\n") ? "" : newline)
                    + " ".repeat(rootIndent) + key + ": " + scalar + newline;
        }
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        do {
            if (matcher.start() < cursor) continue;
            String before = matcher.group(2);
            int comment = PackageManifest.yamlCommentStart(before);
            int suffixStart = comment;
            while (suffixStart > 0 && (before.charAt(suffixStart - 1) == ' '
                    || before.charAt(suffixStart - 1) == '\t')) suffixStart--;
            String suffix = comment < 0 ? "" : before.substring(suffixStart);
            result.append(original, cursor, matcher.start());
            result.append(matcher.group(1)).append(scalar).append(suffix).append(matcher.group(3));
            cursor = matcher.end();
            int newlineLength = cursor < original.length() && original.charAt(cursor) == '\n' ? 1
                    : cursor + 1 < original.length() && original.charAt(cursor) == '\r'
                    && original.charAt(cursor + 1) == '\n' ? 2 : 0;
            if (newlineLength > 0) {
                PackageManifest.YamlBlockScalar block = PackageManifest.yamlBlockScalar(
                        before, original.substring(cursor + newlineLength), rootIndent);
                if (block != null) {
                    result.append(original, cursor, cursor + newlineLength);
                    cursor += newlineLength + block.bodyLength;
                }
            }
        } while (matcher.find());
        result.append(original, cursor, original.length());
        return result.toString();
    }

    static void writeWorkspace(ChartPackage pack, ChartDocument chart) throws IOException {
        boolean edited = false;
        for (String key : KEYS) edited |= chart.isPackageMetadataEdited(key);
        if (!edited) return;
        String source = text(pack, chart, null);
        if (source != null) {
            write(PackageImporter.resolveInside(pack.getWorkspace(), BackgroundDimManifestWriter.path(pack)),
                    source.getBytes(StandardCharsets.UTF_8));
        }
        // TXT fallback must agree even when an explicitly emptied YAML scalar is skipped.
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InfoTxtManifestWriter.write(pack, chart, output);
        write(PackageImporter.resolveInside(pack.getWorkspace(), InfoTxtManifestWriter.FILE_NAME),
                output.toByteArray());
    }

    private static void write(File target, byte[] bytes) throws IOException {
        if (target.isFile() && java.util.Arrays.equals(bytes, Files.readAllBytes(target.toPath()))) return;
        File temporary = File.createTempFile(".xpe-metadata-", ".tmp", target.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
                output.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            temporary.delete();
        }
    }
}
