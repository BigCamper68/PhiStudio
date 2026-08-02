package com.xpe.mobile.packageio;

import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;

import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Creates a new app-private RPE project workspace without touching external files. */
public final class ProjectWorkspaceCreator {
    public static final class Spec {
        public final String projectId;
        public final String name;
        public final String composer;
        public final String charter;
        public final String level;
        public final double baseBpm;
        public final String audioExtension;
        public final String illustrationExtension;

        public Spec(String projectId, String name, String composer, String charter, String level,
                    double baseBpm, String audioExtension, String illustrationExtension) {
            this.projectId = requireText(projectId, "Project ID");
            this.name = requireText(name, "Project name");
            this.composer = safeText(composer);
            this.charter = safeText(charter);
            this.level = safeText(level);
            if (!Double.isFinite(baseBpm) || baseBpm <= 0.0) {
                throw new IllegalArgumentException("Base BPM must be positive and finite");
            }
            this.baseBpm = baseBpm;
            this.audioExtension = requireExtension(audioExtension, "Audio");
            this.illustrationExtension = requireExtension(
                    illustrationExtension, "Illustration");
        }
    }

    private static final String CHART_PATH = "chart.json";
    private static final String MANIFEST_PATH = "info.yml";
    private static final String REPHIEDIT_INFO_PATH = "info.txt";

    private final PackageLimits limits;

    public ProjectWorkspaceCreator() {
        this(PackageLimits.DEFAULT);
    }

    public ProjectWorkspaceCreator(PackageLimits limits) {
        this.limits = limits;
    }

    public ChartPackage create(File workspace, Spec spec,
                               InputStream audioInput, InputStream illustrationInput)
            throws IOException {
        if (workspace == null || spec == null) throw new IOException("Project workspace is missing");
        if (audioInput == null || illustrationInput == null) {
            throw new IOException("Audio and illustration are required for a new project");
        }
        if (workspace.exists()) throw new IOException("Project workspace already exists");
        if (!workspace.mkdirs()) throw new IOException("Unable to create project workspace");

        String audioPath = "audio." + spec.audioExtension;
        String illustrationPath = "illustration." + spec.illustrationExtension;
        long[] totalBytes = new long[]{0L};
        try {
            ChartDocument chart = createChart(spec, audioPath, illustrationPath);
            writeBytes(new File(workspace, CHART_PATH), serializeChart(chart), totalBytes);
            String manifest = PhiraManifestCompat.normalize("", chart, CHART_PATH,
                    audioPath, illustrationPath, 0L, false);
            writeBytes(new File(workspace, MANIFEST_PATH),
                    manifest.getBytes(StandardCharsets.UTF_8), totalBytes);
            String rePhiInfo = RePhiEditInfoCompat.normalize("", chart, CHART_PATH,
                    audioPath, illustrationPath);
            writeBytes(new File(workspace, REPHIEDIT_INFO_PATH),
                    rePhiInfo.getBytes(StandardCharsets.UTF_8), totalBytes);
            copyAsset(audioInput, new File(workspace, audioPath), totalBytes);
            copyAsset(illustrationInput, new File(workspace, illustrationPath), totalBytes);
            return new PackageWorkspaceLoader(limits).load(
                    workspace, safeSourceDisplayName(spec.name));
        } catch (IOException | RuntimeException | OutOfMemoryError exception) {
            try {
                deleteRecursively(workspace);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private static ChartDocument createChart(Spec spec, String audioPath,
                                             String illustrationPath) {
        ChartDocument chart = new ChartDocument();
        chart.name = spec.name;
        chart.composer = spec.composer;
        chart.charter = spec.charter;
        chart.level = spec.level;
        chart.id = spec.projectId;
        chart.song = audioPath;
        chart.background = illustrationPath;
        BpmChange bpm = new BpmChange();
        bpm.bpm = spec.baseBpm;
        chart.bpmChanges.add(bpm);
        JudgeLine lineZero = new JudgeLine();
        lineZero.name = "Line 0";
        chart.judgeLines.add(lineZero);
        return chart;
    }

    private static byte[] serializeChart(ChartDocument chart) throws IOException {
        try {
            return chart.toJsonString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException exception) {
            throw new IOException("Unable to serialize new chart", exception);
        }
    }

    private void writeBytes(File file, byte[] bytes, long[] totalBytes) throws IOException {
        if (bytes.length > limits.maxEntryBytes) {
            throw new PackageException(PackageException.Code.ENTRY_SIZE_LIMIT,
                    "New project entry exceeds the size limit: " + file.getName());
        }
        addTotal(bytes.length, totalBytes);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
    }

    private void copyAsset(InputStream input, File target, long[] totalBytes) throws IOException {
        if (input == null) return;
        long entryBytes = 0L;
        try (FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                entryBytes += count;
                if (entryBytes > limits.maxEntryBytes) {
                    throw new PackageException(PackageException.Code.ENTRY_SIZE_LIMIT,
                            "New project asset exceeds the size limit: " + target.getName());
                }
                addTotal(count, totalBytes);
                output.write(buffer, 0, count);
            }
            output.flush();
            output.getFD().sync();
        }
    }

    private void addTotal(long count, long[] totalBytes) throws PackageException {
        totalBytes[0] += count;
        if (totalBytes[0] > limits.maxTotalBytes) {
            throw new PackageException(PackageException.Code.TOTAL_SIZE_LIMIT,
                    "New project exceeds the total workspace size limit");
        }
    }

    private static String safeSourceDisplayName(String name) {
        String normalized = name.replaceAll("[\\p{Cntrl}\\r\\n]", " ").trim();
        if (normalized.isEmpty()) normalized = "New chart";
        return normalized + ".pez";
    }

    private static String normalizeExtension(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith(".")) normalized = normalized.substring(1);
        if (!normalized.matches("[a-z0-9]{1,8}")) {
            throw new IllegalArgumentException("Asset extension is invalid");
        }
        return normalized;
    }

    private static String requireExtension(String value, String label) {
        String normalized = normalizeExtension(value);
        if (normalized == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return normalized;
    }

    private static String requireText(String value, String label) {
        String normalized = safeText(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " cannot be empty");
        if (normalized.length() > 160) throw new IllegalArgumentException(label + " is too long");
        return normalized;
    }

    private static String safeText(String value) {
        String input = value == null ? "" : value.trim();
        StringBuilder output = new StringBuilder(input.length());
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            output.append(Character.isISOControl(character) ? ' ' : character);
        }
        return output.toString().trim();
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("Unable to remove incomplete project workspace");
        }
    }
}
