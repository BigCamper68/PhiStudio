package com.xpe.mobile;

import com.xpe.mobile.i18n.UiText;
import com.xpe.mobile.i18n.UiDialogs;
import com.xpe.mobile.i18n.LanguageController;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.Toast;
import com.xpe.mobile.audio.EditorAudioController;
import com.xpe.mobile.audio.HitSoundTimeline;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.io.ChartIo;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.project.ProjectBrowserDialog;
import com.xpe.mobile.project.ProjectLibrary;
import com.xpe.mobile.project.ProjectThumbnailLoader;
import com.xpe.mobile.shadereditor.ShaderDocument;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements EditorView.Callback, UiText.ResourceOwner,
        UiDialogs.Owner {
    LanguageController languageController;
    private final java.util.List<java.lang.ref.WeakReference<android.app.AlertDialog>> localizedDialogs = new java.util.ArrayList<>();

    @Override public void registerLocalizedDialog(android.app.AlertDialog dialog) {
        localizedDialogs.removeIf(reference -> reference.get() == null);
        localizedDialogs.add(new java.lang.ref.WeakReference<>(dialog));
    }

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        languageController = new LanguageController(base);
        languageController.setLanguageChanged(this::relocalizeUi);
    }

    @Override public android.content.res.Resources getResources() {
        return languageController == null ? super.getResources() : languageController.resources();
    }

    @Override public android.content.res.Resources uiResources() { return getResources(); }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        languageController.onConfigurationChanged();
    }

    void relocalizeUi() {
        if (workbench != null) workbench.relocalizeUi();
        if (projectBrowser != null) projectBrowser.relocalizeUi();
        mainVideoExport.relocalizeUi();
        mainPlaytest.relocalizeUi();
        for (java.lang.ref.WeakReference<android.app.AlertDialog> reference : localizedDialogs) {
            android.app.AlertDialog dialog = reference.get();
            if (dialog != null && dialog.isShowing())
                UiText.relocalize(dialog.getWindow().getDecorView());
        }
        setTitle(getText(R.string.app_name));
    }
    private final MainActivityResults mainActivityResults = new MainActivityResults(this);
    final MainFileActions mainFileActions = new MainFileActions(this);
    final MainVideoExport mainVideoExport = new MainVideoExport(this);
    private final MainScreenSetup mainScreenSetup = new MainScreenSetup(this);

    final MainMenuUi mainMenuUi = new MainMenuUi(this);
    final MainPlaytest mainPlaytest = new MainPlaytest(this);
    final MainPreviewResources mainPreviewResources = new MainPreviewResources(this);
    final MainProjectActions mainProjectActions = new MainProjectActions(this);
    final MainProjectSession mainProjectSession = new MainProjectSession(this);
    final MainShaderSession mainShaderSession = new MainShaderSession(this);
    final MainShortcuts mainShortcuts = new MainShortcuts(this);
    final NewProjectDialog newProjectDialog = new NewProjectDialog(this);

    interface ProjectTask<T> {
        T run() throws Exception;
    }

    interface ProjectSuccess<T> {
        void accept(T value);
    }

    interface ProjectFailure {
        void accept(Exception exception);
    }

    static final int OPEN_CHART = 1001;
    static final int SAVE_CHART = 1002;
    static final int OPEN_AUDIO = 1003;
    static final int OPEN_PACKAGE = 1004;
    static final int SAVE_PACKAGE = 1005;
    static final int SELECT_NEW_PROJECT_AUDIO = 1006;
    static final int SELECT_NEW_PROJECT_ILLUSTRATION = 1007;
    static final int OPEN_SHADER = 1008;
    static final int SAVE_VIDEO = 1009;

    EditorView editorView;
    WorkbenchView workbench;
    EditorDialogController editorDialogs;
    EditorAudioController audioController;
    ChartPackage currentPackage;
    Uri currentPackageSourceUri;
    ProjectLibrary projectLibrary;
    String currentProjectId;
    String currentProjectName;
    ProjectThumbnailLoader projectThumbnails;
    ProjectBrowserDialog projectBrowser;
    ExecutorService projectExecutor;
    ExecutorService previewExecutor;
    Handler mainHandler;
    NewProjectDraft newProjectDraft;
    int illustrationLoadGeneration;
    EditorSettings editorSettings;
    ShaderDocument shaderDocument;
    ChartPackage shaderPackage;
    ShaderEditorView shaderEditor;
    int shaderGeneration;
    Runnable pendingShaderPreview;
    private final Runnable scheduledAutosave = new Runnable() {
        @Override
        public void run() {
            if (editorSettings == null || !editorSettings.autosaveEnabled) return;
            try {
                ChartDocument chart = editorView == null ? null : editorView.getChart();
                if (chart != null && (editorView.isChartDirty() || mainShaderSession.shadersDirty()) && !editorView.isPlaying()) {
                    if (currentPackage != null && currentProjectId != null) {
                        mainProjectSession.saveCurrentProject();
                    } else {
                        ChartIo.writeAutosave(MainActivity.this, chart);
                    }
                    editorView.markChartSaved();
                }
            } catch (Exception ignored) {
                // Explicit save/export remains available after an autosave failure.
            } finally {
                scheduleAutosave();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainScreenSetup.onCreate(savedInstanceState);
    }

    @Override
    public void requestOpen() {
        mainFileActions.requestOpen();
    }

    @Override
    public void requestSave() {
        mainFileActions.requestSave();
    }

    @Override
    public void requestProjectSave() {
        mainFileActions.requestProjectSave();
    }

    @Override
    public void requestAudio() {
        mainFileActions.requestAudio();
    }

    @Override
    public void requestEditMetadata() {
        editorDialogs.showMetadata();
    }

    @Override
    public void requestEditBpmList() {
        editorDialogs.showBpmList();
    }

    @Override
    public void requestManageLines() {
        showLineManager();
    }

    @Override
    public void requestMainMenu() {
        mainMenuUi.requestMainMenu();
    }

    View createWorkbenchMenu() {
        return mainMenuUi.createWorkbenchMenu();
    }

    @Override
    public void requestStoryboardProperties(com.xpe.mobile.model.StoryboardEventType type,
            com.xpe.mobile.model.ExtendedLineEvents.TimedEvent event, boolean creating) {
        editorDialogs.showStoryboardProperties(type, event, creating);
    }

    @Override public void onBackPressed() {
        if (workbench != null && workbench.back()) return;
        super.onBackPressed();
    }

    private void showStoryboardEditor() {
        editorDialogs.showStoryboardEditor();
    }

    void showSettings() {
        editorDialogs.showSettings();
    }

    @Override
    public void requestAdvancedBatchEdit() {
        editorDialogs.showAdvancedBatchEdit();
    }

    @Override
    public void requestEventClone() {
        editorDialogs.showEventClone();
    }

    void showComplexMove() {
        editorDialogs.showComplexMove();
    }

    void showCurveNotes() {
        editorDialogs.showCurveNotes();
    }

    void showChartDiagnostics() {
        editorDialogs.showChartDiagnostics();
    }

    private void showLineManager() {
        editorDialogs.showLineManager();
    }

    @Override
    public void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean isAudioReady() {
        return audioController != null && audioController.isReady();
    }

    @Override
    public boolean isAudioPlaying() {
        return audioController != null && audioController.isPlaying();
    }

    @Override
    public void playHitSound(HitSoundTimeline.Cue cue) {
        if (audioController != null) audioController.playHitSound(cue);
    }

    @Override
    public void configureHitSounds(HitSoundTimeline timeline, long chartOffsetMs, long packageOffsetMs) {
        if (audioController != null) audioController.configureHitSounds(timeline, chartOffsetMs, packageOffsetMs);
    }

    @Override
    public boolean schedulesHitSounds() {
        return audioController != null && audioController.schedulesHitSounds();
    }

    @Override
    public void startAudio(long positionMs, float speed) {
        if (audioController != null) audioController.start(positionMs, speed);
    }

    @Override
    public void requestEditNoteProperties(Note note) {
        editorDialogs.showNoteProperties(note);
    }

    @Override
    public void requestEditEventProperties(LineEvent event) {
        editorDialogs.showEventProperties(event);
    }

    @Override
    public void pauseAudio() {
        if (audioController != null) audioController.pause();
    }

    @Override
    public void seekAudio(long positionMs) {
        if (audioController != null) audioController.seek(positionMs);
    }

    @Override
    public long audioPositionMs() {
        return audioController == null ? 0L : audioController.positionMillis();
    }

    @Override
    public double audioPositionSeconds() {
        return audioController == null ? 0 : audioController.positionSeconds();
    }

    @Override
    public long audioDurationMs() {
        return audioController == null ? 0L : audioController.durationMillis();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        mainActivityResults.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainPlaytest.pause();
        pauseAudio();
        ChartDocument chart = editorView.getChart();
        if (chart != null && (editorView.isChartDirty() || mainShaderSession.shadersDirty())
                && editorSettings != null && editorSettings.autosaveEnabled) {
            try {
                if (currentPackage != null && currentProjectId != null) {
                    mainProjectSession.saveCurrentProject();
                } else {
                    ChartIo.writeAutosave(this, chart);
                }
                editorView.markChartSaved();
            } catch (Exception ignored) {
                // Explicit export remains available even if private autosave fails.
            }
        }
    }

    @Override
    protected void onDestroy() {
        mainPlaytest.close();
        mainVideoExport.close();
        mainShaderSession.clearShaderSession();
        illustrationLoadGeneration++;
        if (mainHandler != null) mainHandler.removeCallbacks(scheduledAutosave);
        if (projectBrowser != null) projectBrowser.dismiss();
        if (projectThumbnails != null) projectThumbnails.shutdown();
        if (projectExecutor != null) projectExecutor.shutdownNow();
        if (previewExecutor != null) previewExecutor.shutdownNow();
        if (editorView != null) {
            editorView.setBackgroundIllustration(null);
            editorView.setPreviewLineTextures(null);
        }
        if (audioController != null) {
            audioController.close();
            audioController = null;
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && !mainPlaytest.isOpen() && editorSettings != null && !(getCurrentFocus() instanceof EditText)) {
            if (mainShortcuts.matchesShortcut(event, editorSettings.shortcutSave)) {
                mainShortcuts.saveFromShortcut();
                return true;
            }
            if (mainShortcuts.matchesShortcut(event, editorSettings.shortcutUndo)) {
                editorView.performUndo();
                return true;
            }
            if (mainShortcuts.matchesShortcut(event, editorSettings.shortcutRedo)) {
                editorView.performRedo();
                return true;
            }
            if (mainShortcuts.matchesShortcut(event, editorSettings.shortcutCopy)) {
                editorView.performCopy();
                return true;
            }
            if (mainShortcuts.matchesShortcut(event, editorSettings.shortcutCut)) {
                editorView.performCut();
                return true;
            }
            if (mainShortcuts.matchesShortcut(event, editorSettings.shortcutMirrorPaste)) {
                editorView.performPaste(true);
                return true;
            }
            if (mainShortcuts.matchesShortcut(event, editorSettings.shortcutPaste)) {
                editorView.performPaste(false);
                return true;
            }
            if (mainShortcuts.matchesShortcut(event, editorSettings.shortcutDelete)) {
                editorView.performDeleteSelection();
                return true;
            }
            if (mainShortcuts.matchesShortcut(event, editorSettings.shortcutPlayPause)) {
                editorView.performTogglePlay();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    void scheduleAutosave() {
        if (mainHandler == null) return;
        mainHandler.removeCallbacks(scheduledAutosave);
        if (editorSettings == null || !editorSettings.autosaveEnabled) return;
        long delay = Math.max(1000L, Math.min(24L * 60L * 60L * 1000L,
                Math.round(editorSettings.autosaveIntervalSeconds * 1000.0)));
        mainHandler.postDelayed(scheduledAutosave, delay);
    }

    void applyAudioVolume() {
        if (audioController != null) audioController.applySettings(editorSettings);
    }

    long currentPackageOffsetMs() {
        return currentPackage == null ? 0L : currentPackage.getManifestOffsetMs();
    }

    void takeReadPermission(Uri uri, Intent data) {
        if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some document providers do not support persistent grants.
        }
    }

    void hideSystemUi() {
        // Resolve the controller through an installed DecorView. Some OEM PhoneWindow
        // implementations dereference a null DecorView when Window#getInsetsController()
        // is called before setContentView(), crashing the activity during startup.
        View decorView = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    static String sanitizeFileName(String value) {
        String safe = value == null ? "chart" : value.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return safe.isEmpty() ? "chart" : safe;
    }

    static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? exception.getClass().getSimpleName() : message;
    }

    static ExecutorService newSingleThreadExecutor(String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }
}
