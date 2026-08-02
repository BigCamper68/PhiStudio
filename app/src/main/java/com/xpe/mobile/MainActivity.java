package com.xpe.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.xpe.mobile.editor.EditorBackgroundDecoder;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.audio.EditorAudioController;
import com.xpe.mobile.audio.HitSoundTimeline;
import com.xpe.mobile.io.ChartIo;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.config.EditorSettingsStore;
import com.xpe.mobile.config.ShortcutChord;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.packageio.AndroidPackageIo;
import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.packageio.PackageException;
import com.xpe.mobile.packageio.PackageWorkspaceWriter;
import com.xpe.mobile.packageio.ProjectWorkspaceCreator;
import com.xpe.mobile.project.ProjectLibrary;
import com.xpe.mobile.project.ProjectBrowserDialog;
import com.xpe.mobile.project.ProjectRecord;
import com.xpe.mobile.project.ProjectThumbnailLoader;
import com.xpe.mobile.preview.PreviewTextureDecoder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements EditorView.Callback {
    private interface ProjectTask<T> {
        T run() throws Exception;
    }

    private interface ProjectSuccess<T> {
        void accept(T value);
    }

    private interface ProjectFailure {
        void accept(Exception exception);
    }

    private static final class ImportedProject {
        final String id;
        final ChartPackage chartPackage;
        final String name;

        ImportedProject(String id, ChartPackage chartPackage, String name) {
            this.id = id;
            this.chartPackage = chartPackage;
            this.name = name;
        }
    }

    private static final class InitialContent {
        final ProjectLibrary.OpenResult project;
        final ChartDocument fallbackChart;
        final boolean autosave;
        final Exception libraryWarning;
        final boolean memoryWarning;

        private InitialContent(ProjectLibrary.OpenResult project, ChartDocument fallbackChart,
                               boolean autosave, Exception libraryWarning,
                               boolean memoryWarning) {
            this.project = project;
            this.fallbackChart = fallbackChart;
            this.autosave = autosave;
            this.libraryWarning = libraryWarning;
            this.memoryWarning = memoryWarning;
        }

        static InitialContent project(ProjectLibrary.OpenResult value) {
            return new InitialContent(value, null, false, null, false);
        }

        static InitialContent fallback(ChartDocument chart, boolean autosave,
                                       Exception warning, boolean memoryWarning) {
            return new InitialContent(null, chart, autosave, warning, memoryWarning);
        }
    }

    private static final int OPEN_CHART = 1001;
    private static final int SAVE_CHART = 1002;
    private static final int OPEN_AUDIO = 1003;
    private static final int OPEN_PACKAGE = 1004;
    private static final int SAVE_PACKAGE = 1005;
    private static final int SELECT_NEW_PROJECT_AUDIO = 1006;
    private static final int SELECT_NEW_PROJECT_ILLUSTRATION = 1007;
    private static final class NewProjectDraft {
        AlertDialog dialog;
        EditText name;
        EditText composer;
        EditText charter;
        EditText level;
        EditText bpm;
        TextView audioStatus;
        TextView illustrationStatus;
        Uri audioUri;
        Uri illustrationUri;
        String audioExtension;
        String illustrationExtension;
    }

    private EditorView editorView;
    private EditorDialogController editorDialogs;
    private EditorAudioController audioController;
    private ChartPackage currentPackage;
    private Uri currentPackageSourceUri;
    private ProjectLibrary projectLibrary;
    private String currentProjectId;
    private String currentProjectName;
    private ProjectThumbnailLoader projectThumbnails;
    private ProjectBrowserDialog projectBrowser;
    private ExecutorService projectExecutor;
    private ExecutorService previewExecutor;
    private Handler mainHandler;
    private NewProjectDraft newProjectDraft;
    private int illustrationLoadGeneration;
    private EditorSettings editorSettings;
    private final Runnable scheduledAutosave = new Runnable() {
        @Override
        public void run() {
            if (editorSettings == null || !editorSettings.autosaveEnabled) return;
            try {
                ChartDocument chart = editorView == null ? null : editorView.getChart();
                if (chart != null && editorView.isChartDirty() && !editorView.isPlaying()) {
                    if (currentPackage != null && currentProjectId != null) {
                        PackageWorkspaceWriter.writeChart(currentPackage, chart);
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
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        editorView = new EditorView(this);
        editorView.setCallback(this);
        editorSettings = EditorSettingsStore.load(getPreferences(MODE_PRIVATE));
        editorView.applySettings(editorSettings);
        mainHandler = new Handler(Looper.getMainLooper());
        audioController = new EditorAudioController(this, mainHandler,
                new EditorAudioController.Listener() {
                    @Override
                    public void onAudioStateChanged() {
                        editorView.audioStateChanged();
                    }

                    @Override
                    public void onAudioCompleted() {
                        editorView.audioCompleted();
                    }

                    @Override
                    public void showMessage(String message) {
                        MainActivity.this.showMessage(message);
                    }

                    @Override
                    public boolean isHostUnavailable() {
                        return isFinishing() || isDestroyed();
                    }
                });
        audioController.applySettings(editorSettings);
        editorDialogs = new EditorDialogController(this, editorView,
                new EditorDialogController.Host() {
                    @Override
                    public EditorSettings editorSettings() {
                        return editorSettings;
                    }

                    @Override
                    public void applyEditorSettings(EditorSettings settings) {
                        editorSettings = settings;
                        EditorSettingsStore.save(
                                getPreferences(MODE_PRIVATE), editorSettings);
                        editorView.applySettings(editorSettings);
                        applyAudioVolume();
                        scheduleAutosave();
                    }

                    @Override
                    public long packageOffsetMs() {
                        return currentPackageOffsetMs();
                    }

                    @Override
                    public long audioDurationMs() {
                        return MainActivity.this.audioDurationMs();
                    }

                    @Override
                    public void onLineAppearanceChanged() {
                        if (currentPackage != null) loadEditorIllustration(currentPackage);
                    }

                    @Override
                    public void showMessage(String message) {
                        MainActivity.this.showMessage(message);
                    }
                });
        setContentView(editorView);
        projectLibrary = new ProjectLibrary(new File(getFilesDir(), "project-library"));
        projectThumbnails = new ProjectThumbnailLoader(
                new File(getCacheDir(), "project-thumbnails"));
        projectBrowser = new ProjectBrowserDialog(this, projectLibrary, projectThumbnails);
        projectExecutor = newSingleThreadExecutor("phistudio-project-io");
        previewExecutor = newSingleThreadExecutor("phistudio-preview-prepare");
        scheduleAutosave();
        hideSystemUi();
        loadInitialChart();
    }

    @Override
    public void requestOpen() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        startActivityForResult(intent, OPEN_CHART);
    }

    @Override
    public void requestSave() {
        ChartDocument chart = editorView.getChart();
        if (chart == null) return;
        String suggested = sanitizeFileName(chart.id.isEmpty() ? chart.name : chart.id) + ".json";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, suggested);
        startActivityForResult(intent, SAVE_CHART);
    }

    @Override
    public void requestProjectSave() {
        if (currentPackage == null || currentProjectId == null || editorView.getChart() == null) {
            showMessage(getString(R.string.project_save_required));
            return;
        }
        try {
            saveCurrentProject();
            showMessage(getString(R.string.shortcut_project_saved));
        } catch (IOException exception) {
            showMessage(getString(R.string.project_library_error, safeMessage(exception)));
        }
    }

    @Override
    public void requestAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/mpeg", "audio/ogg", "audio/wav", "audio/x-wav", "audio/*"});
        startActivityForResult(intent, OPEN_AUDIO);
    }

    private void requestPackageOpen() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed", "application/octet-stream"
        });
        startActivityForResult(intent, OPEN_PACKAGE);
    }

    private void requestPackageSave() {
        if (currentPackage == null || editorView.getChart() == null) {
            showMessage(getString(R.string.package_required));
            return;
        }
        try {
            saveCurrentProject();
        } catch (IOException exception) {
            showMessage(getString(R.string.package_export_failed, safeMessage(exception)));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE,
                sanitizeFileName(currentProjectName == null
                        ? currentPackage.getProjectName() : currentProjectName) + ".zip");
        startActivityForResult(intent, SAVE_PACKAGE);
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
        int pad = dp(10);
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setPadding(pad, dp(2), pad, dp(6));

        ScrollView projectScroll = new ScrollView(this);
        projectScroll.setFillViewport(true);
        LinearLayout projectActions = new LinearLayout(this);
        projectActions.setOrientation(LinearLayout.VERTICAL);
        projectActions.setPadding(0, 0, dp(6), 0);
        projectScroll.addView(projectActions);
        columns.addView(projectScroll, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));

        ScrollView chartScroll = new ScrollView(this);
        chartScroll.setFillViewport(true);
        LinearLayout chartActions = new LinearLayout(this);
        chartActions.setOrientation(LinearLayout.VERTICAL);
        chartActions.setPadding(dp(6), 0, 0, 0);
        chartScroll.addView(chartActions);
        columns.addView(chartScroll, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));

        addMenuSection(projectActions, R.string.menu_section_projects);
        Button projects = addMenuButton(projectActions, R.string.menu_projects);
        Button importPackage = addMenuButton(projectActions, R.string.menu_import_package);
        Button exportPackage = addMenuButton(projectActions, R.string.menu_export_package);

        addMenuSection(chartActions, R.string.menu_section_chart);
        Button metadata = addMenuButton(chartActions, R.string.menu_metadata);
        Button bpmList = addMenuButton(chartActions, R.string.menu_bpm);
        Button lineList = addMenuButton(chartActions, R.string.menu_lines);
        Button storyboard = addMenuButton(chartActions, R.string.menu_storyboard);
        Button curveNotes = addMenuButton(chartActions, R.string.menu_curve_notes);
        Button complexMove = addMenuButton(chartActions, R.string.menu_complex_move);
        Button settings = addMenuButton(chartActions, R.string.menu_settings);
        Button diagnostics = addMenuButton(chartActions, R.string.menu_diagnostics);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.menu_title)
                .setView(columns)
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        projects.setOnClickListener(view -> runMenuAction(dialog,
                () -> showProjectLibrary(false)));
        importPackage.setOnClickListener(view -> runMenuAction(dialog, this::requestPackageOpen));
        exportPackage.setOnClickListener(view -> runMenuAction(dialog, this::requestPackageSave));
        metadata.setOnClickListener(view -> runMenuAction(dialog, this::requestEditMetadata));
        bpmList.setOnClickListener(view -> runMenuAction(dialog, this::requestEditBpmList));
        lineList.setOnClickListener(view -> runMenuAction(dialog, this::requestManageLines));
        storyboard.setOnClickListener(view -> runMenuAction(dialog, this::showStoryboardEditor));
        curveNotes.setOnClickListener(view -> runMenuAction(dialog, this::showCurveNotes));
        complexMove.setOnClickListener(view -> runMenuAction(dialog, this::showComplexMove));
        settings.setOnClickListener(view -> runMenuAction(dialog, this::showSettings));
        diagnostics.setOnClickListener(view -> runMenuAction(dialog, this::showChartDiagnostics));
        showEditorWindow(dialog, null);
    }

    private void showStoryboardEditor() {
        editorDialogs.showStoryboardEditor();
    }

    private void showSettings() {
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

    private void showComplexMove() {
        editorDialogs.showComplexMove();
    }

    private void showCurveNotes() {
        editorDialogs.showCurveNotes();
    }

    private void showProjectLibrary(boolean startup) {
        runProjectTask(R.string.project_library_loading,
                projectLibrary::discoverWorkspaces,
                discovery -> projectBrowser.show(discovery, startup,
                        new ProjectBrowserDialog.Callback() {
                            @Override
                            public void onCreateRequested() {
                                showCreateProjectDialog();
                            }

                            @Override
                            public void onImportRequested() {
                                requestPackageOpen();
                            }

                            @Override
                            public void onOpen(ProjectRecord project) {
                                openLibraryProject(project, true, false);
                            }

                            @Override
                            public void onRename(ProjectRecord project) {
                                showRenameProject(project);
                            }

                            @Override
                            public void onDuplicate(ProjectRecord project) {
                                duplicateProject(project);
                            }

                            @Override
                            public void onExport(ProjectRecord project) {
                                openLibraryProject(project, false, true);
                            }

                            @Override
                            public void onRemove(ProjectRecord project) {
                                confirmRemoveProject(project);
                            }

                            @Override
                            public void onIssueAction(ProjectLibrary.WorkspaceIssue issue) {
                                handleWorkspaceIssue(issue);
                            }
                        }));
    }

    private void showCreateProjectDialog() {
        NewProjectDraft draft = new NewProjectDraft();
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = createDialogContent(scroll);
        draft.name = addField(content, getString(R.string.project_create_name), "", false);
        draft.composer = addField(content, getString(R.string.project_create_composer), "", false);
        draft.charter = addField(content, getString(R.string.project_create_charter), "", false);
        draft.level = addField(content, getString(R.string.project_create_level), "", false);
        draft.bpm = addField(content, getString(R.string.project_create_bpm), "120", true);

        Button audioButton = new Button(this);
        audioButton.setText(R.string.project_create_audio);
        audioButton.setAllCaps(false);
        content.addView(audioButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        draft.audioStatus = new TextView(this);
        draft.audioStatus.setText(R.string.project_create_audio_none);
        draft.audioStatus.setTextSize(12f);
        draft.audioStatus.setPadding(0, 0, 0, dp(8));
        content.addView(draft.audioStatus);

        Button illustrationButton = new Button(this);
        illustrationButton.setText(R.string.project_create_illustration);
        illustrationButton.setAllCaps(false);
        content.addView(illustrationButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        draft.illustrationStatus = new TextView(this);
        draft.illustrationStatus.setText(R.string.project_create_illustration_none);
        draft.illustrationStatus.setTextSize(12f);
        content.addView(draft.illustrationStatus);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.project_create_title)
                .setView(scroll)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.project_create_action, null)
                .create();
        draft.dialog = dialog;
        newProjectDraft = draft;
        audioButton.setOnClickListener(view -> requestNewProjectAsset(true));
        illustrationButton.setOnClickListener(view -> requestNewProjectAsset(false));
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> createProjectFromDraft(draft)));
        dialog.setOnDismissListener(ignored -> {
            if (newProjectDraft == draft) newProjectDraft = null;
        });
        dialog.show();
    }

    private void requestNewProjectAsset(boolean audio) {
        if (newProjectDraft == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(audio ? "audio/*" : "image/*");
        startActivityForResult(intent, audio
                ? SELECT_NEW_PROJECT_AUDIO : SELECT_NEW_PROJECT_ILLUSTRATION);
    }

    private void createProjectFromDraft(NewProjectDraft draft) {
        if (draft == null || draft != newProjectDraft) return;
        if (draft.audioUri == null || draft.audioExtension == null
                || draft.illustrationUri == null || draft.illustrationExtension == null) {
            showMessage(getString(R.string.project_create_assets_required));
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
            saveCurrentProject();
        } catch (IllegalArgumentException | IOException exception) {
            showMessage(getString(R.string.project_create_invalid));
            return;
        }

        Uri selectedAudio = draft.audioUri;
        Uri selectedIllustration = draft.illustrationUri;
        draft.dialog.dismiss();
        newProjectDraft = null;
        long now = System.currentTimeMillis();
        runProjectTask(R.string.project_creating, () -> {
            File workspace = projectLibrary.workspaceForNewProject(spec.projectId);
            boolean indexed = false;
            try (InputStream audioInput = openRequiredInput(selectedAudio);
                 InputStream illustrationInput = openRequiredInput(selectedIllustration)) {
                ChartPackage created = new ProjectWorkspaceCreator().create(
                        workspace, spec, audioInput, illustrationInput);
                projectLibrary.addImportedProject(spec.projectId, created, now);
                indexed = true;
                return projectLibrary.openProject(spec.projectId, now);
            } catch (Exception exception) {
                try {
                    if (indexed) projectLibrary.removeProject(spec.projectId);
                    else if (workspace.exists()) projectLibrary.removeUnindexedWorkspace(spec.projectId);
                } catch (Exception cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                throw exception;
            }
        }, opened -> {
            activatePackage(opened.getChartPackage(), spec.projectId, null,
                    opened.getRecord().getName());
            showMessage(getString(R.string.project_created, opened.getRecord().getName()));
        });
    }

    private InputStream openRequiredInput(Uri uri) throws IOException {
        if (uri == null) throw new IOException("Required project asset was not selected");
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("Unable to open selected project asset");
        return input;
    }

    private void acceptNewProjectAsset(int requestCode, Uri uri, Intent data) {
        NewProjectDraft draft = newProjectDraft;
        if (draft == null || draft.dialog == null || !draft.dialog.isShowing()) return;
        takeReadPermission(uri, data);
        String displayName = AndroidPackageIo.displayName(getContentResolver(), uri);
        boolean audio = requestCode == SELECT_NEW_PROJECT_AUDIO;
        String extension = resolveAssetExtension(uri, displayName, audio);
        if (extension == null) {
            showMessage(getString(R.string.project_create_asset_unsupported,
                    getString(audio ? R.string.project_create_asset_audio
                            : R.string.project_create_asset_illustration)));
            return;
        }
        if (audio) {
            draft.audioUri = uri;
            draft.audioExtension = extension;
            draft.audioStatus.setText(getString(R.string.project_create_selected, displayName));
        } else {
            draft.illustrationUri = uri;
            draft.illustrationExtension = extension;
            draft.illustrationStatus.setText(getString(
                    R.string.project_create_selected, displayName));
        }
    }

    private String resolveAssetExtension(Uri uri, String displayName, boolean audio) {
        String lowerName = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        int dot = lowerName.lastIndexOf('.');
        String extension = dot >= 0 && dot + 1 < lowerName.length()
                ? lowerName.substring(dot + 1) : "";
        if (audio) {
            if (extension.matches("ogg|mp3|wav|flac|m4a|aac")) return extension;
        } else if (extension.matches("png|jpg|jpeg|webp|bmp")) {
            return extension;
        }

        String mime = getContentResolver().getType(uri);
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

    private void confirmRemoveProject(ProjectRecord project) {
        confirmRemoveIndexedProject(project.getId(), project.getName());
    }

    private void confirmRemoveIndexedProject(String projectId, String projectName) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.project_remove_title)
                .setMessage(getString(R.string.project_remove_message, projectName))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.project_action_remove, (dialog, which) -> {
                    boolean active = projectId.equals(currentProjectId);
                    runProjectTask(R.string.project_removing,
                            () -> {
                                InitialContent fallback = active
                                        ? readFallbackContent(null, false) : null;
                                projectLibrary.removeProject(projectId);
                                return fallback;
                            }, fallback -> {
                        if (active) {
                            currentPackage = null;
                            currentPackageSourceUri = null;
                            currentProjectId = null;
                            currentProjectName = null;
                            releaseAudio();
                            activateFallbackContent(fallback);
                        }
                        showMessage(getString(R.string.project_removed, projectName));
                        showProjectLibrary(false);
                    });
                })
                .show();
    }

    private void openLibraryProject(ProjectRecord project, boolean notify, boolean exportAfterOpen) {
        try {
            saveCurrentProject();
        } catch (IOException exception) {
            showMessage(getString(R.string.project_library_error, safeMessage(exception)));
            return;
        }
        runProjectTask(R.string.project_opening,
                () -> projectLibrary.openProject(project.getId(), System.currentTimeMillis()),
                opened -> {
                    activatePackage(opened.getChartPackage(), project.getId(), null,
                            opened.getRecord().getName());
                    if (notify) showMessage(getString(
                            R.string.project_opened, opened.getRecord().getName()));
                    if (exportAfterOpen) requestPackageSave();
                });
    }

    private void showRenameProject(ProjectRecord project) {
        EditText input = new EditText(this);
        input.setText(project.getName());
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.project_rename_title)
                .setView(input)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.project_action_rename, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        ProjectLibrary.State state = projectLibrary.renameProject(
                                project.getId(), input.getText().toString());
                        ProjectRecord renamed = state.find(project.getId());
                        if (project.getId().equals(currentProjectId) && renamed != null) {
                            currentProjectName = renamed.getName();
                            editorView.setProjectName(currentProjectName);
                        }
                        dialog.dismiss();
                        showMessage(getString(R.string.project_renamed,
                                renamed == null ? input.getText().toString().trim() : renamed.getName()));
                        showProjectLibrary(false);
                    } catch (IOException exception) {
                        showMessage(getString(R.string.project_library_error, safeMessage(exception)));
                    }
                }));
        dialog.show();
    }

    private void duplicateProject(ProjectRecord project) {
        try {
            saveCurrentProject();
        } catch (IOException exception) {
            showMessage(getString(R.string.project_library_error, safeMessage(exception)));
            return;
        }
        String newId = UUID.randomUUID().toString();
        runProjectTask(R.string.project_duplicating,
                () -> projectLibrary.duplicateProject(
                        project.getId(), newId, System.currentTimeMillis()), duplicated -> {
                    activatePackage(duplicated.getChartPackage(), newId, null,
                            duplicated.getRecord().getName());
                    showMessage(getString(R.string.project_duplicated,
                            duplicated.getRecord().getName()));
                });
    }

    private void handleWorkspaceIssue(ProjectLibrary.WorkspaceIssue issue) {
        if (issue.isIndexed()) {
            confirmRemoveIndexedProject(issue.getProjectId(), issue.getDisplayName());
        } else if (issue.getStatus() == ProjectLibrary.WorkspaceStatus.RECOVERABLE) {
            runProjectTask(R.string.project_recovering,
                    () -> projectLibrary.recoverOrphan(
                            issue.getProjectId(), System.currentTimeMillis()), recovered -> {
                        activatePackage(recovered.getChartPackage(), recovered.getRecord().getId(),
                                null, recovered.getRecord().getName());
                        showMessage(getString(R.string.project_recovered,
                                recovered.getRecord().getName()));
                    });
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.project_recovery_remove_title)
                    .setMessage(getString(R.string.project_recovery_remove_message,
                            issue.getDisplayName(), issue.getMessage()))
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.project_recovery_remove, (dialog, which) ->
                            runProjectTask(R.string.project_removing,
                                    () -> projectLibrary.removeUnindexedWorkspace(
                                            issue.getProjectId()), state -> {
                                        showMessage(getString(R.string.project_recovery_removed,
                                                issue.getDisplayName()));
                                        showProjectLibrary(false);
                                    }))
                    .show();
        }
    }

    private <T> void runProjectTask(int messageResource, ProjectTask<T> task,
                                    ProjectSuccess<T> success) {
        runProjectTask(messageResource, task, success, exception -> showMessage(getString(
                R.string.project_library_error, safeMessage(exception))));
    }

    private <T> void runProjectTask(int messageResource, ProjectTask<T> task,
                                    ProjectSuccess<T> success, ProjectFailure failure) {
        AlertDialog progress = new AlertDialog.Builder(this)
                .setMessage(messageResource)
                .setCancelable(false)
                .create();
        progress.show();
        try {
            projectExecutor.execute(() -> {
                try {
                    T result = task.run();
                    mainHandler.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        progress.dismiss();
                        success.accept(result);
                    });
                } catch (Exception exception) {
                    mainHandler.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        progress.dismiss();
                        failure.accept(exception);
                    });
                } catch (OutOfMemoryError error) {
                    mainHandler.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        progress.dismiss();
                        showMessage(getString(R.string.project_memory_error));
                    });
                }
            });
        } catch (RuntimeException exception) {
            progress.dismiss();
            showMessage(getString(R.string.project_library_error, safeMessage(exception)));
        }
    }

    private void showChartDiagnostics() {
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
    public long audioDurationMs() {
        return audioController == null ? 0L : audioController.durationMillis();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == SELECT_NEW_PROJECT_AUDIO
                || requestCode == SELECT_NEW_PROJECT_ILLUSTRATION) {
            acceptNewProjectAsset(requestCode, uri, data);
            return;
        }
        try {
            if (requestCode == OPEN_CHART) {
                takeReadPermission(uri, data);
                saveCurrentProject();
                editorView.stopPlayback();
                String displayName = AndroidPackageIo.displayName(getContentResolver(), uri);
                runProjectTask(R.string.project_importing,
                        () -> ChartIo.readChart(getContentResolver(), uri), imported -> {
                            try {
                                projectLibrary.clearCurrentProject();
                            } catch (IOException exception) {
                                showMessage(getString(R.string.project_library_error,
                                        safeMessage(exception)));
                                return;
                            }
                            currentPackage = null;
                            currentPackageSourceUri = null;
                            currentProjectId = null;
                            currentProjectName = null;
                            editorView.setChart(imported);
                            editorView.markChartDirty();
                            clearEditorIllustration();
                            editorView.setProjectName(displayName);
                            showMessage(getString(R.string.chart_imported));
                        }, exception -> showMessage(getString(
                                R.string.file_error, safeMessage(exception))));
            } else if (requestCode == SAVE_CHART) {
                ChartIo.writeChart(getContentResolver(), uri, editorView.getChart());
                showMessage("Chart exported");
            } else if (requestCode == OPEN_AUDIO) {
                takeReadPermission(uri, data);
                loadAudio(uri, true);
            } else if (requestCode == OPEN_PACKAGE) {
                takeReadPermission(uri, data);
                saveCurrentProject();
                String projectId = UUID.randomUUID().toString();
                File workspace = projectLibrary.workspaceForNewProject(projectId);
                runProjectTask(R.string.project_importing, () -> {
                    ChartPackage imported = AndroidPackageIo.importPackage(
                            getContentResolver(), uri, workspace);
                    projectLibrary.addImportedProject(
                            projectId, imported, System.currentTimeMillis());
                    ProjectRecord record = projectLibrary.load().find(projectId);
                    String name = record == null
                            ? imported.getProjectName() : record.getName();
                    return new ImportedProject(projectId, imported, name);
                }, imported -> {
                    activatePackage(imported.chartPackage, imported.id, uri, imported.name);
                    showMessage(getString(R.string.package_imported,
                            imported.chartPackage.getProjectName()));
                }, exception -> {
                    int message = exception instanceof PackageException
                            && ((PackageException) exception).getRetainedWorkspace() != null
                            ? R.string.package_import_failed_retained
                            : R.string.package_import_failed;
                    showMessage(getString(message, safeMessage(exception)));
                });
            } else if (requestCode == SAVE_PACKAGE) {
                if (currentPackage == null) {
                    showMessage(getString(R.string.package_required));
                    return;
                }
                if (currentPackageSourceUri != null && currentPackageSourceUri.equals(uri)) {
                    showMessage(getString(R.string.package_source_overwrite_blocked));
                    return;
                }
                AndroidPackageIo.exportPackage(getContentResolver(), uri, getCacheDir(),
                        currentPackage, editorView.getChart());
                showMessage(getString(R.string.package_exported, currentPackage.getProjectName()));
            }
        } catch (PackageException exception) {
            if (requestCode == OPEN_PACKAGE) {
                int message = exception.getRetainedWorkspace() == null
                        ? R.string.package_import_failed : R.string.package_import_failed_retained;
                showMessage(getString(message, safeMessage(exception)));
            } else {
                showMessage(getString(R.string.package_export_failed, safeMessage(exception)));
            }
        } catch (Exception exception) {
            if (requestCode == OPEN_PACKAGE) {
                showMessage(getString(R.string.package_import_failed, safeMessage(exception)));
            } else if (requestCode == SAVE_PACKAGE) {
                showMessage(getString(R.string.package_export_failed, safeMessage(exception)));
            } else {
                showMessage(getString(R.string.file_error, safeMessage(exception)));
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        pauseAudio();
        ChartDocument chart = editorView.getChart();
        if (chart != null && editorView.isChartDirty()
                && editorSettings != null && editorSettings.autosaveEnabled) {
            try {
                if (currentPackage != null && currentProjectId != null) {
                    PackageWorkspaceWriter.writeChart(currentPackage, chart);
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
                && editorSettings != null && !(getCurrentFocus() instanceof EditText)) {
            if (matchesShortcut(event, editorSettings.shortcutSave)) {
                saveFromShortcut();
                return true;
            }
            if (matchesShortcut(event, editorSettings.shortcutUndo)) {
                editorView.performUndo();
                return true;
            }
            if (matchesShortcut(event, editorSettings.shortcutRedo)) {
                editorView.performRedo();
                return true;
            }
            if (matchesShortcut(event, editorSettings.shortcutCopy)) {
                editorView.performCopy();
                return true;
            }
            if (matchesShortcut(event, editorSettings.shortcutCut)) {
                editorView.performCut();
                return true;
            }
            if (matchesShortcut(event, editorSettings.shortcutMirrorPaste)) {
                editorView.performPaste(true);
                return true;
            }
            if (matchesShortcut(event, editorSettings.shortcutPaste)) {
                editorView.performPaste(false);
                return true;
            }
            if (matchesShortcut(event, editorSettings.shortcutDelete)) {
                editorView.performDeleteSelection();
                return true;
            }
            if (matchesShortcut(event, editorSettings.shortcutPlayPause)) {
                editorView.performTogglePlay();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void saveFromShortcut() {
        requestProjectSave();
    }

    private boolean matchesShortcut(KeyEvent event, String text) {
        final ShortcutChord chord;
        try {
            chord = ShortcutChord.parse(text);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return event.isCtrlPressed() == chord.control
                && event.isAltPressed() == chord.alt
                && event.isShiftPressed() == chord.shift
                && event.getKeyCode() == shortcutKeyCode(chord.key);
    }

    private static int shortcutKeyCode(String key) {
        if (key.length() == 1) {
            char value = key.charAt(0);
            if (value >= 'A' && value <= 'Z') return KeyEvent.KEYCODE_A + value - 'A';
            if (value >= '0' && value <= '9') return KeyEvent.KEYCODE_0 + value - '0';
        }
        switch (key) {
            case "SPACE": return KeyEvent.KEYCODE_SPACE;
            case "DELETE": return KeyEvent.KEYCODE_FORWARD_DEL;
            case "BACKSPACE": return KeyEvent.KEYCODE_DEL;
            case "ENTER": return KeyEvent.KEYCODE_ENTER;
            case "ESC": return KeyEvent.KEYCODE_ESCAPE;
            case "LEFT": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "RIGHT": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "UP": return KeyEvent.KEYCODE_DPAD_UP;
            case "DOWN": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "PAGEUP": return KeyEvent.KEYCODE_PAGE_UP;
            case "PAGEDOWN": return KeyEvent.KEYCODE_PAGE_DOWN;
            case "HOME": return KeyEvent.KEYCODE_MOVE_HOME;
            case "END": return KeyEvent.KEYCODE_MOVE_END;
            default:
                if (key.matches("F(?:[1-9]|1[0-2])")) {
                    return KeyEvent.KEYCODE_F1 + Integer.parseInt(key.substring(1)) - 1;
                }
                return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    private void scheduleAutosave() {
        if (mainHandler == null) return;
        mainHandler.removeCallbacks(scheduledAutosave);
        if (editorSettings == null || !editorSettings.autosaveEnabled) return;
        long delay = Math.max(1000L, Math.min(24L * 60L * 60L * 1000L,
                Math.round(editorSettings.autosaveIntervalSeconds * 1000.0)));
        mainHandler.postDelayed(scheduledAutosave, delay);
    }

    private void applyAudioVolume() {
        if (audioController != null) audioController.applySettings(editorSettings);
    }

    private void loadInitialChart() {
        runProjectTask(R.string.project_library_loading, this::readInitialContent,
                content -> {
                    if (content.memoryWarning) {
                        showMessage(getString(R.string.project_memory_error));
                    } else if (content.libraryWarning != null) {
                        showMessage(getString(R.string.project_library_error,
                                safeMessage(content.libraryWarning)));
                    }
                    if (content.project != null) {
                        ProjectLibrary.OpenResult opened = content.project;
                        activatePackage(opened.getChartPackage(), opened.getRecord().getId(),
                                null, opened.getRecord().getName());
                    } else {
                        activateFallbackContent(content);
                    }
                    showProjectLibrary(true);
                }, exception -> {
                    showMessage("Unable to load initial chart: " + safeMessage(exception));
                    showProjectLibrary(true);
                });
    }

    private InitialContent readInitialContent() throws IOException {
        Exception libraryWarning = null;
        boolean memoryWarning = false;
        try {
            ProjectLibrary.State state = projectLibrary.load();
            String currentId = state.getCurrentProjectId();
            if (currentId != null) {
                ProjectLibrary.OpenResult opened = projectLibrary.openProject(
                        currentId, System.currentTimeMillis());
                return InitialContent.project(opened);
            }
        } catch (Exception exception) {
            libraryWarning = exception;
        } catch (OutOfMemoryError error) {
            memoryWarning = true;
        }
        return readFallbackContent(libraryWarning, memoryWarning);
    }

    private InitialContent readFallbackContent(Exception libraryWarning,
                                               boolean memoryWarning) throws IOException {
        try {
            return InitialContent.fallback(
                    ChartIo.readAutosave(this), true, libraryWarning, memoryWarning);
        } catch (Exception | OutOfMemoryError noAutosave) {
            try {
                return InitialContent.fallback(
                        ChartIo.readAsset(this, "demo_chart.json"),
                        false, libraryWarning, memoryWarning);
            } catch (Exception | OutOfMemoryError demoFailure) {
                IOException failure = new IOException("Unable to load demo chart", demoFailure);
                failure.addSuppressed(noAutosave);
                if (libraryWarning != null) failure.addSuppressed(libraryWarning);
                throw failure;
            }
        }
    }

    private void activateFallbackContent(InitialContent content) {
        clearEditorIllustration();
        editorView.setChart(content.fallbackChart);
        editorView.setProjectName(getString(content.autosave
                ? R.string.project_autosave : R.string.project_demo));
        if (content.autosave) showMessage("Autosave restored");
        restoreAudioReference();
    }

    private void activatePackage(ChartPackage chartPackage, String projectId, Uri sourceUri,
                                 String localName) {
        editorView.stopPlayback();
        currentPackage = chartPackage;
        currentProjectId = projectId;
        currentProjectName = localName;
        currentPackageSourceUri = sourceUri;
        editorView.setChart(chartPackage.getChart());
        editorView.setPackageOffsetMs(chartPackage.getManifestOffsetMs());
        editorView.setUseRpe170Speed(chartPackage.isUseRpe170Speed());
        editorView.setProjectName(localName == null ? chartPackage.getProjectName() : localName);
        loadEditorIllustration(chartPackage);
        getPreferences(MODE_PRIVATE).edit().remove("audio_uri").apply();
        File packageAudio = chartPackage.getAudioFile();
        if (packageAudio != null && packageAudio.isFile()) {
            try {
                loadAudio(Uri.fromFile(packageAudio), false);
            } catch (IOException exception) {
                releaseAudio();
                editorView.audioStateChanged();
                showMessage(getString(R.string.project_audio_unavailable, safeMessage(exception)));
            }
        } else {
            releaseAudio();
            editorView.audioStateChanged();
        }
    }

    private void loadEditorIllustration(ChartPackage chartPackage) {
        int generation = ++illustrationLoadGeneration;
        editorView.setBackgroundIllustration(null);
        editorView.setPreviewLineTextures(null);
        File illustration = chartPackage == null ? null : chartPackage.getIllustrationFile();
        Set<String> textureNames = new LinkedHashSet<>();
        if (chartPackage != null && chartPackage.getChart() != null) {
            for (JudgeLine line : chartPackage.getChart().judgeLines) {
                if (line != null && line.texture != null) textureNames.add(line.texture);
            }
        }
        boolean hasIllustration = illustration != null && illustration.isFile();
        if (!hasIllustration && textureNames.isEmpty()) return;
        int targetWidth = Math.max(1, Math.round(
                getResources().getDisplayMetrics().widthPixels * 0.70f));
        int targetHeight = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        try {
            previewExecutor.execute(() -> {
                Bitmap bitmap = hasIllustration ? EditorBackgroundDecoder.decode(
                        illustration, targetWidth, targetHeight) : null;
                Map<String, PreviewTextureDecoder.Texture> lineTextures = chartPackage == null
                        ? Collections.emptyMap() : PreviewTextureDecoder.decode(
                        chartPackage.getWorkspace(), textureNames);
                mainHandler.post(() -> {
                    if (generation != illustrationLoadGeneration
                            || isFinishing() || isDestroyed()) {
                        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                        PreviewTextureDecoder.recycleAll(lineTextures);
                        return;
                    }
                    editorView.setBackgroundIllustration(bitmap);
                    editorView.setPreviewLineTextures(lineTextures);
                });
            });
        } catch (RuntimeException exception) {
            // The project remains usable without a decoded illustration.
        }
    }

    private void clearEditorIllustration() {
        illustrationLoadGeneration++;
        if (editorView != null) {
            editorView.setBackgroundIllustration(null);
            editorView.setPreviewLineTextures(null);
        }
    }

    private void saveCurrentProject() throws IOException {
        if (currentPackage != null && currentProjectId != null
                && editorView.getChart() != null && editorView.isChartDirty()) {
            PackageWorkspaceWriter.writeChart(currentPackage, editorView.getChart());
            editorView.markChartSaved();
        }
    }

    private void loadAudio(Uri uri, boolean remember) throws IOException {
        if (audioController == null) throw new IOException("Audio controller is unavailable");
        audioController.load(uri);
        if (remember) {
            getPreferences(MODE_PRIVATE).edit().putString("audio_uri", uri.toString()).apply();
        }
    }

    private void restoreAudioReference() {
        String stored = getPreferences(MODE_PRIVATE).getString("audio_uri", "");
        if (stored.isEmpty()) return;
        try {
            loadAudio(Uri.parse(stored), false);
        } catch (Exception ignored) {
            getPreferences(MODE_PRIVATE).edit().remove("audio_uri").apply();
        }
    }

    private void releaseAudio() {
        if (audioController != null) audioController.clearSource();
    }

    private long currentPackageOffsetMs() {
        return currentPackage == null ? 0L : currentPackage.getManifestOffsetMs();
    }

    private void takeReadPermission(Uri uri, Intent data) {
        if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some document providers do not support persistent grants.
        }
    }

    private void addMenuSection(LinearLayout parent, int textResource) {
        TextView title = new TextView(this);
        title.setText(textResource);
        title.setTextSize(16f);
        title.setPadding(dp(4), dp(4), dp(4), dp(6));
        parent.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private Button addMenuButton(LinearLayout parent, int textResource) {
        Button button = new Button(this);
        button.setText(textResource);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinHeight(dp(48));
        button.setBackgroundResource(R.drawable.phistudio_menu_button);
        parent.addView(button, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return button;
    }

    private void runMenuAction(AlertDialog menu, Runnable action) {
        menu.dismiss();
        action.run();
    }

    private void showEditorWindow(AlertDialog dialog, Runnable afterShow) {
        showEditorWindow(dialog, afterShow, 0.72f, 0.78f);
    }

    private void showEditorWindow(AlertDialog dialog, Runnable afterShow,
                                  float widthFraction, float heightFraction) {
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                int width = getResources().getDisplayMetrics().widthPixels;
                int height = getResources().getDisplayMetrics().heightPixels;
                window.setLayout((int) (width * widthFraction),
                        (int) (height * heightFraction));
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            if (afterShow != null) afterShow.run();
        });
        dialog.show();
    }

    private LinearLayout createDialogContent(ScrollView scrollView) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        content.setPadding(pad, pad, pad, pad);
        scrollView.addView(content);
        return content;
    }

    private EditText addField(LinearLayout parent, String label, String value, boolean numeric) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(14f);
        title.setPadding(0, dp(8), 0, dp(3));
        parent.addView(title);

        EditText field = new EditText(this);
        field.setText(value);
        field.setSingleLine(true);
        field.setSelectAllOnFocus(true);
        field.setGravity(Gravity.CENTER_VERTICAL);
        if (numeric) {
            field.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | InputType.TYPE_NUMBER_FLAG_SIGNED);
        }
        parent.addView(field, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return field;
    }

    private void hideSystemUi() {
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String sanitizeFileName(String value) {
        String safe = value == null ? "chart" : value.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return safe.isEmpty() ? "chart" : safe;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? exception.getClass().getSimpleName() : message;
    }

    private static ExecutorService newSingleThreadExecutor(String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }
}
