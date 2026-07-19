package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;

import org.json.JSONObject;
import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class PackageImporter {
    private static final long MAX_PARSED_MANIFEST_BYTES = 1024L * 1024L;

    private final PackageLimits limits;

    public PackageImporter() {
        this(PackageLimits.DEFAULT);
    }

    public PackageImporter(PackageLimits limits) {
        this.limits = limits;
    }

    public ChartPackage importPackage(InputStream input, File workspace, String sourceDisplayName)
            throws IOException {
        if (input == null) throw new IOException("Unable to open selected package");
        prepareWorkspace(workspace);
        boolean extractionComplete = false;
        try {
            List<ChartPackage.Entry> entries = extract(input, workspace);
            extractionComplete = true;
            return inspectWorkspace(workspace, sourceDisplayName, entries);
        } catch (PackageException exception) {
            if (extractionComplete && isFormatSelectionError(exception.getCode())) {
                throw new PackageException(exception.getCode(), exception.getMessage(), workspace);
            }
            deleteRecursively(workspace);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            deleteRecursively(workspace);
            throw exception;
        }
    }

    private List<ChartPackage.Entry> extract(InputStream source, File workspace) throws IOException {
        List<ChartPackage.Entry> entries = new ArrayList<>();
        Map<String, Boolean> knownPaths = new HashMap<>();
        long totalBytes = 0L;
        BoundedArchiveInputStream bounded = new BoundedArchiveInputStream(source, limits.maxArchiveBytes);
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(bounded))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entries.size() >= limits.maxEntries) {
                    throw new PackageException(PackageException.Code.ENTRY_COUNT_LIMIT,
                            "Package has more than " + limits.maxEntries + " entries");
                }
                if (entry.getCompressedSize() > limits.maxCompressedEntryBytes) {
                    throw new PackageException(PackageException.Code.COMPRESSED_SIZE_LIMIT,
                            "Compressed entry is too large: " + entry.getName());
                }
                String path = normalizePath(entry.getName());
                boolean directory = entry.isDirectory() || entry.getName().endsWith("/")
                        || entry.getName().endsWith("\\");
                registerPath(knownPaths, path, directory);
                File output = resolveInside(workspace, path);
                if (directory) {
                    if (!output.isDirectory() && !output.mkdirs()) {
                        throw new PackageException(PackageException.Code.WORKSPACE_ERROR,
                                "Unable to create workspace directory: " + path);
                    }
                    entries.add(new ChartPackage.Entry(path, true, 0L));
                    zip.closeEntry();
                    continue;
                }

                File parent = output.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new PackageException(PackageException.Code.WORKSPACE_ERROR,
                            "Unable to create workspace directory for: " + path);
                }
                long entryBytes = 0L;
                try (FileOutputStream file = new FileOutputStream(output)) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        entryBytes += count;
                        totalBytes += count;
                        if (entryBytes > limits.maxEntryBytes) {
                            throw new PackageException(PackageException.Code.ENTRY_SIZE_LIMIT,
                                    "Entry exceeds the " + limits.maxEntryBytes + " byte limit: " + path);
                        }
                        if (totalBytes > limits.maxTotalBytes) {
                            throw new PackageException(PackageException.Code.TOTAL_SIZE_LIMIT,
                                    "Package exceeds the " + limits.maxTotalBytes + " byte extracted-size limit");
                        }
                        file.write(buffer, 0, count);
                    }
                }
                entries.add(new ChartPackage.Entry(path, false, entryBytes));
                zip.closeEntry();
            }
        } catch (ArchiveLimitIOException exception) {
            throw new PackageException(PackageException.Code.ARCHIVE_SIZE_LIMIT,
                    "Package exceeds the " + limits.maxArchiveBytes + " byte compressed-size limit", exception);
        }
        return entries;
    }

    ChartPackage inspectWorkspace(File workspace, String sourceDisplayName,
                                  List<ChartPackage.Entry> entries) throws IOException {
        WorkspaceAnalysis analysis = analyzeWorkspace(workspace, sourceDisplayName, entries);
        ChartDocument chart;
        if (analysis.selected.format == ChartJsonFormat.OFFICIAL_PHIGROS) {
            chart = OfficialPhigrosConverter.convert(analysis.selected.root);
        } else if (analysis.selected.format == ChartJsonFormat.LEGACY_PEC) {
            chart = LegacyPecConverter.convert(analysis.selected.source);
        } else {
            try {
                chart = ChartDocument.fromParsedJson(analysis.selected.root);
            } catch (JSONException exception) {
                throw new IOException("Unable to parse the selected RPE chart JSON", exception);
            }
        }
        boolean converted = analysis.selected.format != ChartJsonFormat.RPE;
        if (converted) applyManifestMetadata(chart, analysis);
        ChartPackage result = new ChartPackage(workspace, sourceDisplayName, analysis.projectName,
                analysis.selected.path, analysis.audioPath, analysis.illustrationPath,
                analysis.manifestOffsetMs, analysis.useRpe170Speed,
                chart, entries, analysis.manifests);
        if (converted) PackageWorkspaceWriter.writeChart(result, chart);
        return result;
    }

    String inspectWorkspaceProjectName(File workspace, String sourceDisplayName,
                                       List<ChartPackage.Entry> entries) throws IOException {
        return analyzeWorkspace(workspace, sourceDisplayName, entries).projectName;
    }

    private WorkspaceAnalysis analyzeWorkspace(File workspace, String sourceDisplayName,
                                                List<ChartPackage.Entry> entries) throws IOException {
        List<PackageManifest> manifests = readManifests(workspace, entries);
        List<JsonCandidate> rpeCandidates = new ArrayList<>();
        List<JsonCandidate> officialCandidates = new ArrayList<>();
        List<JsonCandidate> legacyPecCandidates = new ArrayList<>();
        Set<String> filePaths = new HashSet<>();
        for (ChartPackage.Entry entry : entries) {
            if (entry.isDirectory()) continue;
            filePaths.add(entry.getPath());
            File file = resolveInside(workspace, entry.getPath());
            if (looksLikeLegacyPec(file)) {
                legacyPecCandidates.add(new JsonCandidate(entry.getPath(), null,
                        readUtf8(file), ChartJsonFormat.LEGACY_PEC));
                continue;
            }
            if (!looksLikeJsonObject(file)) continue;
            String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (!source.isEmpty() && source.charAt(0) == '\ufeff') source = source.substring(1);
            try {
                JSONObject root = new JSONObject(source);
                ChartJsonFormat format = ChartJsonFormatDetector.detect(root);
                if (format == ChartJsonFormat.RPE) {
                    rpeCandidates.add(new JsonCandidate(entry.getPath(), root, null, format));
                } else if (format == ChartJsonFormat.OFFICIAL_PHIGROS) {
                    officialCandidates.add(new JsonCandidate(entry.getPath(), root, null, format));
                }
            } catch (Exception ignored) {
                // Non-chart JSON is an unknown package resource and remains preserved.
            }
        }

        JsonCandidate selected = selectChart(
                manifests, rpeCandidates, officialCandidates, legacyPecCandidates);
        if (selected.format == ChartJsonFormat.OFFICIAL_PHIGROS) {
            validateOfficialCandidate(selected.root);
        }
        String audioPath = selectResource(manifests, entries, filePaths,
                new String[]{"music", "song"}, new String[]{"ogg", "mp3", "wav", "flac", "m4a", "aac"});
        String illustrationPath = selectResource(manifests, entries, filePaths,
                new String[]{"illustration", "picture", "background"},
                new String[]{"png", "jpg", "jpeg", "webp", "bmp"});
        String projectName = selectProjectName(manifests, selected.root, sourceDisplayName);
        long manifestOffsetMs = selectManifestOffsetMs(manifests);
        boolean useRpe170Speed = selectUseRpe170Speed(manifests);
        return new WorkspaceAnalysis(manifests, selected, audioPath, illustrationPath,
                projectName, manifestOffsetMs, useRpe170Speed);
    }

    private List<PackageManifest> readManifests(File workspace, List<ChartPackage.Entry> entries)
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
            if (entry.getSize() <= MAX_PARSED_MANIFEST_BYTES) {
                manifests.add(PackageManifest.parse(entry.getPath(), kind,
                        readUtf8(resolveInside(workspace, entry.getPath()))));
            }
        }
        manifests.sort(Comparator.comparing(PackageManifest::getKind)
                .thenComparing(PackageManifest::getPath));
        return manifests;
    }

    private JsonCandidate selectChart(List<PackageManifest> manifests,
                                      List<JsonCandidate> rpeCandidates,
                                      List<JsonCandidate> officialCandidates,
                                      List<JsonCandidate> legacyPecCandidates) throws PackageException {
        Map<String, JsonCandidate> rpeByPath = byPath(rpeCandidates);
        Map<String, JsonCandidate> officialByPath = byPath(officialCandidates);
        Map<String, JsonCandidate> pecByPath = byPath(legacyPecCandidates);
        Set<String> hints = new LinkedHashSet<>();
        for (PackageManifest manifest : manifests) {
            String hint = manifest.get("chart");
            if (hint == null || hint.trim().isEmpty()) continue;
            try {
                hints.add(resolveManifestPath(manifest.getPath(), hint));
            } catch (PackageException exception) {
                throw new PackageException(PackageException.Code.INVALID_MANIFEST_PATH,
                        "Manifest contains an unsafe chart path: " + hint);
            }
        }

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

    private static String selectResource(List<PackageManifest> manifests,
                                         List<ChartPackage.Entry> entries,
                                         Set<String> filePaths,
                                         String[] keys, String[] extensions) throws PackageException {
        for (PackageManifest manifest : manifests) {
            for (String key : keys) {
                String value = manifest.get(key);
                if (value == null || value.trim().isEmpty()) continue;
                String path;
                try {
                    path = resolveManifestPath(manifest.getPath(), value);
                } catch (PackageException exception) {
                    throw new PackageException(PackageException.Code.INVALID_MANIFEST_PATH,
                            "Manifest contains an unsafe resource path: " + value);
                }
                if (filePaths.contains(path)) return path;
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

    private static String selectProjectName(List<PackageManifest> manifests,
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

    private static long selectManifestOffsetMs(List<PackageManifest> manifests) {
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

    private static boolean selectUseRpe170Speed(List<PackageManifest> manifests) {
        for (PackageManifest manifest : manifests) {
            if (manifest.getKind() != PackageManifest.Kind.YAML) continue;
            String value = manifest.get("useRpe170Speed");
            if (value == null) continue;
            return Boolean.parseBoolean(value.trim());
        }
        return false;
    }

    private static void validateOfficialCandidate(JSONObject root) throws PackageException {
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

    private static void applyManifestMetadata(ChartDocument chart, WorkspaceAnalysis analysis) {
        chart.name = firstManifestValue(analysis.manifests, "name", analysis.projectName);
        chart.composer = firstManifestValue(analysis.manifests, "composer", "");
        chart.charter = firstManifestValue(analysis.manifests, "charter", "");
        chart.level = firstManifestValue(analysis.manifests, "level", "");
        chart.id = firstManifestValue(analysis.manifests, "id", "");
        chart.song = analysis.audioPath == null ? "" : analysis.audioPath;
        chart.background = analysis.illustrationPath == null ? "" : analysis.illustrationPath;
    }

    private static String firstManifestValue(List<PackageManifest> manifests,
                                             String key, String fallback) {
        for (PackageManifest manifest : manifests) {
            String value = manifest.get(key);
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return fallback == null ? "" : fallback;
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
        byte[] buffer = new byte[64 * 1024];
        int length;
        try (InputStream input = new FileInputStream(file)) {
            length = input.read(buffer);
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

    static String normalizePath(String raw) throws PackageException {
        if (raw == null || raw.isEmpty() || raw.indexOf('\0') >= 0) {
            throw new PackageException(PackageException.Code.UNSAFE_PATH, "Package contains an empty or invalid path");
        }
        String value = raw.replace('\\', '/');
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw new PackageException(PackageException.Code.UNSAFE_PATH,
                    "Package contains an absolute path: " + raw);
        }
        String[] parts = value.split("/+", -1);
        List<String> normalized = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                throw new PackageException(PackageException.Code.UNSAFE_PATH,
                        "Package path traversal is not allowed: " + raw);
            }
            normalized.add(part);
        }
        if (normalized.isEmpty()) {
            throw new PackageException(PackageException.Code.UNSAFE_PATH,
                    "Package contains an empty normalized path: " + raw);
        }
        return String.join("/", normalized);
    }

    private static String resolveManifestPath(String manifestPath, String value) throws PackageException {
        String trimmed = value.trim();
        int slash = manifestPath.lastIndexOf('/');
        String relative = slash < 0 ? trimmed : manifestPath.substring(0, slash + 1) + trimmed;
        return normalizePath(relative);
    }

    static File resolveInside(File workspace, String path) throws IOException {
        File output = new File(workspace, path.replace('/', File.separatorChar));
        String root = workspace.getCanonicalPath();
        String target = output.getCanonicalPath();
        if (!target.startsWith(root + File.separator)) {
            throw new PackageException(PackageException.Code.UNSAFE_PATH,
                    "Package path escapes the private workspace: " + path);
        }
        return output;
    }

    private static void registerPath(Map<String, Boolean> known, String path, boolean directory)
            throws PackageException {
        if (known.containsKey(path)) duplicate(path);
        int slash = path.indexOf('/');
        while (slash >= 0) {
            String parent = path.substring(0, slash);
            if (Boolean.FALSE.equals(known.get(parent))) duplicate(path);
            slash = path.indexOf('/', slash + 1);
        }
        if (!directory) {
            String prefix = path + "/";
            for (String existing : known.keySet()) {
                if (existing.startsWith(prefix)) duplicate(path);
            }
        }
        known.put(path, directory);
    }

    private static void duplicate(String path) throws PackageException {
        throw new PackageException(PackageException.Code.DUPLICATE_PATH,
                "Package contains duplicate or conflicting normalized path: " + path);
    }

    private static Map<String, JsonCandidate> byPath(List<JsonCandidate> candidates) {
        Map<String, JsonCandidate> result = new HashMap<>();
        for (JsonCandidate candidate : candidates) result.put(candidate.path, candidate);
        return result;
    }

    private static String readUtf8(File file) throws IOException {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void prepareWorkspace(File workspace) throws IOException {
        if (workspace == null) throw new IOException("Private package workspace is unavailable");
        if (workspace.exists()) {
            throw new PackageException(PackageException.Code.WORKSPACE_ERROR,
                    "Package workspace already exists: " + workspace.getAbsolutePath());
        }
        if (!workspace.mkdirs()) {
            throw new PackageException(PackageException.Code.WORKSPACE_ERROR,
                    "Unable to create private package workspace");
        }
    }

    private static boolean isFormatSelectionError(PackageException.Code code) {
        return code == PackageException.Code.MISSING_CHART
                || code == PackageException.Code.AMBIGUOUS_CHART
                || code == PackageException.Code.UNSUPPORTED_CHART_FORMAT;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private static final class JsonCandidate {
        final String path;
        final JSONObject root;
        final String source;
        final ChartJsonFormat format;

        JsonCandidate(String path, JSONObject root, String source, ChartJsonFormat format) {
            this.path = path;
            this.root = root;
            this.source = source;
            this.format = format;
        }
    }

    private static final class WorkspaceAnalysis {
        final List<PackageManifest> manifests;
        final JsonCandidate selected;
        final String audioPath;
        final String illustrationPath;
        final String projectName;
        final long manifestOffsetMs;
        final boolean useRpe170Speed;

        WorkspaceAnalysis(List<PackageManifest> manifests, JsonCandidate selected,
                          String audioPath, String illustrationPath, String projectName,
                          long manifestOffsetMs, boolean useRpe170Speed) {
            this.manifests = manifests;
            this.selected = selected;
            this.audioPath = audioPath;
            this.illustrationPath = illustrationPath;
            this.projectName = projectName;
            this.manifestOffsetMs = manifestOffsetMs;
            this.useRpe170Speed = useRpe170Speed;
        }
    }

    private static final class ArchiveLimitIOException extends IOException {
        ArchiveLimitIOException() {
            super("Compressed package size limit exceeded");
        }
    }

    private static final class BoundedArchiveInputStream extends FilterInputStream {
        private final long limit;
        private long count;

        BoundedArchiveInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) add(1L);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) add(read);
            return read;
        }

        private void add(long amount) throws ArchiveLimitIOException {
            count += amount;
            if (count > limit) throw new ArchiveLimitIOException();
        }
    }
}
