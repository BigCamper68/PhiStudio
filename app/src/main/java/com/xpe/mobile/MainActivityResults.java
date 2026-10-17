package com.xpe.mobile;

import android.content.Intent;
import android.net.Uri;
import com.xpe.mobile.io.ChartIo;
import com.xpe.mobile.packageio.AndroidPackageIo;
import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.packageio.PackageException;
import com.xpe.mobile.project.ProjectRecord;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** MainActivityResults responsibilities extracted from MainActivity. */
final class MainActivityResults {
    private final MainActivity delegateOwner;

    MainActivityResults(MainActivity delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == MainActivity.SAVE_VIDEO) {
            delegateOwner.mainVideoExport.acceptDestination(resultCode, data);
            return;
        }
        if (resultCode != MainActivity.RESULT_OK || data == null || data.getData() == null) return;
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
                                        MainActivity.safeMessage(exception)));
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
                                R.string.file_error, MainActivity.safeMessage(exception))));
            } else if (requestCode == MainActivity.SAVE_CHART) {
                ChartIo.writeChart(delegateOwner.getContentResolver(), uri, delegateOwner.editorView.getChart());
                delegateOwner.showMessage("Chart exported");
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
                    delegateOwner.showMessage(delegateOwner.getString(message, MainActivity.safeMessage(exception)));
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
                delegateOwner.showMessage(delegateOwner.getString(message, MainActivity.safeMessage(exception)));
            } else {
                delegateOwner.showMessage(delegateOwner.getString(R.string.package_export_failed, MainActivity.safeMessage(exception)));
            }
        } catch (Exception exception) {
            if (requestCode == MainActivity.OPEN_PACKAGE) {
                delegateOwner.showMessage(delegateOwner.getString(R.string.package_import_failed, MainActivity.safeMessage(exception)));
            } else if (requestCode == MainActivity.SAVE_PACKAGE) {
                delegateOwner.showMessage(delegateOwner.getString(R.string.package_export_failed, MainActivity.safeMessage(exception)));
            } else {
                delegateOwner.showMessage(delegateOwner.getString(R.string.file_error, MainActivity.safeMessage(exception)));
            }
        }
    }
}
