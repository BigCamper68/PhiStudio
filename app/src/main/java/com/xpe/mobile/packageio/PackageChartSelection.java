package com.xpe.mobile.packageio;

import com.xpe.mobile.packageio.PackageImporter.JsonCandidate;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** PackageChartSelection responsibilities extracted from PackageImporter. */
final class PackageChartSelection {
    private final PackageImporter delegateOwner;

    PackageChartSelection(PackageImporter delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    static JsonCandidate loadCandidate(File workspace, JsonCandidate candidate)
            throws IOException {
        File file = PackageImporter.resolveInside(workspace, candidate.path);
        String source = PackageImporter.readUtf8(file);
        if (candidate.format == ChartJsonFormat.LEGACY_PEC) {
            return new JsonCandidate(candidate.path, null, source, candidate.format);
        }
        if (!source.isEmpty() && source.charAt(0) == '\ufeff') {
            source = source.substring(1);
        }
        try {
            return new JsonCandidate(
                    candidate.path, com.xpe.mobile.preview.NoiseFieldRpeSource.parse(source), null, candidate.format);
        } catch (JSONException exception) {
            throw new IOException("Unable to parse the selected chart candidate", exception);
        }
    }

    JsonCandidate selectChart(List<PackageManifest> manifests,
                                      List<JsonCandidate> rpeCandidates,
                                      List<JsonCandidate> officialCandidates,
                                      List<JsonCandidate> legacyPecCandidates) throws PackageException {
        Map<String, JsonCandidate> rpeByPath = PackageImporter.byPath(rpeCandidates);
        Map<String, JsonCandidate> officialByPath = PackageImporter.byPath(officialCandidates);
        Map<String, JsonCandidate> pecByPath = PackageImporter.byPath(legacyPecCandidates);
        Set<String> hints = manifestChartPaths(manifests);
        Set<JsonCandidate> referencedRpe = new LinkedHashSet<>();
        Set<JsonCandidate> referencedOfficial = new LinkedHashSet<>();
        Set<JsonCandidate> referencedPec = new LinkedHashSet<>();
        for (String hint : hints) {
            if (rpeByPath.containsKey(hint)) referencedRpe.add(rpeByPath.get(hint));
            if (officialByPath.containsKey(hint)) referencedOfficial.add(officialByPath.get(hint));
            if (pecByPath.containsKey(hint)) referencedPec.add(pecByPath.get(hint));
        }
        Set<JsonCandidate> referenced = new LinkedHashSet<>();
        referenced.addAll(referencedRpe);
        referenced.addAll(referencedOfficial);
        referenced.addAll(referencedPec);
        if (referenced.size() == 1) return referenced.iterator().next();
        if (referenced.size() > 1) {
            throw new PackageException(PackageException.Code.AMBIGUOUS_CHART,
                    "The manifests reference more than one supported chart candidate");
        }
        if (rpeCandidates.size() == 1) return rpeCandidates.get(0);
        if (rpeCandidates.size() > 1) {
            throw new PackageException(PackageException.Code.AMBIGUOUS_CHART,
                    "The package contains multiple RPE chart JSON candidates and no unique manifest selection");
        }
        List<JsonCandidate> convertible = new ArrayList<>();
        convertible.addAll(officialCandidates);
        convertible.addAll(legacyPecCandidates);
        if (convertible.size() == 1) return convertible.get(0);
        if (convertible.size() > 1) {
            throw new PackageException(PackageException.Code.AMBIGUOUS_CHART,
                    "The package contains multiple convertible chart candidates and no unique manifest selection");
        }
        throw new PackageException(PackageException.Code.MISSING_CHART,
                "No supported RPE, official Phigros, or legacy PEC chart was found in the package");
    }

    static Set<String> manifestChartPaths(List<PackageManifest> manifests) throws PackageException {
        Set<String> hints = new LinkedHashSet<>();
        for (PackageManifest manifest : manifests) {
            String hint = manifest.get("chart");
            if (hint == null || hint.trim().isEmpty()) continue;
            try {
                hints.add(PackageImporter.resolveManifestPath(manifest.getPath(), hint));
            } catch (PackageException exception) {
                throw new PackageException(PackageException.Code.INVALID_MANIFEST_PATH,
                        "Manifest contains an unsafe chart path: " + hint);
            }
        }

        return hints;
    }

    static void validateOfficialCandidate(JSONObject root) throws PackageException {
        int version = root.optInt("formatVersion", -1);
        if (version != 1 && version != 3) {
            throw new PackageException(PackageException.Code.UNSUPPORTED_CHART_FORMAT,
                    "Unsupported official Phigros formatVersion " + version
                            + "; only versions 1 and 3 can be converted");
        }
        if (root.optJSONArray("judgeLineList").length() == 0) {
            throw new PackageException(PackageException.Code.UNSUPPORTED_CHART_FORMAT,
                    "Official Phigros chart has no judge lines");
        }
    }

    static boolean looksLikeJsonObject(File file) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            int value;
            boolean bomAllowed = true;
            while ((value = input.read()) != -1) {
                if (bomAllowed && value == 0xef) {
                    int second = input.read();
                    int third = input.read();
                    if (second == 0xbb && third == 0xbf) {
                        bomAllowed = false;
                        continue;
                    }
                    return false;
                }
                bomAllowed = false;
                if (!Character.isWhitespace((char) value)) return value == '{';
            }
        }
        return false;
    }

    static boolean looksLikeLegacyPec(File file) throws IOException {
        byte[] buffer;
        int length;
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            input.mark(64 * 1024);
            int first = input.read();
            int scanned = 1;
            if (first == 0xef) {
                if (input.read() != 0xbb || input.read() != 0xbf) return false;
                first = input.read();
                scanned = 4;
            }
            while (first >= 0 && first <= 32 && first != '\n' && scanned < 64 * 1024) {
                first = input.read(); scanned++;
            }
            if (scanned >= 64 * 1024) return false;
            if (!(first >= '0' && first <= '9') && first != '+' && first != '-' && first != '.') return false;
            input.reset();
            buffer = new byte[64 * 1024];
            length = 0;
            int count;
            while (length < buffer.length
                    && (count = input.read(buffer, length, buffer.length - length)) != -1) {
                length += count;
            }
        }
        if (length <= 0) return false;
        String prefix = new String(buffer, 0, length, StandardCharsets.UTF_8);
        String[] lines = prefix.split("\\r?\\n", 257);
        if (lines.length < 2) return false;
        String first = lines[0];
        if (!first.isEmpty() && first.charAt(0) == '\ufeff') first = first.substring(1);
        try {
            double offset = Double.parseDouble(first.trim());
            if (!Double.isFinite(offset)) return false;
        } catch (NumberFormatException ignored) {
            return false;
        }

        boolean hasBpm = false;
        boolean hasCommand = false;
        for (int index = 1; index < lines.length; index++) {
            String trimmed = lines[index].trim();
            if (trimmed.startsWith("bp ")) hasBpm = true;
            if (trimmed.matches("(?:n[1-4]|cp|cm|cd|ca|cv|cr|cf)(?:\\s+.*)?")) {
                hasCommand = true;
            }
            if (hasBpm && hasCommand) return true;
        }
        return false;
    }
}
