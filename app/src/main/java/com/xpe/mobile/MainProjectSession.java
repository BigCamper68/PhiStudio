package com.xpe.mobile;

import com.xpe.mobile.i18n.UiText;
import com.xpe.mobile.i18n.UiDialogs;
import com.xpe.mobile.i18n.UiErrors;

import android.app.AlertDialog;
import android.net.Uri;
import com.xpe.mobile.MainActivity.ProjectFailure;
import com.xpe.mobile.MainActivity.ProjectSuccess;
import com.xpe.mobile.MainActivity.ProjectTask;
import com.xpe.mobile.io.ChartIo;
import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.packageio.PackageWorkspaceWriter;
import com.xpe.mobile.project.ProjectLibrary;
import com.xpe.mobile.shadereditor.ShaderWorkspace;
import java.io.File;
import java.io.IOException;

/** MainProjectSession responsibilities extracted from MainActivity. */
final class MainProjectSession {
    private final MainActivity delegateOwner;

    MainProjectSession(MainActivity delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    <T> void runProjectTask(int messageResource, ProjectTask<T> task,
                                    ProjectSuccess<T> success) {
        delegateOwner.mainProjectSession.runProjectTask(messageResource, task, success, exception -> delegateOwner.showMessage(delegateOwner.getString(
                R.string.project_library_error, UiErrors.describe(delegateOwner, exception))));
    }

    <T> void runProjectTask(int messageResource, ProjectTask<T> task,
                                    ProjectSuccess<T> success, ProjectFailure failure) {
        AlertDialog progress = new UiDialogs.Builder(delegateOwner)
                .setMessage(messageResource)
                .setCancelable(false)
                .create();
        progress.show();
        try {
            delegateOwner.projectExecutor.execute(() -> {
                try {
                    T result = task.run();
                    delegateOwner.mainHandler.post(() -> {
                        if (delegateOwner.isFinishing() || delegateOwner.isDestroyed()) return;
                        progress.dismiss();
                        success.accept(result);
                    });
                } catch (Exception exception) {
                    delegateOwner.mainHandler.post(() -> {
                        if (delegateOwner.isFinishing() || delegateOwner.isDestroyed()) return;
                        progress.dismiss();
                        failure.accept(exception);
                    });
                } catch (OutOfMemoryError error) {
                    delegateOwner.mainHandler.post(() -> {
                        if (delegateOwner.isFinishing() || delegateOwner.isDestroyed()) return;
                        progress.dismiss();
                        failure.accept(new IOException(delegateOwner.getString(R.string.project_memory_error), error));
                    });
                }
            });
        } catch (RuntimeException exception) {
            progress.dismiss();
            failure.accept(exception);
        }
    }

    void loadInitialChart() {
        delegateOwner.mainActivityResults.beginInitialLoad();
        delegateOwner.mainProjectSession.runProjectTask(R.string.project_library_loading, delegateOwner.mainProjectSession::readInitialContent,
                content -> {
                    if (content.memoryWarning) {
                        delegateOwner.showMessage(delegateOwner.getString(R.string.project_memory_error));
                    } else if (content.libraryWarning != null) {
                        delegateOwner.showMessage(delegateOwner.getString(R.string.project_library_error,
                                MainActivity.safeMessage(content.libraryWarning)));
                    }
                    if (content.project != null) {
                        ProjectLibrary.OpenResult opened = content.project;
                        delegateOwner.mainProjectSession.activatePackage(opened.getChartPackage(), opened.getRecord().getId(),
                                delegateOwner.currentPackageSourceUri, opened.getRecord().getName());
                    } else {
                        delegateOwner.mainProjectSession.activateFallbackContent(content);
                    }
                    if (!delegateOwner.mainActivityResults.finishInitialLoad()
                            && content.project == null && !content.autosave) {
                        delegateOwner.mainProjectActions.showProjectLibrary(true);
                    }
                }, exception -> {
                    delegateOwner.showMessage(delegateOwner.getString(R.string.project_initial_failed, UiErrors.describe(delegateOwner, exception)));
                    if (!delegateOwner.mainActivityResults.finishInitialLoad()) {
                        delegateOwner.mainProjectActions.showProjectLibrary(true);
                    }
                });
    }

    InitialContent readInitialContent() throws IOException {
        Exception libraryWarning = null;
        boolean memoryWarning = false;
        try {
            ProjectLibrary.State state = delegateOwner.projectLibrary.load();
            String currentId = state.getCurrentProjectId();
            if (currentId != null) {
                ProjectLibrary.OpenResult opened = delegateOwner.projectLibrary.openProject(
                        currentId, System.currentTimeMillis());
                return InitialContent.project(opened);
            }
        } catch (Exception exception) {
            libraryWarning = exception;
        } catch (OutOfMemoryError error) {
            memoryWarning = true;
        }
        return delegateOwner.mainProjectSession.readFallbackContent(libraryWarning, memoryWarning);
    }

    InitialContent readFallbackContent(Exception libraryWarning,
                                               boolean memoryWarning) throws IOException {
        try {
            return InitialContent.fallback(
                    ChartIo.readAutosave(delegateOwner), true, libraryWarning, memoryWarning);
        } catch (Exception | OutOfMemoryError noAutosave) {
            try {
                return InitialContent.fallback(
                        ChartIo.readAsset(delegateOwner, "demo_chart.json"),
                        false, libraryWarning, memoryWarning);
            } catch (Exception | OutOfMemoryError demoFailure) {
                IOException failure = new IOException("Unable to load demo chart", demoFailure);
                failure.addSuppressed(noAutosave);
                if (libraryWarning != null) failure.addSuppressed(libraryWarning);
                throw failure;
            }
        }
    }

    void activateFallbackContent(InitialContent content) {
        delegateOwner.mainPreviewResources.clearEditorIllustration();
        delegateOwner.editorView.setChart(content.fallbackChart);
        delegateOwner.editorView.setProjectNameResource(content.autosave
                ? R.string.project_autosave : R.string.project_demo);
        if (content.autosave) delegateOwner.showMessage(UiText.string(delegateOwner, R.string.ui_autosave_restored));
        delegateOwner.mainPreviewResources.restoreAudioReference();
    }

    void activatePackage(ChartPackage chartPackage, String projectId, Uri sourceUri,
                                 String localName) {
        delegateOwner.editorView.stopPlayback();
        delegateOwner.currentPackage = chartPackage;
        delegateOwner.audioController.setHitSoundPackage(chartPackage);
        delegateOwner.currentProjectId = projectId;
        delegateOwner.currentProjectName = localName;
        delegateOwner.currentPackageSourceUri = sourceUri;
        delegateOwner.editorView.setChart(chartPackage.getChart());
        delegateOwner.editorView.setPackageOffsetMs(chartPackage.getManifestOffsetMs());
        delegateOwner.editorView.setUseRpe170Speed(chartPackage.isUseRpe170Speed());
        delegateOwner.editorView.setProjectName(localName == null ? chartPackage.getProjectName() : localName);
        delegateOwner.mainPreviewResources.loadEditorIllustration(chartPackage);
        delegateOwner.getPreferences(MainActivity.MODE_PRIVATE).edit().remove("audio_uri").apply();
        File packageAudio = chartPackage.getAudioFile();
        if (packageAudio != null && packageAudio.isFile()) {
            try {
                delegateOwner.mainPreviewResources.loadAudio(Uri.fromFile(packageAudio), false);
            } catch (IOException exception) {
                delegateOwner.mainPreviewResources.releaseAudio();
                delegateOwner.editorView.audioStateChanged();
                delegateOwner.showMessage(delegateOwner.getString(R.string.project_audio_unavailable, UiErrors.describe(delegateOwner, exception)));
            }
        } else {
            delegateOwner.mainPreviewResources.releaseAudio();
            delegateOwner.editorView.audioStateChanged();
        }
    }

    void saveCurrentProject() throws IOException {
        if (delegateOwner.mainShaderSession.shadersDirty()) {
            String snapshot = delegateOwner.shaderDocument.json();
            ShaderWorkspace.save(delegateOwner.currentPackage, snapshot);
            delegateOwner.shaderDocument.markSaved(snapshot);
            if (delegateOwner.shaderEditor != null) delegateOwner.shaderEditor.invalidatePreview();
        }
        if (delegateOwner.currentPackage != null && delegateOwner.currentProjectId != null
                && delegateOwner.editorView.getChart() != null && delegateOwner.editorView.isChartDirty()) {
            PackageWorkspaceWriter.writeChart(delegateOwner.currentPackage, delegateOwner.editorView.getChart());
            delegateOwner.editorView.markChartSaved();
        }
    }
}
