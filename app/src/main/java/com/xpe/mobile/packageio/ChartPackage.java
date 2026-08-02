package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;

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

    public List<Entry> getEntries() {
        return entries;
    }

    public List<PackageManifest> getManifests() {
        return manifests;
    }
}
