package com.xpe.mobile.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;
import com.xpe.mobile.audio.EditorAudioController;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.config.EditorSettingsStore;
import com.xpe.mobile.editor.EditorDialogController;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.editor.WorkbenchView;
import com.xpe.mobile.i18n.LanguageController;
import com.xpe.mobile.project.ProjectBrowserDialog;
import com.xpe.mobile.project.ProjectLibrary;
import com.xpe.mobile.project.ProjectThumbnailLoader;
import java.io.File;
import java.util.List;

/** MainScreenSetup responsibilities extracted from MainActivity. */
final class MainScreenSetup {
    private final MainActivity delegateOwner;

    MainScreenSetup(MainActivity delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void onCreate(Bundle savedInstanceState) {
        delegateOwner.mainActivityResults.restoreState(savedInstanceState);
        delegateOwner.requestWindowFeature(Window.FEATURE_NO_TITLE);
        delegateOwner.getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        delegateOwner.editorView = new EditorView(delegateOwner);
        delegateOwner.workbench = new WorkbenchView(delegateOwner, delegateOwner.editorView, delegateOwner::createWorkbenchMenu);
        delegateOwner.workbench.setShaderOpener(delegateOwner.mainShaderSession::openShaderEditor);
        delegateOwner.editorView.setCallback(delegateOwner);
        delegateOwner.editorSettings = EditorSettingsStore.load(delegateOwner.getPreferences(MainActivity.MODE_PRIVATE));
        delegateOwner.editorView.applySettings(delegateOwner.editorSettings);
        delegateOwner.mainHandler = new Handler(Looper.getMainLooper());
        delegateOwner.audioController = new EditorAudioController(delegateOwner, delegateOwner.mainHandler,
                new EditorAudioController.Listener() {
                    @Override
                    public void onAudioStateChanged() {
                        delegateOwner.editorView.audioStateChanged();
                    }

                    @Override
                    public void onAudioCompleted() {
                        if (!delegateOwner.mainPlaytest.audioCompleted()) delegateOwner.editorView.audioCompleted();
                    }

                    @Override
                    public void showMessage(String message) {
                        delegateOwner.showMessage(message);
                    }

                    @Override
                    public boolean isHostUnavailable() {
                        return delegateOwner.isFinishing() || delegateOwner.isDestroyed();
                    }
                });
        delegateOwner.audioController.applySettings(delegateOwner.editorSettings);
        delegateOwner.editorDialogs = new EditorDialogController(delegateOwner, delegateOwner.editorView,
                new EditorDialogController.Host() {
                    @Override public LanguageController languageController() {
                        return delegateOwner.languageController;
                    }
                    @Override public WorkbenchView workbench() { return delegateOwner.workbench; }
                    @Override
                    public EditorSettings editorSettings() {
                        return delegateOwner.editorSettings;
                    }

                    @Override
                    public void applyEditorSettings(EditorSettings settings) {
                        delegateOwner.editorSettings = settings;
                        EditorSettingsStore.save(
                                delegateOwner.getPreferences(MainActivity.MODE_PRIVATE), delegateOwner.editorSettings);
                        delegateOwner.editorView.applySettings(delegateOwner.editorSettings);
                        delegateOwner.applyAudioVolume();
                        delegateOwner.scheduleAutosave();
                    }

                    @Override
                    public long packageOffsetMs() {
                        return delegateOwner.currentPackageOffsetMs();
                    }

                    @Override
                    public long audioDurationMs() {
                        return delegateOwner.audioDurationMs();
                    }

                    @Override
                    public void onLineAppearanceChanged() {
                        if (delegateOwner.currentPackage != null) delegateOwner.mainPreviewResources.loadEditorIllustration(delegateOwner.currentPackage);
                    }

                    @Override
                    public List<String> hitSoundChoices() {
                        return com.xpe.mobile.packageio.PackageHitSoundCatalog.choices(delegateOwner.currentPackage);
                    }

                    @Override
                    public boolean isHitSoundAvailable(String reference) {
                        return com.xpe.mobile.packageio.PackageHitSoundCatalog.isAvailable(delegateOwner.currentPackage, reference);
                    }

                    @Override
                    public void showMessage(String message) {
                        delegateOwner.showMessage(message);
                    }
                });
        delegateOwner.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        delegateOwner.setContentView(delegateOwner.workbench);
        delegateOwner.projectLibrary = new ProjectLibrary(new File(delegateOwner.getFilesDir(), "project-library"));
        delegateOwner.projectThumbnails = new ProjectThumbnailLoader(
                new File(delegateOwner.getCacheDir(), "project-thumbnails"));
        delegateOwner.projectBrowser = new ProjectBrowserDialog(delegateOwner, delegateOwner.projectLibrary, delegateOwner.projectThumbnails);
        delegateOwner.projectExecutor = MainActivity.newSingleThreadExecutor("phistudio-project-io");
        delegateOwner.previewExecutor = MainActivity.newSingleThreadExecutor("phistudio-preview-prepare");
        delegateOwner.scheduleAutosave();
        delegateOwner.hideSystemUi();
        delegateOwner.mainVideoExport.restoreState(savedInstanceState);
        delegateOwner.newProjectDialog.restoreState(savedInstanceState);
        delegateOwner.mainProjectSession.loadInitialChart(savedInstanceState != null);
    }
}
