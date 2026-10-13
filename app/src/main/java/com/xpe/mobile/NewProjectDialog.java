package com.xpe.mobile;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
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

    NewProjectDialog(MainActivity delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void showCreateProjectDialog() {
        NewProjectDraft draft = new NewProjectDraft();
        ScrollView scroll = new ScrollView(delegateOwner);
        LinearLayout content = delegateOwner.mainMenuUi.createDialogContent(scroll);
        draft.name = delegateOwner.mainMenuUi.addField(content, delegateOwner.getString(R.string.project_create_name), "", false);
        draft.composer = delegateOwner.mainMenuUi.addField(content, delegateOwner.getString(R.string.project_create_composer), "", false);
        draft.charter = delegateOwner.mainMenuUi.addField(content, delegateOwner.getString(R.string.project_create_charter), "", false);
        draft.level = delegateOwner.mainMenuUi.addField(content, delegateOwner.getString(R.string.project_create_level), "", false);
        draft.bpm = delegateOwner.mainMenuUi.addField(content, delegateOwner.getString(R.string.project_create_bpm), "120", true);

        Button audioButton = new Button(delegateOwner);
        audioButton.setText(R.string.project_create_audio);
        audioButton.setAllCaps(false);
        content.addView(audioButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        draft.audioStatus = new TextView(delegateOwner);
        draft.audioStatus.setText(R.string.project_create_audio_none);
        draft.audioStatus.setTextSize(12f);
        draft.audioStatus.setPadding(0, 0, 0, delegateOwner.dp(8));
        content.addView(draft.audioStatus);

        Button illustrationButton = new Button(delegateOwner);
        illustrationButton.setText(R.string.project_create_illustration);
        illustrationButton.setAllCaps(false);
        content.addView(illustrationButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        draft.illustrationStatus = new TextView(delegateOwner);
        draft.illustrationStatus.setText(R.string.project_create_illustration_none);
        draft.illustrationStatus.setTextSize(12f);
        content.addView(draft.illustrationStatus);

        AlertDialog dialog = new AlertDialog.Builder(delegateOwner)
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
        if (uri == null) throw new IOException("Required project asset was not selected");
        InputStream input = delegateOwner.getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("Unable to open selected project asset");
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
            draft.audioStatus.setText(delegateOwner.getString(R.string.project_create_selected, displayName));
        } else {
            draft.illustrationUri = uri;
            draft.illustrationExtension = extension;
            draft.illustrationStatus.setText(delegateOwner.getString(
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
}
