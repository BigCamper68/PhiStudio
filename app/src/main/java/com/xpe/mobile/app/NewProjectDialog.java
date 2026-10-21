package com.xpe.mobile.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.xpe.mobile.R;
import com.xpe.mobile.i18n.UiDialogs;
import com.xpe.mobile.i18n.UiText;
import com.xpe.mobile.packageio.AndroidPackageIo;
import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.packageio.ProjectWorkspaceCreator;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

/** NewProjectDialog responsibilities extracted from MainActivity. */
final class NewProjectDialog {
    private final MainActivity delegateOwner;
    private Bundle pendingRestore;

    NewProjectDialog(MainActivity delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void showCreateProjectDialog() {
        showCreateProjectDialog(null);
    }

    void restoreState(Bundle state) {
        pendingRestore = state == null ? null : state.getBundle("new_project_draft");
    }

    boolean resumeDraft() {
        if (pendingRestore == null) return false;
        Bundle restored = pendingRestore;
        pendingRestore = null;
        showCreateProjectDialog(restored);
        return true;
    }

    void saveState(Bundle state) {
        NewProjectDraft draft = delegateOwner.newProjectDraft;
        if (draft == null || draft.dialog == null || !draft.dialog.isShowing()) {
            if (pendingRestore != null) state.putBundle("new_project_draft", pendingRestore);
            return;
        }
        Bundle values = new Bundle();
        values.putString("name", draft.name.getText().toString());
        values.putString("composer", draft.composer.getText().toString());
        values.putString("charter", draft.charter.getText().toString());
        values.putString("level", draft.level.getText().toString());
        values.putString("bpm", draft.bpm.getText().toString());
        values.putString("audio_uri", draft.audioUri == null ? null : draft.audioUri.toString());
        values.putString("illustration_uri", draft.illustrationUri == null ? null : draft.illustrationUri.toString());
        values.putString("audio_extension", draft.audioExtension);
        values.putString("illustration_extension", draft.illustrationExtension);
        values.putString("audio_name", draft.audioDisplayName);
        values.putString("illustration_name", draft.illustrationDisplayName);
        state.putBundle("new_project_draft", values);
    }

    private void showCreateProjectDialog(Bundle state) {
        NewProjectDraft draft = new NewProjectDraft();
        ScrollView scroll = new ScrollView(delegateOwner);
        LinearLayout content = delegateOwner.mainMenuUi.createDialogContent(scroll);
        draft.name = delegateOwner.mainMenuUi.addField(content, R.string.project_create_name, value(state,"name",""), false);
        draft.composer = delegateOwner.mainMenuUi.addField(content, R.string.project_create_composer, value(state,"composer",""), false);
        draft.charter = delegateOwner.mainMenuUi.addField(content, R.string.project_create_charter, value(state,"charter",""), false);
        draft.level = delegateOwner.mainMenuUi.addField(content, R.string.project_create_level, value(state,"level",""), false);
        draft.bpm = delegateOwner.mainMenuUi.addField(content, R.string.project_create_bpm, value(state,"bpm","120"), true);

        Button audioButton = new Button(delegateOwner);
        UiText.text(audioButton, R.string.project_create_audio);
        audioButton.setAllCaps(false);
        content.addView(audioButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        draft.audioStatus = new TextView(delegateOwner);
        UiText.text(draft.audioStatus, R.string.project_create_audio_none);
        draft.audioStatus.setTextSize(12f);
        draft.audioStatus.setPadding(0, 0, 0, delegateOwner.dp(8));
        content.addView(draft.audioStatus);

        Button illustrationButton = new Button(delegateOwner);
        UiText.text(illustrationButton, R.string.project_create_illustration);
        illustrationButton.setAllCaps(false);
        content.addView(illustrationButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        draft.illustrationStatus = new TextView(delegateOwner);
        UiText.text(draft.illustrationStatus, R.string.project_create_illustration_none);
        draft.illustrationStatus.setTextSize(12f);
        content.addView(draft.illustrationStatus);

        if (state != null) {
            String audio = state.getString("audio_uri"), illustration = state.getString("illustration_uri");
            draft.audioUri = audio == null ? null : Uri.parse(audio);
            draft.illustrationUri = illustration == null ? null : Uri.parse(illustration);
            draft.audioExtension = state.getString("audio_extension");
            draft.illustrationExtension = state.getString("illustration_extension");
            draft.audioDisplayName = state.getString("audio_name", "");
            draft.illustrationDisplayName = state.getString("illustration_name", "");
            if (draft.audioUri != null) UiText.text(draft.audioStatus,
                    () -> delegateOwner.getString(R.string.project_create_selected, draft.audioDisplayName));
            if (draft.illustrationUri != null) UiText.text(draft.illustrationStatus,
                    () -> delegateOwner.getString(R.string.project_create_selected, draft.illustrationDisplayName));
        }

        AlertDialog dialog = new UiDialogs.Builder(delegateOwner)
                .setTitle(R.string.project_create_title)
                .setView(scroll)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.project_create_action, null)
                .create();
        draft.dialog = dialog;
        delegateOwner.newProjectDraft = draft;
        audioButton.setOnClickListener(view -> delegateOwner.newProjectDialog.requestNewProjectAsset(true));
        illustrationButton.setOnClickListener(view -> delegateOwner.newProjectDialog.requestNewProjectAsset(false));
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> delegateOwner.newProjectDialog.createProjectFromDraft(draft)));
        dialog.setOnDismissListener(ignored -> {
            if (delegateOwner.newProjectDraft == draft) delegateOwner.newProjectDraft = null;
        });
        dialog.show();
    }

    void requestNewProjectAsset(boolean audio) {
        if (delegateOwner.newProjectDraft == null) return;
        if (!delegateOwner.mainFileActions.prepareFilePicker()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(audio ? "audio/*" : "image/*");
        delegateOwner.startActivityForResult(intent, audio
                ? MainActivity.SELECT_NEW_PROJECT_AUDIO : MainActivity.SELECT_NEW_PROJECT_ILLUSTRATION);
    }

    void createProjectFromDraft(NewProjectDraft draft) {
        if (draft == null || draft != delegateOwner.newProjectDraft) return;
        if (draft.audioUri == null || draft.audioExtension == null
                || draft.illustrationUri == null || draft.illustrationExtension == null) {
            delegateOwner.showMessage(delegateOwner.getString(R.string.project_create_assets_required));
            return;
        }
        final ProjectWorkspaceCreator.Spec spec;
        try {
            String name = draft.name.getText().toString().trim();
            double bpm = Double.parseDouble(draft.bpm.getText().toString().trim());
            spec = new ProjectWorkspaceCreator.Spec(
                    UUID.randomUUID().toString(), name,
                    draft.composer.getText().toString(),
                    draft.charter.getText().toString(),
                    draft.level.getText().toString(), bpm,
                    draft.audioExtension, draft.illustrationExtension);
            delegateOwner.mainProjectSession.saveCurrentProject();
        } catch (IllegalArgumentException | IOException exception) {
            delegateOwner.showMessage(delegateOwner.getString(R.string.project_create_invalid));
            return;
        }

        Uri selectedAudio = draft.audioUri;
        Uri selectedIllustration = draft.illustrationUri;
        draft.dialog.dismiss();
        delegateOwner.newProjectDraft = null;
        long now = System.currentTimeMillis();
        delegateOwner.mainProjectSession.runProjectTask(R.string.project_creating, () -> {
            File workspace = delegateOwner.projectLibrary.workspaceForNewProject(spec.projectId);
            boolean indexed = false;
            try (InputStream audioInput = delegateOwner.newProjectDialog.openRequiredInput(selectedAudio);
                 InputStream illustrationInput = delegateOwner.newProjectDialog.openRequiredInput(selectedIllustration)) {
                ChartPackage created = new ProjectWorkspaceCreator().create(
                        workspace, spec, audioInput, illustrationInput);
                delegateOwner.projectLibrary.addImportedProject(spec.projectId, created, now);
                indexed = true;
                return delegateOwner.projectLibrary.openProject(spec.projectId, now);
            } catch (Exception exception) {
                try {
                    if (indexed) delegateOwner.projectLibrary.removeProject(spec.projectId);
                    else if (workspace.exists()) delegateOwner.projectLibrary.removeUnindexedWorkspace(spec.projectId);
                } catch (Exception cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                throw exception;
            }
        }, opened -> {
            delegateOwner.mainProjectSession.activatePackage(opened.getChartPackage(), spec.projectId, null,
                    opened.getRecord().getName());
            delegateOwner.showMessage(delegateOwner.getString(R.string.project_created, opened.getRecord().getName()));
        });
    }

    InputStream openRequiredInput(Uri uri) throws IOException {
        if (uri == null) throw new IOException(delegateOwner.getString(R.string.project_asset_not_selected));
        InputStream input = delegateOwner.getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException(delegateOwner.getString(R.string.project_asset_unreadable));
        return input;
    }

    void acceptNewProjectAsset(int requestCode, Uri uri, Intent data) {
        NewProjectDraft draft = delegateOwner.newProjectDraft;
        if (draft == null || draft.dialog == null || !draft.dialog.isShowing()) return;
        delegateOwner.takeReadPermission(uri, data);
        String displayName = AndroidPackageIo.displayName(delegateOwner.getContentResolver(), uri);
        boolean audio = requestCode == MainActivity.SELECT_NEW_PROJECT_AUDIO;
        String extension = delegateOwner.newProjectDialog.resolveAssetExtension(uri, displayName, audio);
        if (extension == null) {
            delegateOwner.showMessage(delegateOwner.getString(R.string.project_create_asset_unsupported,
                    delegateOwner.getString(audio ? R.string.project_create_asset_audio
                            : R.string.project_create_asset_illustration)));
            return;
        }
        if (audio) {
            draft.audioUri = uri;
            draft.audioExtension = extension;
            draft.audioDisplayName = displayName;
            UiText.text(draft.audioStatus, () -> delegateOwner.getString(R.string.project_create_selected, displayName));
        } else {
            draft.illustrationUri = uri;
            draft.illustrationExtension = extension;
            draft.illustrationDisplayName = displayName;
            UiText.text(draft.illustrationStatus, () -> delegateOwner.getString(
                    R.string.project_create_selected, displayName));
        }
    }

    String resolveAssetExtension(Uri uri, String displayName, boolean audio) {
        String lowerName = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        int dot = lowerName.lastIndexOf('.');
        String extension = dot >= 0 && dot + 1 < lowerName.length()
                ? lowerName.substring(dot + 1) : "";
        if (audio) {
            if (extension.matches("ogg|mp3|wav|flac|m4a|aac")) return extension;
        } else if (extension.matches("png|jpg|jpeg|webp|bmp")) {
            return extension;
        }

        String mime = delegateOwner.getContentResolver().getType(uri);
        if (mime == null) return null;
        String normalized = mime.toLowerCase(Locale.ROOT);
        if (audio) {
            if (normalized.equals("audio/mpeg")) return "mp3";
            if (normalized.equals("audio/ogg")) return "ogg";
            if (normalized.equals("audio/wav") || normalized.equals("audio/x-wav")) return "wav";
            if (normalized.equals("audio/flac")) return "flac";
            if (normalized.equals("audio/mp4") || normalized.equals("audio/x-m4a")) return "m4a";
            if (normalized.equals("audio/aac")) return "aac";
        } else {
            if (normalized.equals("image/png")) return "png";
            if (normalized.equals("image/jpeg")) return "jpg";
            if (normalized.equals("image/webp")) return "webp";
            if (normalized.equals("image/bmp")) return "bmp";
        }
        return null;
    }

    private static String value(Bundle state, String key, String fallback) {
        return state == null ? fallback : state.getString(key, fallback);
    }
}
