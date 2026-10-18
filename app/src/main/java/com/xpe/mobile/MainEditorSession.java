package com.xpe.mobile;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.AtomicFile;
import android.util.Log;
import com.xpe.mobile.io.ChartIo;
import com.xpe.mobile.model.ChartDocument;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.json.JSONObject;

/** A background checkpoint is independent of periodic autosave and never touches the source ZIP. */
final class MainEditorSession {
    private static final String KEY = "editor_session_token";
    private final MainActivity activity;
    private String token = UUID.randomUUID().toString(), pendingToken;
    private JSONObject pending;
    private Long pendingAudioPosition;
    private boolean restoreProjects, taskReplay, restoredSession;

    MainEditorSession(MainActivity activity) { this.activity = activity; }

    void restoreState(Bundle state) {
        Intent intent = activity.getIntent();
        taskReplay = intent != null && (intent.getFlags()
                & (Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY | Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT)) != 0;
        String restored = state == null ? null : state.getString(KEY);
        if (restored != null) { token = restored; pendingToken = restored; }
    }

    private AtomicFile file() {
        return new AtomicFile(new File(activity.getNoBackupFilesDir(), "editor-session.json"));
    }

    void readPendingState() {
        if (pendingToken == null && !taskReplay) return;
        try (java.io.InputStream stream = file().openRead()) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024]; int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            JSONObject saved = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            boolean matches = pendingToken != null ? pendingToken.equals(saved.optString("token"))
                    : saved.has("task") && saved.optInt("task") == activity.getTaskId();
            String savedToken = saved.optString("token");
            if (matches && !savedToken.isEmpty()) {
                token = savedToken; pendingToken = savedToken; pending = saved;
            }
        } catch (Exception exception) { Log.w("PhiStudioSession", "Unable to read editor checkpoint", exception); }
    }

    void saveState(Bundle state) {
        if (checkpoint() || pendingToken != null) state.putString(KEY, token);
    }

    boolean checkpoint() {
        ChartDocument chart = activity.editorView == null ? null : activity.editorView.getChart();
        if (chart == null) return false;
        try {
            if (activity.currentPackage != null && activity.currentProjectId != null) {
                activity.mainProjectSession.saveCurrentProject();
            } else if (activity.editorView.isChartDirty()) {
                ChartIo.writeAutosave(activity, chart); activity.editorView.markChartSaved();
            }
            Uri audio = activity.audioController == null ? null : activity.audioController.sourceUri();
            JSONObject snapshot = new JSONObject().put("token", token).put("task", activity.getTaskId())
                    .put("project", activity.currentProjectId == null ? JSONObject.NULL : activity.currentProjectId)
                    .put("chart", chart.id).put("editor", activity.editorView.saveEditorSession())
                    .put("audio", audio == null ? JSONObject.NULL : audio.toString())
                    .put("projects", activity.projectBrowser != null && activity.projectBrowser.isShowing())
                    .put("shaders", activity.workbench != null && activity.workbench.isShaderEditorOpen());
            AtomicFile file = file(); FileOutputStream output = null;
            try {
                output = file.startWrite();
                output.write(snapshot.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
                file.finishWrite(output); output = null;
            } finally { if (output != null) file.failWrite(output); }
            pendingToken = token;
            if (!activity.isAudioReady()) pendingAudioPosition = 0L;
            return true;
        } catch (Exception exception) {
            Log.w("PhiStudioSession", "Unable to checkpoint editor session", exception); return false;
        }
    }

    void apply() {
        JSONObject saved = pending; pending = null;
        ChartDocument chart = activity.editorView.getChart();
        if (!matchesDocument(saved, activity.currentProjectId, chart)) return;
        try {
            activity.editorView.restoreEditorSession(saved.getJSONObject("editor"));
            restoreProjects = saved.optBoolean("projects");
            restoredSession = true;
            if (saved.optBoolean("shaders") && activity.currentPackage != null) activity.mainShaderSession.openShaderEditor();
            pendingAudioPosition = chart.beatToAudioMillis(activity.editorView.getCurrentBeat(), activity.currentPackageOffsetMs());
            audioStateChanged();
        } catch (Exception exception) { Log.w("PhiStudioSession", "Unable to restore editor checkpoint", exception); }
    }

    boolean restoreProjects() { return restoreProjects; }
    boolean restoredSession() { return restoredSession; }

    Uri restoredAudioSource(String projectId, ChartDocument chart) {
        if (!matchesDocument(pending, projectId, chart) || pending.isNull("audio")) return null;
        String source = pending.optString("audio");
        return source.isEmpty() ? null : Uri.parse(source);
    }

    private boolean matchesDocument(JSONObject saved, String projectId, ChartDocument chart) {
        if (saved == null || chart == null) return false;
        String project = saved.isNull("project") ? null : saved.optString("project", null);
        return Objects.equals(project, projectId) && Objects.equals(saved.optString("chart"), chart.id);
    }

    void audioStateChanged() {
        if (pendingAudioPosition != null && activity.audioController != null && activity.audioController.isReady()) {
            long position = activity.editorView.getChart().beatToAudioMillis(
                    activity.editorView.getCurrentBeat(), activity.currentPackageOffsetMs());
            pendingAudioPosition = null;
            activity.audioController.seek(position);
        }
    }
}
