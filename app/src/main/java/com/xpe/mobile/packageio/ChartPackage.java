package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.preview.PhiraBackgroundMetrics;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ChartPackage {
    public static final class Entry {
        private final String path;
        private final boolean directory;
        private final long size;

        Entry(String path, boolean directory, long size) {
            this.path = path;
            this.directory = directory;
            this.size = size;
        }

        public String getPath() {
            return path;
        }

        public boolean isDirectory() {
            return directory;
        }

        public long getSize() {
            return size;
        }
    }

    private final File workspace;
    private final String sourceDisplayName;
    private final String projectName;
    private final String chartPath;
    private final String audioPath;
    private final String illustrationPath;
    private final long manifestOffsetMs;
    private final boolean useRpe170Speed;
    private final ChartDocument chart;
    private final List<Entry> entries;
    private final List<Entry> addedResources = new ArrayList<>();
    private final List<PackageManifest> manifests;

    ChartPackage(File workspace, String sourceDisplayName, String projectName,
                 String chartPath, String audioPath, String illustrationPath,
                 long manifestOffsetMs, boolean useRpe170Speed,
                 ChartDocument chart, List<Entry> entries,
                 List<PackageManifest> manifests) {
        this.workspace = workspace;
        this.sourceDisplayName = sourceDisplayName;
        this.projectName = projectName;
        this.chartPath = chartPath;
        this.audioPath = audioPath;
        this.illustrationPath = illustrationPath;
        this.manifestOffsetMs = manifestOffsetMs;
        this.useRpe170Speed = useRpe170Speed;
        this.chart = chart;
        List<Entry> packageEntries = new ArrayList<>(entries);
        boolean hasInfoTxt = false;
        for (Entry entry : packageEntries) {
            if (!entry.isDirectory() && InfoTxtManifestWriter.isInfoTxt(entry.getPath())) {
                hasInfoTxt = true;
                break;
            }
        }
        if (!hasInfoTxt) {
            // The file is generated during export; representing it here keeps package entry
            // accounting stable across the first export/import round trip.
            packageEntries.add(new Entry(InfoTxtManifestWriter.FILE_NAME, false, 0L));
        }
        this.entries = Collections.unmodifiableList(packageEntries);
        this.manifests = Collections.unmodifiableList(new ArrayList<>(manifests));
        if (chart.backgroundDim == null) chart.backgroundDim = manifestBackgroundDim();
        // Phira reads this flag from ChartInfo (YAML), never from RPE META.
        chart.useAttachUiFix = false;
        for (PackageManifest manifest : manifests) {
            if (manifest.getKind() != PackageManifest.Kind.YAML) continue;
            String flag = manifest.get("useAttachUiFix");
            if (flag == null) continue;
            int comment = flag.indexOf('#');
            if (comment >= 0) flag = flag.substring(0, comment);
            chart.useAttachUiFix = Boolean.parseBoolean(flag.trim());
            break;
        }
    }

    public File getWorkspace() {
        return workspace;
    }

    public String getSourceDisplayName() {
        return sourceDisplayName;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getChartPath() {
        return chartPath;
    }

    public String getAudioPath() {
        return audioPath;
    }

    public String getIllustrationPath() {
        return illustrationPath;
    }

    public long getManifestOffsetMs() {
        return manifestOffsetMs;
    }

    public boolean isUseRpe170Speed() {
        return useRpe170Speed;
    }

    public File getAudioFile() {
        return audioPath == null ? null : new File(workspace, audioPath);
    }

    public File getIllustrationFile() {
        return illustrationPath == null ? null : new File(workspace, illustrationPath);
    }

    public ChartDocument getChart() {
        return chart;
    }

    public double getBackgroundDim() {
        return chart.getBackgroundDim();
    }

    private double manifestBackgroundDim() {
        for (PackageManifest manifest : manifests) {
            if (manifest.getKind() == PackageManifest.Kind.YAML) {
                String value = manifest.get("backgroundDim");
                if (value != null) return PhiraBackgroundMetrics.chartDim(value);
            }
        }
        return PhiraBackgroundMetrics.DEFAULT_CHART_DIM;
    }

    public synchronized List<Entry> getEntries() {
        List<Entry> result = new ArrayList<>(entries);
        result.addAll(addedResources);
        return Collections.unmodifiableList(result);
    }

    /** New shader resources must participate in export before the project is reopened. */
    public synchronized void registerShaderResource(String path) throws java.io.IOException {
        if (!path.equals("extra.json") && !path.startsWith("shaders/")) throw new java.io.IOException("Invalid shader resource path");
        File file = PackageImporter.resolveInside(workspace, path);
        if (!file.isFile()) throw new java.io.IOException("Missing shader resource");
        for (Entry item : getEntries()) if (item.getPath().equals(path)) return;
        addedResources.add(new Entry(path, false, file.length()));
    }

    public List<PackageManifest> getManifests() {
        return manifests;
    }
}
