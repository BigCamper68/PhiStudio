package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.packageio.PackageImporter.WorkspaceAnalysis;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONObject;

/** PackageResourceSelection responsibilities extracted from PackageImporter. */
final class PackageResourceSelection {
    private final PackageImporter delegateOwner;

    PackageResourceSelection(PackageImporter delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    List<PackageManifest> readManifests(File workspace, List<ChartPackage.Entry> entries)
            throws IOException {
        List<PackageManifest> manifests = new ArrayList<>();
        for (ChartPackage.Entry entry : entries) {
            if (entry.isDirectory() || entry.getPath().contains("/")) continue;
            String lower = entry.getPath().toLowerCase(Locale.ROOT);
            PackageManifest.Kind kind;
            if (lower.equals("info.yml") || lower.equals("info.yaml")) {
                kind = PackageManifest.Kind.YAML;
            } else if (lower.equals("info.txt")) {
                kind = PackageManifest.Kind.TEXT;
            } else {
                continue;
            }
            if (entry.getSize() <= PackageImporter.MAX_PARSED_MANIFEST_BYTES) {
                manifests.add(PackageManifest.parse(entry.getPath(), kind,
                        PackageImporter.readUtf8(PackageImporter.resolveInside(workspace, entry.getPath()))));
            }
        }
        manifests.sort(Comparator.comparing(PackageManifest::getKind)
                .thenComparing(PackageManifest::getPath));
        return manifests;
    }

    static String selectResource(List<PackageManifest> manifests,
                                         List<ChartPackage.Entry> entries,
                                         Set<String> filePaths,
                                         String[] keys, String[] extensions, String chartPath,
                                         String metaPath) throws PackageException {
        for (PackageManifest manifest : manifests) {
            for (String key : keys) {
                String value = manifest.get(key);
                if (value == null || value.trim().isEmpty()) continue;
                String path;
                try {
                    path = PackageImporter.resolveManifestPath(manifest.getPath(), value);
                } catch (PackageException exception) {
                    throw new PackageException(PackageException.Code.INVALID_MANIFEST_PATH,
                            "Manifest contains an unsafe resource path: " + value);
                }
                if (filePaths.contains(path)) return path;
            }
        }

        // Multiple hit-sound assets must not make the chart's main music ambiguous.
        if (metaPath != null && !metaPath.trim().isEmpty()) {
            try {
                String path = PackageImporter.normalizePath(metaPath.trim());
                if (filePaths.contains(path)) return path;
                path = PackageImporter.resolveManifestPath(chartPath, metaPath);
                if (filePaths.contains(path)) return path;
            } catch (PackageException ignored) {
                // Unsafe/missing optional META hints never access files outside the package.
            }
        }

        List<String> matches = new ArrayList<>();
        for (ChartPackage.Entry entry : entries) {
            if (entry.isDirectory()) continue;
            String lower = entry.getPath().toLowerCase(Locale.ROOT);
            for (String extension : extensions) {
                if (lower.endsWith("." + extension)) {
                    matches.add(entry.getPath());
                    break;
                }
            }
        }
        Collections.sort(matches);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    static String metaAsset(JSONObject root, String key) {
        JSONObject meta = root == null ? null : root.optJSONObject("META");
        return meta == null ? "" : meta.optString(key, "");
    }

    static String selectProjectName(List<PackageManifest> manifests,
                                            JSONObject chartRoot, String sourceDisplayName) {
        for (PackageManifest manifest : manifests) {
            String value = manifest.get("name");
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        JSONObject meta = chartRoot == null ? null : chartRoot.optJSONObject("META");
        String chartName = meta == null ? "" : meta.optString("name", "").trim();
        if (!chartName.isEmpty() && !chartName.equals("Untitled")) {
            return chartName;
        }
        String value = sourceDisplayName == null ? "Imported package" : sourceDisplayName.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) value = value.substring(0, value.length() - 4);
        lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pez")) value = value.substring(0, value.length() - 4);
        return value.isEmpty() ? "Imported package" : value;
    }

    static long selectManifestOffsetMs(List<PackageManifest> manifests) {
        for (PackageManifest manifest : manifests) {
            if (manifest.getKind() != PackageManifest.Kind.YAML) continue;
            String value = manifest.get("offset");
            if (value == null || value.trim().isEmpty()) continue;
            try {
                return new BigDecimal(value.trim()).movePointRight(3)
                        .setScale(0, RoundingMode.HALF_UP).longValueExact();
            } catch (ArithmeticException | NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    static boolean selectUseRpe170Speed(List<PackageManifest> manifests) {
        for (PackageManifest manifest : manifests) {
            if (manifest.getKind() != PackageManifest.Kind.YAML) continue;
            String value = manifest.get("useRpe170Speed");
            if (value == null) continue;
            return Boolean.parseBoolean(value.trim());
        }
        return false;
    }

    static void applyManifestMetadata(ChartDocument chart, WorkspaceAnalysis analysis) {
        chart.name = PackageResourceSelection.firstManifestValue(analysis.manifests, "name", analysis.projectName);
        chart.composer = PackageResourceSelection.firstManifestValue(analysis.manifests, "composer", chart.composer);
        chart.charter = PackageResourceSelection.firstManifestValue(analysis.manifests, "charter", chart.charter);
        chart.level = PackageResourceSelection.firstManifestValue(analysis.manifests, "level", chart.level);
        chart.id = PackageResourceSelection.firstManifestValue(analysis.manifests, "id", chart.id);
        chart.song = analysis.audioPath == null ? "" : analysis.audioPath;
        chart.background = analysis.illustrationPath == null ? "" : analysis.illustrationPath;
    }

    static void applyDisplayMetadata(ChartDocument chart, WorkspaceAnalysis analysis) {
        chart.setPackageDisplayMetadata(
                firstManifestValue(analysis.manifests, "name", chart.name),
                firstManifestValue(analysis.manifests, "level", chart.level),
                firstManifestValue(analysis.manifests, "composer", chart.composer),
                firstManifestValue(analysis.manifests, "charter", chart.charter));
    }

    static String firstManifestValue(List<PackageManifest> manifests,
                                             String key, String fallback) {
        for (PackageManifest manifest : manifests) {
            String value = manifest.get(key);
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return fallback == null ? "" : fallback;
    }
}
