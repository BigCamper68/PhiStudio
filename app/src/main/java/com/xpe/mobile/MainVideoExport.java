package com.xpe.mobile;

import com.xpe.mobile.i18n.UiText;
import com.xpe.mobile.i18n.UiDialogs;
import com.xpe.mobile.i18n.UiErrors;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.preview.PreviewShaderLoader;
import com.xpe.mobile.preview.ShaderTimeline;
import com.xpe.mobile.video.Mp4Exporter;
import com.xpe.mobile.video.VideoExportOptions;
import com.xpe.mobile.video.VideoExportStorage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns export/save/cancel lifecycle; the editor is paused while a snapshot renders. */
final class MainVideoExport implements AutoCloseable {
    private final MainActivity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Thread workerThread;
    private AtomicBoolean cancelled;
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText;
    private File readyFile;
    private String readyName;
    private Uri pendingDestination;
    private Uri savedDestination;
    private String savedName;
    private AlertDialog savedDialog;
    private boolean busy;
    private volatile boolean closed, preserveReadyFile;
    private VideoExportDialog settingsDialog;

    void relocalizeUi() {
        if (settingsDialog != null) settingsDialog.relocalizeUi();
        if (progressDialog != null) {
            progressDialog.setTitle(activity.getText(R.string.menu_export_video));
            progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(activity.getText(R.string.action_cancel));
            UiText.relocalize(progressDialog.getWindow().getDecorView());
        }
    }

    MainVideoExport(MainActivity activity) { this.activity = activity; }

    void saveState(Bundle state) {
        if (savedDestination != null) {
            state.putString("video_saved_destination", savedDestination.toString());
            state.putString("video_saved_name", savedName);
        }
        if (!hasReadyVideo()) return;
        preserveReadyFile = true;
        state.putString("video_ready_file", readyFile.getName());
        state.putBoolean("video_ready_private", !readyFile.getParentFile().equals(activity.getCacheDir()));
        state.putString("video_ready_name", readyName);
        if (pendingDestination != null && (cancelled == null || !cancelled.get())) {
            state.putString("video_save_destination", pendingDestination.toString());
        }
    }

    void restoreState(Bundle state) {
        if (state == null) return;
        String saved = state.getString("video_saved_destination");
        if (saved != null) {
            savedDestination = Uri.parse(saved);
            savedName = state.getString("video_saved_name", "PhiStudio.mp4");
        }
        String name = state.getString("video_ready_file");
        if (name == null || !name.startsWith("phistudio-video-") || !name.endsWith(".mp4")
                || name.contains("/") || name.contains("\\")) return;
        File directory;
        try {
            directory = state.getBoolean("video_ready_private")
                    ? VideoExportStorage.directory(activity) : activity.getCacheDir();
        } catch (IOException exception) { return; }
        File file = new File(directory, name);
        try {
            if (!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())) return;
        } catch (IOException exception) { return; }
        if (!file.isFile() || file.length() == 0) return;
        readyFile = file;
        readyName = state.getString("video_ready_name", "PhiStudio.mp4");
        String destination = state.getString("video_save_destination");
        if (destination != null) pendingDestination = Uri.parse(destination);
    }

    boolean resumeAfterInitialLoad() {
        if (closed) return false;
        if (busy) return true;
        if (pendingDestination != null) {
            Uri destination = pendingDestination;
            pendingDestination = null;
            acceptDestination(MainActivity.RESULT_OK, new Intent().setData(destination));
            return true;
        }
        if (savedDestination != null) { showSaved(); return true; }
        // The picker may return after the project has finished restoring.
        return hasReadyVideo();
    }

    private boolean hasReadyVideo() {
        return readyFile != null && readyFile.isFile() && readyFile.length() > 0;
    }

    void show() {
        if (closed || busy) return;
        if (hasReadyVideo()) { showReady(); return; }
        showSettings();
    }

    private void showSettings() {
        ChartDocument chart = activity.editorView.getChart();
        if (chart == null) { activity.showMessage(activity.getString(R.string.video_chart_required)); return; }
        activity.editorView.stopPlayback();
        Uri source = audioSource();
        long duration = activity.audioDurationMs();
        if (duration <= 0) {
            double lastBeat = 0;
            for (JudgeLine line : chart.judgeLines) for (Note note : line.notes) {
                if (note.startTime != null) lastBeat = Math.max(lastBeat, note.startTime.toDouble());
                if (note.endTime != null) lastBeat = Math.max(lastBeat, note.endTime.toDouble());
            }
            duration = Math.max(1000, chart.beatToAudioMillis(lastBeat, activity.currentPackageOffsetMs()) + 1000);
        }
        settingsDialog = new VideoExportDialog(activity, source != null, duration,
                com.xpe.mobile.audio.PlaybackTimeline.startMillis(chart.offsetMs,
                        activity.currentPackageOffsetMs()));
        settingsDialog.show(this::render);
    }

    private Uri audioSource() {
        if (activity.audioController != null && activity.audioController.sourceUri() != null)
            return activity.audioController.sourceUri();
        File audio = activity.currentPackage == null ? null : activity.currentPackage.getAudioFile();
        return audio != null && audio.isFile() ? Uri.fromFile(audio) : null;
    }

    private void render(VideoExportOptions options) {
        if (busy || closed) return;
        ChartDocument current = activity.editorView.getChart();
        if (current == null) return;
        try {
            final String json = current.toJsonString();
            final boolean uniformScale = current.noteUniformScale, attachUiFix = current.useAttachUiFix;
            final ChartPackage pack = activity.currentPackage;
            final Uri audio = audioSource();
            final long duration = activity.audioDurationMs(), offset = activity.currentPackageOffsetMs();
            final EditorSettings settings = activity.editorSettings.copy();
            final String shaderJson = activity.shaderDocument != null && activity.shaderPackage == pack
                    ? activity.shaderDocument.json() : null;
            final Map<String, String> sources = shaderJson == null ? null : activity.shaderDocument.sourceSnapshot();
            readyName = MainActivity.sanitizeFileName(activity.currentProjectName == null
                    ? current.name : activity.currentProjectName) + ".mp4";
            beginProgress(R.string.video_preparing);
            AtomicBoolean token = cancelled;
            executor.execute(() -> {
                workerThread = Thread.currentThread();
                if (token.get()) workerThread.interrupt();
                File result = null;
                Exception failure = null;
                try {
                    ChartDocument snapshot = ChartDocument.fromJson(json);
                    snapshot.noteUniformScale = uniformScale; snapshot.useAttachUiFix = attachUiFix;
                    ShaderTimeline timeline = ShaderTimeline.EMPTY;
                    if (options.shaders) {
                        timeline = shaderJson == null ? PreviewShaderLoader.load(pack, activity.getAssets())
                                : ShaderTimeline.parse(shaderJson, name -> {
                                    String source = sources.get(name);
                                    if (source == null) throw new IOException(activity.getString(R.string.shader_source_unavailable, name));
                                    return source;
                                });
                    }
                    result = Mp4Exporter.export(activity.getApplicationContext(), snapshot, pack, audio,
                            duration, offset, settings, timeline, options, progressListener(token));
                } catch (Exception exception) { failure = exception; }
                catch (OutOfMemoryError error) { failure = new IOException(activity.getString(R.string.video_out_of_memory), error); }
                finally { workerThread = null; }
                File completed = result; Exception error = failure;
                activity.mainHandler.post(() -> {
                    finishProgress();
                    if (closed || token.get()) {
                        if (completed != null) completed.delete();
                        if (!closed) activity.showMessage(activity.getString(R.string.video_cancelled));
                        return;
                    }
                    if (error != null) { showFailure(error); return; }
                    readyFile = completed; requestSave();
                });
            });
        } catch (Exception exception) { finishProgress(); showFailure(exception); }
    }

    private Mp4Exporter.Progress progressListener(AtomicBoolean token) {
        return new Mp4Exporter.Progress() {
            private long lastUpdate;
            private Mp4Exporter.Stage previous;
            public void update(Mp4Exporter.Stage stage, long done, long total) {
                long now = System.nanoTime();
                if (stage == previous && done != total && now - lastUpdate < 150_000_000L) return;
                previous = stage; lastUpdate = now;
                activity.mainHandler.post(() -> {
                    if (closed || token.get() || progressDialog == null) return;
                    int resource = switch (stage) {
                        case PREPARING -> R.string.video_preparing;
                        case VIDEO -> R.string.video_rendering;
                        case AUDIO -> R.string.video_encoding_audio;
                        case FINALIZING -> R.string.video_finalizing;
                        case SAVING -> R.string.video_saving;
                    };
                    boolean determinate = total > 1;
                    progressBar.setIndeterminate(!determinate);
                    int percentage = (int) Math.min(100, done * 100 / Math.max(1, total));
                    if (determinate) progressBar.setProgress(percentage);
                    UiText.text(progressText, () -> determinate ? activity.getString(R.string.video_progress_percentage,
                            activity.getString(resource), percentage) : activity.getString(resource));
                });
            }
        };
    }

    private void beginProgress(int message) {
        busy = true; cancelled = new AtomicBoolean();
        LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        int pad = activity.dp(20); content.setPadding(pad, pad, pad, pad);
        progressText = new TextView(activity); UiText.text(progressText, message); content.addView(progressText);
        progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100); progressBar.setIndeterminate(true); content.addView(progressBar);
        TextView hint = new TextView(activity); UiText.text(hint, R.string.video_progress_hint); content.addView(hint);
        progressDialog = new UiDialogs.Builder(activity).setTitle(R.string.menu_export_video).setView(content)
                .setNegativeButton(R.string.action_cancel, null).create();
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.setOnCancelListener(dialog -> { if (progressDialog == dialog) cancel(); });
        progressDialog.setOnShowListener(dialog -> {
            AlertDialog shown = (AlertDialog) dialog;
            if (closed || progressDialog != shown) return;
            shown.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
                if (closed || progressDialog != shown) return;
                cancel(); UiText.text(progressText, R.string.video_cancelling);
                shown.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            });
        });
        progressDialog.show();
    }

    private void cancel() {
        if (cancelled != null) cancelled.set(true);
        Thread worker = workerThread;
        if (worker != null) worker.interrupt();
    }
    private void finishProgress() {
        busy = false;
        if (progressDialog != null) progressDialog.dismiss();
        progressDialog = null; progressBar = null; progressText = null;
    }

    private void requestSave() {
        if (closed) return;
        if (!hasReadyVideo()) { showFailure(new IOException(activity.getString(R.string.video_ready_missing))); return; }
        if (!activity.mainFileActions.prepareFilePicker()) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("video/mp4").putExtra(Intent.EXTRA_TITLE, readyName);
        try { activity.startActivityForResult(intent, MainActivity.SAVE_VIDEO); }
        catch (RuntimeException exception) { showFailure(exception); }
    }

    void acceptDestination(int resultCode, Intent data) {
        if (closed || busy) return;
        if (!hasReadyVideo()) { showFailure(new IOException(activity.getString(R.string.video_ready_missing))); return; }
        if (resultCode != MainActivity.RESULT_OK || data == null || data.getData() == null) {
            pendingDestination = null; showReady(); return;
        }
        Uri destination = data.getData();
        File source = readyFile;
        long expectedBytes = source.length();
        pendingDestination = destination;
        beginProgress(R.string.video_saving);
        AtomicBoolean token = cancelled;
        executor.execute(() -> {
            workerThread = Thread.currentThread();
            if (token.get()) workerThread.interrupt();
            Exception failure = null;
            try (FileInputStream input = new FileInputStream(source);
                 OutputStream output = activity.getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException(activity.getString(R.string.video_destination_unavailable));
                byte[] buffer = new byte[256 * 1024]; long copied = 0;
                Mp4Exporter.Progress progress = progressListener(token);
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Save cancelled");
                    output.write(buffer, 0, read); copied += read;
                    progress.update(Mp4Exporter.Stage.SAVING, copied, source.length());
                }
                if (copied == 0 || copied != expectedBytes) throw new IOException(activity.getString(R.string.video_incomplete_save));
                output.flush();
            } catch (Exception exception) { failure = exception; }
            finally { workerThread = null; }
            if ((failure != null || token.get()) && !(closed && preserveReadyFile)) {
                try {
                    if (DocumentsContract.isDocumentUri(activity, destination))
                        DocumentsContract.deleteDocument(activity.getContentResolver(), destination);
                } catch (Exception ignored) { /* A provider may not support removing its new document. */ }
            }
            Exception error = failure;
            activity.mainHandler.post(() -> {
                finishProgress();
                if (closed) { if (!preserveReadyFile) source.delete(); return; }
                pendingDestination = null;
                if (error != null || token.get()) {
                    if (token.get()) activity.showMessage(activity.getString(R.string.video_save_cancelled));
                    else showFailure(error);
                    showReady();
                } else {
                    source.delete(); readyFile = null;
                    savedDestination = destination; savedName = readyName;
                    showSaved();
                }
            });
        });
    }

    private void showSaved() {
        if (closed || savedDestination == null || savedDialog != null && savedDialog.isShowing()) return;
        Uri destination = savedDestination;
        String name = savedName;
        savedDialog = new UiDialogs.Builder(activity).setTitle(R.string.video_saved_title)
                .setMessage(() -> activity.getString(R.string.video_saved, name))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.video_open, (dialog, which) -> {
                    try { activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(destination, "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
                    catch (RuntimeException exception) { showFailure(exception); }
                }).create();
        savedDialog.setCanceledOnTouchOutside(false);
        savedDialog.setOnDismissListener(dialog -> {
            if (savedDialog != dialog) return;
            savedDialog = null;
            if (!closed) { savedDestination = null; savedName = null; }
        });
        savedDialog.show();
    }

    private void showReady() {
        new UiDialogs.Builder(activity).setTitle(R.string.video_ready_title)
                .setMessage(R.string.video_ready_message).setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.video_save, (dialog, which) -> requestSave())
                .setNeutralButton(R.string.video_new_export, (dialog, which) -> {
                    if (readyFile != null) readyFile.delete(); readyFile = null; showSettings();
                }).show();
    }
    private void showFailure(Exception exception) {
        if (closed) return;
        UiDialogs.Builder dialog = new UiDialogs.Builder(activity).setTitle(R.string.video_failed_title)
                .setMessage(() -> UiErrors.video(activity, exception)).setPositiveButton(android.R.string.ok, null);
        if (!UiErrors.video(activity, exception).equals(UiErrors.describe(activity, exception))) {
            dialog.setNeutralButton(R.string.error_details, (ignored, which) ->
                    new UiDialogs.Builder(activity).setTitle(R.string.error_details)
                            .setMessage(UiErrors.describe(activity, exception))
                            .setPositiveButton(android.R.string.ok, null).show());
        }
        dialog.show();
    }
    @Override public void close() {
        closed = true; cancel(); executor.shutdownNow(); finishProgress();
        if (savedDialog != null) { savedDialog.dismiss(); savedDialog = null; }
        if (readyFile != null && !preserveReadyFile) readyFile.delete(); readyFile = null;
    }
}
