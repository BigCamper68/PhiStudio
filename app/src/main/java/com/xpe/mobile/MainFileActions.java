package com.xpe.mobile;

import com.xpe.mobile.i18n.UiErrors;

import android.content.Intent;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.io.ChartIo;
import java.io.IOException;

/** MainFileActions responsibilities extracted from MainActivity. */
final class MainFileActions {
    private final MainActivity delegateOwner;

    MainFileActions(MainActivity delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void requestOpen() {
        if (!prepareFilePicker()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        delegateOwner.startActivityForResult(intent, MainActivity.OPEN_CHART);
    }

    void requestSave() {
        ChartDocument chart = delegateOwner.editorView.getChart();
        if (chart == null) return;
        if (!prepareFilePicker()) return;
        String suggested = MainActivity.sanitizeFileName(chart.id.isEmpty() ? chart.name : chart.id) + ".json";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, suggested);
        delegateOwner.startActivityForResult(intent, MainActivity.SAVE_CHART);
    }

    void requestProjectSave() {
        if (delegateOwner.currentPackage == null || delegateOwner.currentProjectId == null || delegateOwner.editorView.getChart() == null) {
            delegateOwner.showMessage(delegateOwner.getString(R.string.project_save_required));
            return;
        }
        try {
            delegateOwner.mainProjectSession.saveCurrentProject();
            delegateOwner.showMessage(delegateOwner.getString(R.string.shortcut_project_saved));
        } catch (IOException exception) {
            delegateOwner.showMessage(delegateOwner.getString(R.string.project_library_error, UiErrors.describe(delegateOwner, exception)));
        }
    }

    void requestAudio() {
        if (!prepareFilePicker()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/mpeg", "audio/ogg", "audio/wav", "audio/x-wav", "audio/*"});
        delegateOwner.startActivityForResult(intent, MainActivity.OPEN_AUDIO);
    }

    void requestPackageOpen() {
        if (!prepareFilePicker()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed", "application/octet-stream"
        });
        delegateOwner.startActivityForResult(intent, MainActivity.OPEN_PACKAGE);
    }

    void requestPackageSave() {
        if (delegateOwner.currentPackage == null || delegateOwner.editorView.getChart() == null) {
            delegateOwner.showMessage(delegateOwner.getString(R.string.package_required));
            return;
        }
        try {
            delegateOwner.mainProjectSession.saveCurrentProject();
        } catch (IOException exception) {
            delegateOwner.showMessage(delegateOwner.getString(R.string.package_export_failed, UiErrors.describe(delegateOwner, exception)));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE,
                MainActivity.sanitizeFileName(delegateOwner.currentProjectName == null
                        ? delegateOwner.currentPackage.getProjectName() : delegateOwner.currentProjectName) + ".zip");
        delegateOwner.startActivityForResult(intent, MainActivity.SAVE_PACKAGE);
    }

    boolean prepareFilePicker() {
        try {
            delegateOwner.mainProjectSession.saveCurrentProject();
            ChartDocument chart = delegateOwner.editorView == null ? null : delegateOwner.editorView.getChart();
            if (delegateOwner.currentPackage == null && chart != null) {
                // Preserve the working session even when periodic autosave is disabled.
                ChartIo.writeAutosave(delegateOwner, chart);
            }
            return true;
        } catch (Exception exception) {
            delegateOwner.showMessage(delegateOwner.getString(R.string.file_error, UiErrors.describe(delegateOwner, exception)));
            return false;
        }
    }
}
