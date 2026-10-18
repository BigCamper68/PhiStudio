package com.xpe.mobile;

import com.xpe.mobile.i18n.UiText;
import com.xpe.mobile.i18n.UiErrors;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import com.xpe.mobile.io.ChartIo;
import com.xpe.mobile.packageio.AndroidPackageIo;
import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.packageio.PackageException;
import com.xpe.mobile.project.ProjectRecord;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.ArrayList;

/** MainActivityResults responsibilities extracted from MainActivity. */
final class MainActivityResults {
    private final MainActivity delegateOwner;
    private boolean initialContentLoading;
    private final ArrayList<Bundle> pendingResults = new ArrayList<>();

    MainActivityResults(MainActivity delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void restoreState(Bundle state) {
        if (state == null) return;
        ArrayList<Bundle> restored = state.getParcelableArrayList("pending_file_results");
        if (restored != null) pendingResults.addAll(restored);
        String source = state.getString("package_source_uri");
        if (source != null) delegateOwner.currentPackageSourceUri = Uri.parse(source);
    }

    void saveState(Bundle state) {
        state.putParcelableArrayList("pending_file_results", new ArrayList<>(pendingResults));
        if (delegateOwner.currentPackageSourceUri != null) {
            state.putString("package_source_uri", delegateOwner.currentPackageSourceUri.toString());
        }
    }

    void beginInitialLoad() {
        initialContentLoading = true;
    }

    boolean finishInitialLoad() {
        initialContentLoading = false;
        ArrayList<Bundle> results = new ArrayList<>(pendingResults);
        pendingResults.clear();
        boolean restoredDraft = delegateOwner.newProjectDialog.resumeDraft();
        for (Bundle result : results) {
            onActivityResult(result.getInt("request"), result.getInt("result", MainActivity.RESULT_OK), result.getParcelable("data"));
        }
        return delegateOwner.mainVideoExport.resumePendingSave() || restoredDraft || !results.isEmpty();
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        boolean validData = resultCode == MainActivity.RESULT_OK && data != null && data.getData() != null;
        // Keep video cancellations too: the rendered file remains available for another save.
        if (initialContentLoading && (validData || requestCode == MainActivity.SAVE_VIDEO)) {
            Bundle result = new Bundle();
            result.putInt("request", requestCode);
            result.putInt("result", resultCode);
            if (data != null) result.putParcelable("data", new Intent(data));
            pendingResults.add(result);
            return;
        }
        if (requestCode == MainActivity.SAVE_VIDEO) {
            delegateOwner.mainVideoExport.acceptDestination(resultCode, data);
            return;
        }
        if (!validData) return;
        Uri uri = data.getData();
        if (requestCode == MainActivity.OPEN_SHADER) {
            delegateOwner.mainShaderSession.importShader(uri, data);
            return;
        }
        if (requestCode == MainActivity.SELECT_NEW_PROJECT_AUDIO
                || requestCode == MainActivity.SELECT_NEW_PROJECT_ILLUSTRATION) {
            delegateOwner.newProjectDialog.acceptNewProjectAsset(requestCode, uri, data);
            return;
        }
        try {
            if (requestCode == MainActivity.OPEN_CHART) {
                delegateOwner.takeReadPermission(uri, data);
                delegateOwner.mainProjectSession.saveCurrentProject();
                delegateOwner.editorView.stopPlayback();
                String displayName = AndroidPackageIo.displayName(delegateOwner.getContentResolver(), uri);
                delegateOwner.mainProjectSession.runProjectTask(R.string.project_importing,
                        () -> ChartIo.readChart(delegateOwner.getContentResolver(), uri), imported -> {
                            try {
                                delegateOwner.projectLibrary.clearCurrentProject();
                            } catch (IOException exception) {
                                delegateOwner.showMessage(delegateOwner.getString(R.string.project_library_error,
                                        UiErrors.describe(delegateOwner, exception)));
                                return;
                            }
                            delegateOwner.currentPackage = null;
                            delegateOwner.audioController.setHitSoundPackage(null);
                            delegateOwner.currentPackageSourceUri = null;
                            delegateOwner.currentProjectId = null;
                            delegateOwner.currentProjectName = null;
                            delegateOwner.editorView.setChart(imported);
                            delegateOwner.editorView.markChartDirty();
                            delegateOwner.mainPreviewResources.clearEditorIllustration();
                            delegateOwner.editorView.setProjectName(displayName);
                            delegateOwner.showMessage(delegateOwner.getString(R.string.chart_imported));
                        }, exception -> delegateOwner.showMessage(delegateOwner.getString(
                                R.string.file_error, UiErrors.describe(delegateOwner, exception))));
            } else if (requestCode == MainActivity.SAVE_CHART) {
                ChartIo.writeChart(delegateOwner, uri, delegateOwner.editorView.getChart());
                delegateOwner.showMessage(UiText.string(delegateOwner, R.string.ui_chart_exported));
            } else if (requestCode == MainActivity.OPEN_AUDIO) {
                delegateOwner.takeReadPermission(uri, data);
                delegateOwner.mainPreviewResources.loadAudio(uri, true);
            } else if (requestCode == MainActivity.OPEN_PACKAGE) {
                delegateOwner.takeReadPermission(uri, data);
                delegateOwner.mainProjectSession.saveCurrentProject();
                String projectId = UUID.randomUUID().toString();
                File workspace = delegateOwner.projectLibrary.workspaceForNewProject(projectId);
                delegateOwner.mainProjectSession.runProjectTask(R.string.project_importing, () -> {
                    ChartPackage imported = AndroidPackageIo.importPackage(
                            delegateOwner.getContentResolver(), uri, workspace);
                    delegateOwner.projectLibrary.addImportedProject(
                            projectId, imported, System.currentTimeMillis());
                    ProjectRecord record = delegateOwner.projectLibrary.load().find(projectId);
                    String name = record == null
                            ? imported.getProjectName() : record.getName();
                    return new ImportedProject(projectId, imported, name);
                }, imported -> {
                    delegateOwner.mainProjectSession.activatePackage(imported.chartPackage, imported.id, uri, imported.name);
                    delegateOwner.showMessage(delegateOwner.getString(R.string.package_imported,
                            imported.chartPackage.getProjectName()));
                }, exception -> {
                    int message = exception instanceof PackageException
                            && ((PackageException) exception).getRetainedWorkspace() != null
                            ? R.string.package_import_failed_retained
                            : R.string.package_import_failed;
                    delegateOwner.showMessage(delegateOwner.getString(message, UiErrors.describe(delegateOwner, exception)));
                });
            } else if (requestCode == MainActivity.SAVE_PACKAGE) {
                if (delegateOwner.currentPackage == null) {
                    delegateOwner.showMessage(delegateOwner.getString(R.string.package_required));
                    return;
                }
                if (delegateOwner.currentPackageSourceUri != null && delegateOwner.currentPackageSourceUri.equals(uri)) {
                    delegateOwner.showMessage(delegateOwner.getString(R.string.package_source_overwrite_blocked));
                    return;
                }
                delegateOwner.mainProjectSession.saveCurrentProject();
                AndroidPackageIo.exportPackage(delegateOwner.getContentResolver(), uri, delegateOwner.getCacheDir(),
                        delegateOwner.currentPackage, delegateOwner.editorView.getChart());
                delegateOwner.showMessage(delegateOwner.getString(R.string.package_exported, delegateOwner.currentPackage.getProjectName()));
            }
        } catch (PackageException exception) {
            if (requestCode == MainActivity.OPEN_PACKAGE) {
                int message = exception.getRetainedWorkspace() == null
                        ? R.string.package_import_failed : R.string.package_import_failed_retained;
                delegateOwner.showMessage(delegateOwner.getString(message, UiErrors.describe(delegateOwner, exception)));
            } else {
                delegateOwner.showMessage(delegateOwner.getString(R.string.package_export_failed, UiErrors.describe(delegateOwner, exception)));
            }
        } catch (Exception exception) {
            if (requestCode == MainActivity.OPEN_PACKAGE) {
                delegateOwner.showMessage(delegateOwner.getString(R.string.package_import_failed, UiErrors.describe(delegateOwner, exception)));
            } else if (requestCode == MainActivity.SAVE_PACKAGE) {
                delegateOwner.showMessage(delegateOwner.getString(R.string.package_export_failed, UiErrors.describe(delegateOwner, exception)));
            } else {
                delegateOwner.showMessage(delegateOwner.getString(R.string.file_error, UiErrors.describe(delegateOwner, exception)));
            }
        }
    }
}
