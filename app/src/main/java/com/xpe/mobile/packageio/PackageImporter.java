package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

public final class PackageImporter {
    final PackageArchiveReader packageArchiveReader = new PackageArchiveReader(this);
    final PackageChartSelection packageChartSelection = new PackageChartSelection(this);
    final PackageResourceSelection packageResourceSelection = new PackageResourceSelection(this);

    static final long MAX_PARSED_MANIFEST_BYTES = 1024L * 1024L;

    final PackageLimits limits;

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
            List<ChartPackage.Entry> entries = packageArchiveReader.extract(input, workspace);
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
        } catch (OutOfMemoryError error) {
            deleteRecursively(workspace);
            throw error;
        }
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
        if (converted) PackageResourceSelection.applyManifestMetadata(chart, analysis);
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
        List<PackageManifest> manifests = packageResourceSelection.readManifests(workspace, entries);
        List<JsonCandidate> rpeCandidates = new ArrayList<>();
        List<JsonCandidate> officialCandidates = new ArrayList<>();
        List<JsonCandidate> legacyPecCandidates = new ArrayList<>();
        Set<String> filePaths = new HashSet<>();
        for (ChartPackage.Entry entry : entries) {
            if (entry.isDirectory()) continue;
            filePaths.add(entry.getPath());
            File file = resolveInside(workspace, entry.getPath());
            if (looksLikeLegacyPec(file)) {
                legacyPecCandidates.add(new JsonCandidate(
                        entry.getPath(), ChartJsonFormat.LEGACY_PEC));
                continue;
            }
            if (!looksLikeJsonObject(file)) continue;
            String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (!source.isEmpty() && source.charAt(0) == '\ufeff') source = source.substring(1);
            try {
                JSONObject root = new JSONObject(source);
                ChartJsonFormat format = ChartJsonFormatDetector.detect(root);
                if (format == ChartJsonFormat.RPE) {
                    rpeCandidates.add(new JsonCandidate(entry.getPath(), format));
                } else if (format == ChartJsonFormat.OFFICIAL_PHIGROS) {
                    officialCandidates.add(new JsonCandidate(entry.getPath(), format));
                }
            } catch (Exception ignored) {
                // Non-chart JSON is an unknown package resource and remains preserved.
            }
        }

        JsonCandidate selected = PackageChartSelection.loadCandidate(workspace, packageChartSelection.selectChart(
                manifests, rpeCandidates, officialCandidates, legacyPecCandidates));
        if (selected.format == ChartJsonFormat.OFFICIAL_PHIGROS) {
            PackageChartSelection.validateOfficialCandidate(selected.root);
        }
        String audioPath = PackageResourceSelection.selectResource(manifests, entries, filePaths,
                new String[]{"music", "song"}, new String[]{"ogg", "mp3", "wav", "flac", "m4a", "aac"},
                selected.path, PackageResourceSelection.metaAsset(selected.root, "song"));
        String illustrationPath = PackageResourceSelection.selectResource(manifests, entries, filePaths,
                new String[]{"illustration", "picture", "background"},
                new String[]{"png", "jpg", "jpeg", "webp", "bmp"},
                selected.path, PackageResourceSelection.metaAsset(selected.root, "background"));
        String projectName = PackageResourceSelection.selectProjectName(manifests, selected.root, sourceDisplayName);
        long manifestOffsetMs = PackageResourceSelection.selectManifestOffsetMs(manifests);
        boolean useRpe170Speed = PackageResourceSelection.selectUseRpe170Speed(manifests);
        return new WorkspaceAnalysis(manifests, selected, audioPath, illustrationPath,
                projectName, manifestOffsetMs, useRpe170Speed);
    }

    static boolean looksLikeJsonObject(File file) throws IOException {
        return PackageChartSelection.looksLikeJsonObject(file);
    }

    static boolean looksLikeLegacyPec(File file) throws IOException {
        return PackageChartSelection.looksLikeLegacyPec(file);
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

    static String resolveManifestPath(String manifestPath, String value) throws PackageException {
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

    static void duplicate(String path) throws PackageException {
        PackageArchiveReader.duplicate(path);
    }

    static Map<String, JsonCandidate> byPath(List<JsonCandidate> candidates) {
        Map<String, JsonCandidate> result = new HashMap<>();
        for (JsonCandidate candidate : candidates) result.put(candidate.path, candidate);
        return result;
    }

    static String readUtf8(File file) throws IOException {
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

    static final class JsonCandidate {
        final String path;
        final JSONObject root;
        final String source;
        final ChartJsonFormat format;

        JsonCandidate(String path, ChartJsonFormat format) {
            this(path, null, null, format);
        }

        JsonCandidate(String path, JSONObject root, String source, ChartJsonFormat format) {
            this.path = path;
            this.root = root;
            this.source = source;
            this.format = format;
        }
    }

    static final class WorkspaceAnalysis {
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

    static final class ArchiveLimitIOException extends IOException {
        private static final long serialVersionUID = 1L;

        ArchiveLimitIOException() {
            super("Compressed package size limit exceeded");
        }
    }

    static final class BoundedArchiveInputStream extends FilterInputStream {
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
