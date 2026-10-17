package com.xpe.mobile.project;

import org.json.JSONException;
import org.json.JSONObject;

public final class ProjectRecord {
    private final String id;
    private final String name;
    private final String sourceDisplayName;
    private final String chartPath;
    private final String audioPath;
    private final String illustrationPath;
    private final long importedAtMillis;
    private final long lastOpenedAtMillis;

    ProjectRecord(String id, String name, String sourceDisplayName, String chartPath,
                  String audioPath, String illustrationPath, long importedAtMillis,
                  long lastOpenedAtMillis) {
        this.id = id;
        this.name = name;
        this.sourceDisplayName = sourceDisplayName;
        this.chartPath = chartPath;
        this.audioPath = audioPath;
        this.illustrationPath = illustrationPath;
        this.importedAtMillis = importedAtMillis;
        this.lastOpenedAtMillis = lastOpenedAtMillis;
    }

    static ProjectRecord fromJson(JSONObject json) throws JSONException {
        return new ProjectRecord(
                json.getString("id"),
                json.optString("name", "Imported project"),
                json.optString("sourceDisplayName", "Imported package.zip"),
                json.getString("chartPath"),
                nullableString(json, "audioPath"),
                nullableString(json, "illustrationPath"),
                json.optLong("importedAtMillis", 0L),
                json.optLong("lastOpenedAtMillis", 0L));
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject()
                .put("id", id)
                .put("name", name)
                .put("sourceDisplayName", sourceDisplayName)
                .put("chartPath", chartPath)
                .put("importedAtMillis", importedAtMillis)
                .put("lastOpenedAtMillis", lastOpenedAtMillis);
        json.put("audioPath", audioPath == null ? JSONObject.NULL : audioPath);
        json.put("illustrationPath", illustrationPath == null ? JSONObject.NULL : illustrationPath);
        return json;
    }

    ProjectRecord withLastOpened(long value) {
        return new ProjectRecord(id, name, sourceDisplayName, chartPath, audioPath,
                illustrationPath, importedAtMillis, value);
    }

    ProjectRecord withName(String value) {
        return new ProjectRecord(id, value, sourceDisplayName, chartPath, audioPath,
                illustrationPath, importedAtMillis, lastOpenedAtMillis);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSourceDisplayName() {
        return sourceDisplayName;
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

    public long getImportedAtMillis() {
        return importedAtMillis;
    }

    public long getLastOpenedAtMillis() {
        return lastOpenedAtMillis;
    }

    public boolean hasAudio() {
        return audioPath != null;
    }

    public boolean hasIllustration() {
        return illustrationPath != null;
    }

    private static String nullableString(JSONObject json, String key) {
        if (!json.has(key) || json.isNull(key)) return null;
        String value = json.optString(key, "").trim();
        return value.isEmpty() ? null : value;
    }
}
