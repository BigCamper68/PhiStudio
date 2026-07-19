package com.xpe.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.MediaTimestamp;
import android.media.PlaybackParams;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.xpe.mobile.editor.ChartDiagnostic;
import com.xpe.mobile.editor.ChartDiagnostics;
import com.xpe.mobile.editor.BatchEditOperation;
import com.xpe.mobile.editor.BatchValueTransform;
import com.xpe.mobile.editor.ComplexMoveGenerator;
import com.xpe.mobile.editor.ComplexMovePreviewView;
import com.xpe.mobile.editor.CurveNoteGenerator;
import com.xpe.mobile.editor.CurveNotePreviewView;
import com.xpe.mobile.editor.EditorBackgroundDecoder;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.editor.EasingPreviewView;
import com.xpe.mobile.editor.EventCloneOperation;
import com.xpe.mobile.editor.PropertyValidator;
import com.xpe.mobile.editor.StoryboardEventValidator;
import com.xpe.mobile.audio.AudioSourceFormat;
import com.xpe.mobile.audio.Mp3PcmDecoder;
import com.xpe.mobile.audio.PcmAudioAsset;
import com.xpe.mobile.audio.PcmAudioPlayer;
import com.xpe.mobile.audio.PlaybackPositionTracker;
import com.xpe.mobile.audio.PlaybackSeekCoordinator;
import com.xpe.mobile.io.ChartIo;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.config.EditorSettingsStore;
import com.xpe.mobile.config.ShortcutChord;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.Easing;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;
import com.xpe.mobile.model.StoryboardEventType;
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
import com.xpe.mobile.preview.ChartEvaluator;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    private interface StoryboardTypeAction {
        void run(StoryboardEventType type);
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

    private static final class StoryboardEntry {
        final StoryboardEventType type;
        final ExtendedLineEvents.TimedEvent event;

        StoryboardEntry(StoryboardEventType type, ExtendedLineEvents.TimedEvent event) {
            this.type = type;
            this.event = event;
        }
    }

    private static final int OPEN_CHART = 1001;
    private static final int SAVE_CHART = 1002;
    private static final int OPEN_AUDIO = 1003;
    private static final int OPEN_PACKAGE = 1004;
    private static final int SAVE_PACKAGE = 1005;
    private static final int SELECT_NEW_PROJECT_AUDIO = 1006;
    private static final int SELECT_NEW_PROJECT_ILLUSTRATION = 1007;
    private static final long AUDIO_SEEK_RETRY_DELAY_MS = 40L;

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
    private MediaPlayer mediaPlayer;
    private SoundPool hitSoundPool;
    private final Map<NoteType, Integer> hitSoundIds = new EnumMap<>(NoteType.class);
    private final Set<Integer> loadedHitSounds =
            Collections.synchronizedSet(new LinkedHashSet<>());
    private boolean audioPrepared;
    private boolean audioStartPending;
    private boolean audioSeekPending;
    private int audioCommandGeneration;
    private long pendingAudioPositionMs;
    private final PlaybackPositionTracker playbackPositionTracker =
            new PlaybackPositionTracker();
    private final PlaybackSeekCoordinator playbackSeekCoordinator =
            new PlaybackSeekCoordinator();
    private boolean currentAudioIsMp3;
    private int audioLoadGeneration;
    private Future<?> audioDecodeTask;
    private File decodedMp3PcmFile;
    private PcmAudioPlayer pcmAudioPlayer;
    private Uri audioUri;
    private ChartPackage currentPackage;
    private Uri currentPackageSourceUri;
    private ProjectLibrary projectLibrary;
    private String currentProjectId;
    private String currentProjectName;
    private ProjectThumbnailLoader projectThumbnails;
    private ProjectBrowserDialog projectBrowser;
    private ExecutorService projectExecutor;
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
        initializeHitSounds();
        setContentView(editorView);
        projectLibrary = new ProjectLibrary(new File(getFilesDir(), "project-library"));
        projectThumbnails = new ProjectThumbnailLoader(
                new File(getCacheDir(), "project-thumbnails"));
        projectBrowser = new ProjectBrowserDialog(this, projectLibrary, projectThumbnails);
        projectExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        scheduleAutosave();
        hideSystemUi();
        loadInitialChart();
        if (currentPackage == null) restoreAudioReference();
        editorView.post(() -> showProjectLibrary(true));
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
        ChartDocument chart = editorView.getChart();
        if (chart == null) return;

        ScrollView scrollView = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        content.setPadding(pad, pad, pad, pad);
        scrollView.addView(content);

        EditText name = addField(content, "Name", chart.name, false);
        EditText composer = addField(content, "Composer", chart.composer, false);
        EditText charter = addField(content, "Charter", chart.charter, false);
        EditText level = addField(content, "Level", chart.level, false);
        EditText id = addField(content, "ID / project folder", chart.id, false);
        EditText offset = addField(content, getString(R.string.field_chart_offset),
                Integer.toString(chart.offsetMs), false);
        offset.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        offset.setKeyListener(DigitsKeyListener.getInstance("0123456789-"));
        TextView offsetHint = new TextView(this);
        long packageOffsetMs = currentPackageOffsetMs();
        offsetHint.setText(getString(R.string.chart_offset_hint, packageOffsetMs,
                (long) chart.offsetMs));
        offsetHint.setTextSize(12f);
        content.addView(offsetHint);
        double currentBpm = chart.bpmChanges.isEmpty() ? 120.0 : chart.bpmChanges.get(0).bpm;
        EditText bpm = addField(content, "Base BPM", String.format(Locale.US, "%.6f", currentBpm), true);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Chart metadata")
                .setView(scrollView)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (ignoredDialog, which) -> {
                    try {
                        String parsedName = nonEmpty(name.getText().toString(), "Untitled");
                        String parsedComposer = composer.getText().toString().trim();
                        String parsedCharter = charter.getText().toString().trim();
                        String parsedLevel = level.getText().toString().trim();
                        String parsedId = id.getText().toString().trim();
                        int parsedOffset = Integer.parseInt(offset.getText().toString().trim());
                        double parsedBpm = Double.parseDouble(bpm.getText().toString().trim());
                        if (!Double.isFinite(parsedBpm) || parsedBpm <= 0.0) {
                            throw new NumberFormatException("BPM must be positive and finite");
                        }
                        if (chart.bpmChanges.isEmpty()) chart.bpmChanges.add(new BpmChange());
                        BpmChange baseChange = chart.bpmChanges.get(0);
                        if (Double.compare(baseChange.bpm, parsedBpm) != 0) {
                            BpmChange edited = baseChange.copy();
                            edited.bpm = parsedBpm;
                            EditorView.BpmApplyResult result = editorView.applyBpmProperties(baseChange, edited);
                            if (result != EditorView.BpmApplyResult.APPLIED) {
                                showBpmApplyError(result);
                                return;
                            }
                        }
                        chart.name = parsedName;
                        chart.composer = parsedComposer;
                        chart.charter = parsedCharter;
                        chart.level = parsedLevel;
                        chart.id = parsedId;
                        chart.offsetMs = parsedOffset;
                        editorView.chartMetadataChanged();
                        showMessage("Metadata saved");
                    } catch (NumberFormatException exception) {
                        showMessage("Invalid offset or BPM");
                    }
                })
                .create();
        showEditorWindow(dialog, null);
    }

    @Override
    public void requestEditBpmList() {
        ChartDocument chart = editorView.getChart();
        if (chart == null) return;
        if (chart.bpmChanges.isEmpty()) chart.bpmChanges.add(new BpmChange());
        chart.sortBpm();

        int pad = dp(12);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(columns, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        ScrollView listScroll = new ScrollView(this);
        listScroll.setFillViewport(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        columns.addView(listScroll, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1.05f));

        ScrollView formScroll = new ScrollView(this);
        formScroll.setFillViewport(true);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(14), 0, 0, dp(8));
        formScroll.addView(form, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        columns.addView(formScroll, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));

        TextView selectionTitle = new TextView(this);
        selectionTitle.setTextSize(18f);
        selectionTitle.setPadding(0, 0, 0, dp(6));
        form.addView(selectionTitle);
        TextView baseHint = new TextView(this);
        baseHint.setText(R.string.bpm_list_base_hint);
        baseHint.setTextSize(13f);
        baseHint.setPadding(0, 0, 0, dp(6));
        form.addView(baseHint);

        EditText startTime = addField(form, getString(R.string.field_bpm_start),
                chart.bpmChanges.get(0).startTime.toString(), false);
        EditText bpmValue = addField(form, getString(R.string.field_bpm_value),
                formatNumber(chart.bpmChanges.get(0).bpm), true);
        bpmValue.setEnabled(true);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, 0);
        root.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        Button newButton = addActionButton(actions, R.string.bpm_list_new);
        Button applyButton = addActionButton(actions, R.string.bpm_list_apply);
        Button deleteButton = addActionButton(actions, R.string.bpm_list_delete);
        Button closeButton = addActionButton(actions, R.string.bpm_list_close);

        BpmChange[] selected = new BpmChange[]{chart.bpmChanges.get(0)};
        boolean[] creating = new boolean[]{false};
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            chart.sortBpm();
            list.removeAllViews();
            for (int index = 0; index < chart.bpmChanges.size(); index++) {
                BpmChange change = chart.bpmChanges.get(index);
                boolean selectedRow = !creating[0] && change == selected[0];
                Button row = new Button(this);
                String suffix = index == 0 ? getString(R.string.bpm_list_base_suffix) : "";
                String rowText = getString(R.string.bpm_list_row, index + 1,
                        change.startTime.toString(), compactBpm(change.bpm), suffix);
                row.setText(selectedRow
                        ? getString(R.string.bpm_list_selected_prefix, rowText)
                        : rowText);
                row.setAllCaps(false);
                row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                row.setMinHeight(dp(48));
                row.setAlpha(selectedRow ? 1.0f : 0.78f);
                row.setEnabled(true);
                row.setOnClickListener(view -> {
                    selected[0] = change;
                    creating[0] = false;
                    refresh[0].run();
                    formScroll.smoothScrollTo(0, 0);
                });
                list.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }

            if (creating[0]) {
                selectionTitle.setText(R.string.bpm_list_new_title);
                startTime.setEnabled(true);
                bpmValue.setEnabled(true);
                deleteButton.setEnabled(false);
            } else {
                int index = chart.bpmChanges.indexOf(selected[0]);
                if (index < 0) {
                    selected[0] = chart.bpmChanges.get(0);
                    index = 0;
                }
                BpmChange change = selected[0];
                selectionTitle.setText(getString(R.string.bpm_list_edit_title, index + 1));
                startTime.setText(change.startTime.toString());
                bpmValue.setText(formatNumber(change.bpm));
                startTime.setEnabled(index != 0);
                bpmValue.setEnabled(true);
                deleteButton.setEnabled(index > 0 && chart.bpmChanges.size() > 1);
            }
        };

        newButton.setOnClickListener(view -> {
            chart.sortBpm();
            BpmChange last = chart.bpmChanges.get(chart.bpmChanges.size() - 1);
            selected[0] = null;
            creating[0] = true;
            startTime.setText(BeatTime.fromDouble(
                    last.startTime.toDouble() + 1.0, 1).toString());
            bpmValue.setText(formatNumber(last.bpm));
            refresh[0].run();
            formScroll.smoothScrollTo(0, 0);
            startTime.requestFocus();
        });
        applyButton.setOnClickListener(view -> {
            try {
                BpmChange edited = creating[0] ? new BpmChange() : selected[0].copy();
                edited.startTime = BeatTime.parse(startTime.getText().toString());
                edited.bpm = parseDouble(bpmValue);
                EditorView.BpmApplyResult result = creating[0]
                        ? editorView.addBpmChange(edited)
                        : editorView.applyBpmProperties(selected[0], edited);
                if (result != EditorView.BpmApplyResult.APPLIED) {
                    showBpmApplyError(result);
                    return;
                }
                selected[0] = creating[0] ? edited : selected[0];
                creating[0] = false;
                refresh[0].run();
                showMessage(getString(R.string.bpm_list_saved));
            } catch (IllegalArgumentException exception) {
                showMessage(getString(R.string.bpm_validation_invalid));
            }
        });
        deleteButton.setOnClickListener(view -> {
            BpmChange target = selected[0];
            int index = chart.bpmChanges.indexOf(target);
            EditorView.BpmApplyResult result = editorView.deleteBpmChange(target);
            if (result != EditorView.BpmApplyResult.APPLIED) {
                showBpmApplyError(result);
                return;
            }
            selected[0] = chart.bpmChanges.get(
                    Math.max(0, Math.min(index - 1, chart.bpmChanges.size() - 1)));
            creating[0] = false;
            refresh[0].run();
            showMessage(getString(R.string.bpm_list_deleted));
        });

        refresh[0].run();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.bpm_list_title)
                .setView(root)
                .create();
        closeButton.setOnClickListener(view -> dialog.dismiss());
        showEditorWindow(dialog, null);
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
        JudgeLine line = editorView.getCurrentJudgeLine();
        if (line == null) {
            showMessage(getString(R.string.storyboard_chart_required));
            return;
        }

        int pad = dp(10);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(4), pad, dp(6));
        TextView scope = addHint(root, getString(R.string.storyboard_scope,
                editorView.getLineIndex(), line.name));
        scope.setTextSize(13f);

        List<String> filters = new ArrayList<>();
        filters.add(getString(R.string.storyboard_filter_all));
        for (StoryboardEventType type : StoryboardEventType.values()) {
            filters.add(storyboardTypeLabel(type));
        }
        Spinner filter = addStringSpinner(root,
                getString(R.string.storyboard_filter), filters, 0);

        ListView list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setMinimumHeight(dp(120));
        List<String> rows = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_single_choice, rows);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        LinearLayout firstActions = new LinearLayout(this);
        firstActions.setOrientation(LinearLayout.HORIZONTAL);
        Button add = addActionButton(firstActions, R.string.storyboard_add);
        Button edit = addActionButton(firstActions, R.string.storyboard_edit);
        Button duplicate = addActionButton(firstActions, R.string.storyboard_duplicate);
        Button delete = addActionButton(firstActions, R.string.storyboard_delete);
        setCompactButtons(firstActions, add, edit, duplicate, delete);
        root.addView(firstActions);

        LinearLayout secondActions = new LinearLayout(this);
        secondActions.setOrientation(LinearLayout.HORIZONTAL);
        Button glue = addActionButton(secondActions, R.string.storyboard_glue);
        Button split = addActionButton(secondActions, R.string.storyboard_split);
        Button close = addActionButton(secondActions, R.string.storyboard_close);
        setCompactButtons(secondActions, glue, split, close);
        root.addView(secondActions);

        List<StoryboardEntry> entries = new ArrayList<>();
        StoryboardEntry[] selected = new StoryboardEntry[1];
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            StoryboardEntry previous = selected[0];
            entries.clear();
            rows.clear();
            StoryboardEventType only = filter.getSelectedItemPosition() <= 0
                    ? null : StoryboardEventType.values()[filter.getSelectedItemPosition() - 1];
            ExtendedLineEvents storyboard = line.extended;
            if (storyboard != null) {
                for (StoryboardEventType type : StoryboardEventType.values()) {
                    if (only != null && only != type) continue;
                    for (ExtendedLineEvents.TimedEvent event : storyboard.events(type)) {
                        StoryboardEntry entry = new StoryboardEntry(type, event);
                        entries.add(entry);
                        rows.add(storyboardEntryLabel(entry));
                    }
                }
            }
            adapter.notifyDataSetChanged();
            selected[0] = null;
            list.clearChoices();
            if (previous != null) {
                for (int index = 0; index < entries.size(); index++) {
                    StoryboardEntry candidate = entries.get(index);
                    if (candidate.type == previous.type && candidate.event == previous.event) {
                        selected[0] = candidate;
                        list.setItemChecked(index, true);
                        break;
                    }
                }
            }
            boolean hasSelection = selected[0] != null;
            edit.setEnabled(hasSelection);
            duplicate.setEnabled(hasSelection);
            delete.setEnabled(hasSelection);
            glue.setEnabled(hasSelection);
            split.setEnabled(hasSelection);
        };
        setSpinnerChangeListener(filter, refresh[0]);
        list.setOnItemClickListener((parent, view, position, id) -> {
            selected[0] = position >= 0 && position < entries.size()
                    ? entries.get(position) : null;
            refresh[0].run();
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.storyboard_title)
                .setView(root)
                .create();
        add.setOnClickListener(view -> {
            int position = filter.getSelectedItemPosition();
            if (position > 0) {
                StoryboardEventType type = StoryboardEventType.values()[position - 1];
                showStoryboardEventForm(type, newStoryboardEvent(type), true, refresh[0]);
            } else {
                chooseStoryboardType(type -> showStoryboardEventForm(
                        type, newStoryboardEvent(type), true, refresh[0]));
            }
        });
        edit.setOnClickListener(view -> {
            StoryboardEntry entry = selected[0];
            if (entry != null) showStoryboardEventForm(
                    entry.type, entry.event, false, refresh[0]);
        });
        duplicate.setOnClickListener(view -> {
            StoryboardEntry entry = selected[0];
            if (entry == null) return;
            try {
                ExtendedLineEvents.TimedEvent copy = entry.event.copy();
                BeatTime duration = copy.endTime.minus(copy.startTime);
                copy.startTime = copy.endTime;
                copy.endTime = copy.endTime.plus(duration);
                showStoryboardEventForm(entry.type, copy, true, refresh[0]);
            } catch (ArithmeticException exception) {
                showMessage(getString(R.string.storyboard_invalid_time));
            }
        });
        delete.setOnClickListener(view -> {
            StoryboardEntry entry = selected[0];
            if (entry == null) return;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.storyboard_delete_title)
                    .setMessage(getString(R.string.storyboard_delete_message,
                            storyboardTypeLabel(entry.type), entry.event.startTime.toString()))
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.storyboard_delete, (ignored, which) -> {
                        EditorView.StoryboardApplyResult result =
                                editorView.deleteStoryboardEvent(entry.type, entry.event);
                        if (result != EditorView.StoryboardApplyResult.APPLIED) {
                            showStoryboardApplyError(result);
                            return;
                        }
                        selected[0] = null;
                        refresh[0].run();
                        showMessage(getString(R.string.storyboard_deleted));
                    })
                    .show();
        });
        glue.setOnClickListener(view -> {
            StoryboardEntry entry = selected[0];
            if (entry == null) return;
            EditorView.StoryboardApplyResult result =
                    editorView.glueStoryboardEvent(entry.type, entry.event);
            if (result != EditorView.StoryboardApplyResult.APPLIED) {
                showStoryboardApplyError(result);
                return;
            }
            refresh[0].run();
            showMessage(getString(R.string.storyboard_glued));
        });
        split.setOnClickListener(view -> {
            StoryboardEntry entry = selected[0];
            if (entry == null) return;
            EditorView.StoryboardApplyResult result = editorView.splitStoryboardEvent(
                    entry.type, entry.event, editorView.getCurrentBeatTime());
            if (result != EditorView.StoryboardApplyResult.APPLIED) {
                showStoryboardApplyError(result);
                return;
            }
            refresh[0].run();
            showMessage(getString(R.string.storyboard_split_applied));
        });
        close.setOnClickListener(view -> dialog.dismiss());
        refresh[0].run();
        showEditorWindow(dialog, null, 0.84f, 0.96f);
    }

    private void chooseStoryboardType(StoryboardTypeAction action) {
        StoryboardEventType[] types = StoryboardEventType.values();
        String[] labels = new String[types.length];
        for (int index = 0; index < types.length; index++) {
            labels[index] = storyboardTypeLabel(types[index]);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.storyboard_choose_type)
                .setItems(labels, (dialog, which) -> action.run(types[which]))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showStoryboardEventForm(StoryboardEventType type,
                                         ExtendedLineEvents.TimedEvent source,
                                         boolean creating, Runnable afterSave) {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout content = createDialogContent(scrollView);
        addHint(content, getString(storyboardHelpResource(type)));
        EditText startTime = addField(content, getString(R.string.field_start_beat),
                source.startTime.toString(), false);
        EditText endTime = addField(content, getString(R.string.field_end_beat),
                source.endTime.toString(), false);
        addHint(content, getString(R.string.storyboard_time_help));
        LinearLayout timeActions = new LinearLayout(this);
        timeActions.setOrientation(LinearLayout.HORIZONTAL);
        Button useStartBeat = addActionButton(
                timeActions, R.string.storyboard_use_start_beat);
        Button useEndBeat = addActionButton(
                timeActions, R.string.storyboard_use_end_beat);
        content.addView(timeActions);
        useStartBeat.setOnClickListener(view -> startTime.setText(
                editorView.getCurrentBeatTime().toString()));
        useEndBeat.setOnClickListener(view -> endTime.setText(
                editorView.getCurrentBeatTime().toString()));

        boolean numeric = type.isNumeric();
        EditText startValue = addField(content,
                getString(R.string.storyboard_start_value),
                storyboardStartValue(source), numeric);
        EditText endValue = addField(content,
                getString(R.string.storyboard_end_value),
                storyboardEndValue(source), numeric);
        if (type == StoryboardEventType.TEXT) {
            configureStoryboardTextField(startValue);
            configureStoryboardTextField(endValue);
        }
        CheckBox locked = addCheckBox(content, R.string.field_event_locked,
                storyboardValuesEqual(source));
        Spinner easingType = addSpinner(content, R.string.field_event_easing_type,
                R.array.event_easing_entries,
                Math.max(Easing.MIN_TYPE,
                        Math.min(Easing.MAX_TYPE, source.easingType)) - 1);
        TextView previewLabel = new TextView(this);
        previewLabel.setText(R.string.field_event_easing_preview);
        previewLabel.setTextSize(14f);
        previewLabel.setPadding(0, dp(8), 0, dp(3));
        content.addView(previewLabel);
        EasingPreviewView easingPreview = new EasingPreviewView(this);
        easingPreview.setContentDescription(
                getString(R.string.field_event_easing_preview_description));
        content.addView(easingPreview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(148)));
        EditText easingLeft = addField(content, getString(R.string.field_event_easing_left),
                formatNumber(source.easingLeft), true);
        EditText easingRight = addField(content, getString(R.string.field_event_easing_right),
                formatNumber(source.easingRight), true);
        EditText linkGroup = addField(content, getString(R.string.field_event_link_group),
                Integer.toString(source.linkGroup), true);
        CheckBox bezier = addCheckBox(content, R.string.field_event_bezier, source.bezier);
        EditText bezierX1 = addField(content, getString(R.string.field_event_bezier_x1),
                formatNumber(source.bezierPoints[0]), true);
        EditText bezierY1 = addField(content, getString(R.string.field_event_bezier_y1),
                formatNumber(source.bezierPoints[1]), true);
        EditText bezierX2 = addField(content, getString(R.string.field_event_bezier_x2),
                formatNumber(source.bezierPoints[2]), true);
        EditText bezierY2 = addField(content, getString(R.string.field_event_bezier_y2),
                formatNumber(source.bezierPoints[3]), true);

        Runnable refreshEasing = () -> updateEasingEditor(
                false, easingType, easingLeft, easingRight, bezier,
                bezierX1, bezierY1, bezierX2, bezierY2, easingPreview);
        setSpinnerChangeListener(easingType, refreshEasing);
        bezier.setOnCheckedChangeListener((button, checked) -> refreshEasing.run());
        watch(refreshEasing, easingLeft, easingRight,
                bezierX1, bezierY1, bezierX2, bezierY2);
        locked.setOnCheckedChangeListener((button, checked) -> endValue.setEnabled(!checked));
        endValue.setEnabled(!locked.isChecked());
        refreshEasing.run();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(creating ? R.string.storyboard_new_title
                        : R.string.storyboard_edit_title, storyboardTypeLabel(type)))
                .setView(scrollView)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_apply, null)
                .create();
        showEditorWindow(dialog, () -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        ExtendedLineEvents.TimedEvent edited = source.copy();
                        edited.startTime = BeatTime.parseFlexible(
                                startTime.getText().toString());
                        edited.endTime = BeatTime.parseFlexible(
                                endTime.getText().toString());
                        readStoryboardValues(type, edited, startValue, endValue,
                                locked.isChecked());
                        edited.easingType = easingType.getSelectedItemPosition()
                                + Easing.MIN_TYPE;
                        edited.easingLeft = parseDouble(easingLeft);
                        edited.easingRight = parseDouble(easingRight);
                        edited.linkGroup = parseInteger(linkGroup);
                        edited.bezier = bezier.isChecked();
                        edited.bezierPoints[0] = parseDouble(bezierX1);
                        edited.bezierPoints[1] = parseDouble(bezierY1);
                        edited.bezierPoints[2] = parseDouble(bezierX2);
                        edited.bezierPoints[3] = parseDouble(bezierY2);

                        StoryboardEventValidator.Error validation =
                                StoryboardEventValidator.validateFields(type, edited);
                        if (validation != StoryboardEventValidator.Error.NONE) {
                            showStoryboardValidationError(validation);
                            return;
                        }
                        EditorView.StoryboardApplyResult result = creating
                                ? editorView.addStoryboardEvent(type, edited)
                                : editorView.applyStoryboardEvent(type, source, edited);
                        if (result != EditorView.StoryboardApplyResult.APPLIED) {
                            showStoryboardApplyError(result);
                            return;
                        }
                        dialog.dismiss();
                        afterSave.run();
                        showMessage(getString(creating ? R.string.storyboard_created
                                : R.string.storyboard_saved));
                    } catch (IllegalArgumentException | ArithmeticException exception) {
                        showMessage(getString(R.string.validation_invalid_number_or_beat));
                    }
                }));
    }

    private ExtendedLineEvents.TimedEvent newStoryboardEvent(StoryboardEventType type) {
        ExtendedLineEvents.TimedEvent event;
        if (type == StoryboardEventType.COLOR) {
            event = new ExtendedLineEvents.ColorEvent();
        } else if (type == StoryboardEventType.TEXT) {
            event = new ExtendedLineEvents.TextEvent();
        } else {
            ExtendedLineEvents.NumericEvent numeric = new ExtendedLineEvents.NumericEvent();
            if (type == StoryboardEventType.SCALE_X
                    || type == StoryboardEventType.SCALE_Y) {
                numeric.start = 1.0;
                numeric.end = 1.0;
            }
            event = numeric;
        }
        event.startTime = editorView.getCurrentBeatTime();
        event.endTime = event.startTime.plus(new BeatTime(1, 0, 1));
        return event;
    }

    private void configureStoryboardTextField(EditText field) {
        field.setSingleLine(false);
        field.setMinLines(2);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    }

    private void readStoryboardValues(StoryboardEventType type,
                                      ExtendedLineEvents.TimedEvent event,
                                      EditText startValue, EditText endValue,
                                      boolean locked) {
        if (event instanceof ExtendedLineEvents.NumericEvent) {
            ExtendedLineEvents.NumericEvent numeric =
                    (ExtendedLineEvents.NumericEvent) event;
            numeric.start = parseDouble(startValue);
            numeric.end = locked ? numeric.start : parseDouble(endValue);
        } else if (event instanceof ExtendedLineEvents.ColorEvent) {
            ExtendedLineEvents.ColorEvent color = (ExtendedLineEvents.ColorEvent) event;
            color.startRgb = parseRgb(startValue.getText().toString());
            color.endRgb = locked ? color.startRgb
                    : parseRgb(endValue.getText().toString());
        } else if (event instanceof ExtendedLineEvents.TextEvent) {
            ExtendedLineEvents.TextEvent text = (ExtendedLineEvents.TextEvent) event;
            text.start = startValue.getText().toString();
            text.end = locked ? text.start : endValue.getText().toString();
        }
    }

    private String storyboardEntryLabel(StoryboardEntry entry) {
        return getString(R.string.storyboard_row, storyboardTypeLabel(entry.type),
                entry.event.startTime.toString(), entry.event.endTime.toString(),
                storyboardValueSummary(entry.event));
    }

    private String storyboardValueSummary(ExtendedLineEvents.TimedEvent event) {
        if (event instanceof ExtendedLineEvents.NumericEvent) {
            ExtendedLineEvents.NumericEvent numeric =
                    (ExtendedLineEvents.NumericEvent) event;
            return getString(R.string.storyboard_values,
                    formatNumber(numeric.start), formatNumber(numeric.end));
        }
        if (event instanceof ExtendedLineEvents.ColorEvent) {
            ExtendedLineEvents.ColorEvent color = (ExtendedLineEvents.ColorEvent) event;
            return getString(R.string.storyboard_values,
                    formatRgb(color.startRgb), formatRgb(color.endRgb));
        }
        ExtendedLineEvents.TextEvent text = (ExtendedLineEvents.TextEvent) event;
        return getString(R.string.storyboard_text_values,
                compactStoryboardText(text.start), compactStoryboardText(text.end));
    }

    private String storyboardStartValue(ExtendedLineEvents.TimedEvent event) {
        if (event instanceof ExtendedLineEvents.NumericEvent) {
            return formatNumber(((ExtendedLineEvents.NumericEvent) event).start);
        }
        if (event instanceof ExtendedLineEvents.ColorEvent) {
            return formatRgb(((ExtendedLineEvents.ColorEvent) event).startRgb);
        }
        return ((ExtendedLineEvents.TextEvent) event).start;
    }

    private String storyboardEndValue(ExtendedLineEvents.TimedEvent event) {
        if (event instanceof ExtendedLineEvents.NumericEvent) {
            return formatNumber(((ExtendedLineEvents.NumericEvent) event).end);
        }
        if (event instanceof ExtendedLineEvents.ColorEvent) {
            return formatRgb(((ExtendedLineEvents.ColorEvent) event).endRgb);
        }
        return ((ExtendedLineEvents.TextEvent) event).end;
    }

    private static boolean storyboardValuesEqual(ExtendedLineEvents.TimedEvent event) {
        if (event instanceof ExtendedLineEvents.NumericEvent) {
            ExtendedLineEvents.NumericEvent numeric =
                    (ExtendedLineEvents.NumericEvent) event;
            return Double.compare(numeric.start, numeric.end) == 0;
        }
        if (event instanceof ExtendedLineEvents.ColorEvent) {
            ExtendedLineEvents.ColorEvent color = (ExtendedLineEvents.ColorEvent) event;
            return color.startRgb == color.endRgb;
        }
        ExtendedLineEvents.TextEvent text = (ExtendedLineEvents.TextEvent) event;
        return text.start.equals(text.end);
    }

    private String storyboardTypeLabel(StoryboardEventType type) {
        switch (type) {
            case SCALE_X: return getString(R.string.storyboard_type_scale_x);
            case SCALE_Y: return getString(R.string.storyboard_type_scale_y);
            case COLOR: return getString(R.string.storyboard_type_color);
            case PAINT: return getString(R.string.storyboard_type_paint);
            case TEXT: return getString(R.string.storyboard_type_text);
            case INCLINE: return getString(R.string.storyboard_type_incline);
            case GIF: return getString(R.string.storyboard_type_gif);
            default: return type.name();
        }
    }

    private int storyboardHelpResource(StoryboardEventType type) {
        switch (type) {
            case SCALE_X: return R.string.storyboard_help_scale_x;
            case SCALE_Y: return R.string.storyboard_help_scale_y;
            case COLOR: return R.string.storyboard_help_color;
            case PAINT: return R.string.storyboard_help_paint;
            case TEXT: return R.string.storyboard_help_text;
            case INCLINE: return R.string.storyboard_help_incline;
            case GIF: return R.string.storyboard_help_gif;
            default: return R.string.storyboard_help_generic;
        }
    }

    private void showStoryboardValidationError(StoryboardEventValidator.Error error) {
        int message;
        switch (error) {
            case NEGATIVE_START_TIME: message = R.string.validation_negative_start; break;
            case END_TIME_NOT_AFTER_START: message = R.string.validation_end_after_start; break;
            case NON_FINITE_NUMBER: message = R.string.validation_non_finite; break;
            case EASING_OUT_OF_RANGE: message = R.string.validation_event_easing; break;
            case EASING_WINDOW_INVALID: message = R.string.validation_event_easing_window; break;
            case LINK_GROUP_NEGATIVE: message = R.string.validation_event_link_group; break;
            case TEXT_VALUE_MISSING: message = R.string.storyboard_text_required; break;
            case EVENT_OVERLAP: message = R.string.validation_event_overlap; break;
            default: message = R.string.validation_invalid_number_or_beat; break;
        }
        showMessage(getString(message));
    }

    private void showStoryboardApplyError(EditorView.StoryboardApplyResult result) {
        int message;
        switch (result) {
            case EVENT_OVERLAP: message = R.string.validation_event_overlap; break;
            case TARGET_NOT_FOUND: message = R.string.validation_target_changed; break;
            case NO_PREVIOUS: message = R.string.storyboard_no_previous; break;
            case NO_CHANGE: message = R.string.storyboard_glue_no_change; break;
            case SPLIT_OUTSIDE: message = R.string.storyboard_split_inside; break;
            default: message = R.string.validation_invalid_number_or_beat; break;
        }
        showMessage(getString(message));
    }

    private static String compactStoryboardText(String value) {
        String compact = value == null ? "" : value.replace('\n', ' ');
        return compact.length() <= 32 ? compact : compact.substring(0, 31) + "…";
    }

    private static String formatRgb(int rgb) {
        return String.format(Locale.US, "#%06X", rgb & 0xFFFFFF);
    }

    private void showSettings() {
        EditorSettings draft = editorSettings == null
                ? new EditorSettings() : editorSettings.copy();
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = createDialogContent(scroll);

        addMenuSection(content, R.string.settings_general);
        EditText musicVolume = addField(content, getString(R.string.settings_music_volume),
                formatNumber(draft.musicVolume), true);
        EditText soundEffectVolume = addField(content, getString(R.string.settings_sound_effect_volume),
                formatNumber(draft.soundEffectVolume), true);
        CheckBox highlight = addCheckBox(content, R.string.settings_highlight_simultaneous,
                draft.highlightSimultaneousNotes);
        EditText noteWidth = addField(content, getString(R.string.settings_note_width),
                formatNumber(draft.noteWidthPixels), true);
        EditText lineWidth = addField(content, getString(R.string.settings_line_width),
                formatNumber(draft.lineDefaultWidth), true);
        CheckBox markLineId = addCheckBox(content, R.string.settings_mark_line_id,
                draft.markLineId);
        EditText lineColor = addField(content, getString(R.string.settings_line_color),
                String.format(Locale.US, "#%06X", draft.lineColorRgb), false);
        EditText backgroundBrightness = addField(content,
                getString(R.string.settings_background_brightness),
                Integer.toString(draft.backgroundBrightness), true);
        EditText tapOffset = addField(content, getString(R.string.settings_hitsound_tap_offset),
                Integer.toString(draft.tapFlickHitsoundOffsetMs), true);
        EditText dragOffset = addField(content, getString(R.string.settings_hitsound_drag_offset),
                Integer.toString(draft.dragHitsoundOffsetMs), true);

        addMenuSection(content, R.string.settings_other);
        CheckBox autosave = addCheckBox(content, R.string.settings_autosave,
                draft.autosaveEnabled);
        EditText autosaveInterval = addField(content,
                getString(R.string.settings_autosave_interval),
                formatNumber(draft.autosaveIntervalSeconds), true);
        EditText timelineScroll = addField(content,
                getString(R.string.settings_timeline_scroll_speed),
                formatNumber(draft.timelineScrollSpeed), true);
        EditText eventScroll = addField(content, getString(R.string.settings_event_scroll_speed),
                formatNumber(draft.eventScrollSpeed), true);
        EditText previewAlpha = addField(content,
                getString(R.string.settings_preview_background_alpha),
                formatNumber(draft.previewBackgroundAlpha), true);
        EditText playerWidth = addField(content, getString(R.string.settings_player_width),
                Integer.toString(draft.playerWidth), true);
        EditText playerHeight = addField(content, getString(R.string.settings_player_height),
                Integer.toString(draft.playerHeight), true);
        CheckBox autoApply = addCheckBox(content, R.string.settings_auto_apply,
                draft.autoApplyPropertyEdits);
        CheckBox autoClipboard = addCheckBox(content, R.string.settings_auto_clipboard,
                draft.autoMoveToClipboard);
        CheckBox showTips = addCheckBox(content, R.string.settings_show_tips, draft.showTips);
        CheckBox xyBinding = addCheckBox(content, R.string.settings_xy_binding,
                draft.xyBindingEnabled);
        CheckBox skipUndo = addCheckBox(content, R.string.settings_skip_undo,
                draft.skipWhenUndoRedo);
        CheckBox autoStick = addCheckBox(content, R.string.settings_auto_stick,
                draft.autoStickEvents);
        CheckBox splitSnap = addCheckBox(content, R.string.settings_split_snap_to_grid,
                draft.splitSnapToGrid);
        EditText cutDensity = addField(content, getString(R.string.settings_cut_density),
                formatNumber(draft.cutDensity), true);
        CheckBox drawCurves = addCheckBox(content, R.string.settings_draw_event_curves,
                draft.drawEventCurves);
        CheckBox drawNumbers = addCheckBox(content, R.string.settings_draw_event_numbers,
                draft.drawEventNumbers);

        addMenuSection(content, R.string.settings_hotkeys);
        addHint(content, getString(R.string.settings_hotkeys_help));
        EditText shortcutPlay = addField(content, getString(R.string.settings_hotkey_play),
                draft.shortcutPlayPause, false);
        EditText shortcutSave = addField(content, getString(R.string.settings_hotkey_save),
                draft.shortcutSave, false);
        EditText shortcutUndo = addField(content, getString(R.string.settings_hotkey_undo),
                draft.shortcutUndo, false);
        EditText shortcutRedo = addField(content, getString(R.string.settings_hotkey_redo),
                draft.shortcutRedo, false);
        EditText shortcutCopy = addField(content, getString(R.string.settings_hotkey_copy),
                draft.shortcutCopy, false);
        EditText shortcutCut = addField(content, getString(R.string.settings_hotkey_cut),
                draft.shortcutCut, false);
        EditText shortcutPaste = addField(content, getString(R.string.settings_hotkey_paste),
                draft.shortcutPaste, false);
        EditText shortcutMirror = addField(content, getString(R.string.settings_hotkey_mirror),
                draft.shortcutMirrorPaste, false);
        EditText shortcutDelete = addField(content, getString(R.string.settings_hotkey_delete),
                draft.shortcutDelete, false);

        addMenuSection(content, R.string.settings_correction);
        EditText correctionX = addField(content, getString(R.string.settings_correction_x),
                formatNumber(draft.correctionXThreshold), true);
        EditText correctionCollision = addField(content,
                getString(R.string.settings_correction_collision),
                formatNumber(draft.correctionCollisionDistance), true);
        EditText correctionRead = addField(content, getString(R.string.settings_correction_read),
                formatNumber(draft.correctionReadTimeSeconds), true);
        EditText correctionDrag = addField(content, getString(R.string.settings_correction_drag),
                formatNumber(draft.correctionDragWarningSeconds), true);
        EditText correctionCombo = addField(content,
                getString(R.string.settings_correction_combination),
                formatNumber(draft.correctionCombinationSeconds), true);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setView(scroll)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_apply, null)
                .create();
        showEditorWindow(dialog, () -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        draft.musicVolume = parseDouble(musicVolume);
                        draft.soundEffectVolume = parseDouble(soundEffectVolume);
                        draft.highlightSimultaneousNotes = highlight.isChecked();
                        draft.noteWidthPixels = parseDouble(noteWidth);
                        draft.lineDefaultWidth = parseDouble(lineWidth);
                        draft.markLineId = markLineId.isChecked();
                        draft.lineColorRgb = parseRgb(lineColor.getText().toString());
                        draft.backgroundBrightness = parseInteger(backgroundBrightness);
                        draft.tapFlickHitsoundOffsetMs = parseInteger(tapOffset);
                        draft.dragHitsoundOffsetMs = parseInteger(dragOffset);
                        draft.autosaveEnabled = autosave.isChecked();
                        draft.autosaveIntervalSeconds = parseDouble(autosaveInterval);
                        draft.timelineScrollSpeed = parseDouble(timelineScroll);
                        draft.eventScrollSpeed = parseDouble(eventScroll);
                        draft.previewBackgroundAlpha = parseDouble(previewAlpha);
                        draft.playerWidth = parseInteger(playerWidth);
                        draft.playerHeight = parseInteger(playerHeight);
                        draft.autoApplyPropertyEdits = autoApply.isChecked();
                        draft.autoMoveToClipboard = autoClipboard.isChecked();
                        draft.showTips = showTips.isChecked();
                        draft.xyBindingEnabled = xyBinding.isChecked();
                        draft.skipWhenUndoRedo = skipUndo.isChecked();
                        draft.autoStickEvents = autoStick.isChecked();
                        draft.splitSnapToGrid = splitSnap.isChecked();
                        draft.cutDensity = parseDouble(cutDensity);
                        draft.drawEventCurves = drawCurves.isChecked();
                        draft.drawEventNumbers = drawNumbers.isChecked();
                        draft.shortcutPlayPause = shortcutPlay.getText().toString().trim();
                        draft.shortcutSave = shortcutSave.getText().toString().trim();
                        draft.shortcutUndo = shortcutUndo.getText().toString().trim();
                        draft.shortcutRedo = shortcutRedo.getText().toString().trim();
                        draft.shortcutCopy = shortcutCopy.getText().toString().trim();
                        draft.shortcutCut = shortcutCut.getText().toString().trim();
                        draft.shortcutPaste = shortcutPaste.getText().toString().trim();
                        draft.shortcutMirrorPaste = shortcutMirror.getText().toString().trim();
                        draft.shortcutDelete = shortcutDelete.getText().toString().trim();
                        draft.correctionXThreshold = parseDouble(correctionX);
                        draft.correctionCollisionDistance = parseDouble(correctionCollision);
                        draft.correctionReadTimeSeconds = parseDouble(correctionRead);
                        draft.correctionDragWarningSeconds = parseDouble(correctionDrag);
                        draft.correctionCombinationSeconds = parseDouble(correctionCombo);
                        if (!draft.isValid()) throw new IllegalArgumentException();
                    } catch (IllegalArgumentException exception) {
                        showMessage(getString(R.string.settings_invalid));
                        return;
                    }
                    editorSettings = draft.copy();
                    EditorSettingsStore.save(getPreferences(MODE_PRIVATE), editorSettings);
                    editorView.applySettings(editorSettings);
                    applyAudioVolume();
                    scheduleAutosave();
                    dialog.dismiss();
                    showMessage(getString(R.string.settings_saved));
                }));
    }

    @Override
    public void requestAdvancedBatchEdit() {
        List<Note> notes = editorView.getSelectedNotesForBatch();
        List<LineEvent> events = editorView.getSelectedEventsForBatch();
        if (notes.isEmpty() && events.isEmpty()) {
            showMessage(getString(R.string.batch_empty_selection));
            return;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = createDialogContent(scroll);
        addHint(content, getString(R.string.batch_help));

        List<String> targetEntries = new ArrayList<>();
        List<Boolean> noteTargets = new ArrayList<>();
        if (!notes.isEmpty()) {
            targetEntries.add(getString(R.string.batch_target_notes, notes.size()));
            noteTargets.add(true);
        }
        if (!events.isEmpty()) {
            targetEntries.add(getString(R.string.batch_target_events, events.size()));
            noteTargets.add(false);
        }
        Spinner target = addStringSpinner(content, getString(R.string.batch_target),
                targetEntries, 0);
        Spinner eventType = addStringSpinner(content, getString(R.string.batch_event_type),
                eventTypeLabels(), 0);
        Spinner field = addStringSpinner(content, getString(R.string.batch_data_type),
                resourceEntries(R.array.batch_note_fields), 0);
        EditText lower = addField(content, getString(R.string.batch_lower_bound), "0", true);
        EditText upper = addField(content, getString(R.string.batch_upper_bound), "0", true);
        EditText easing = addField(content, getString(R.string.batch_easing), "1", true);
        EditText sequence = addField(content, getString(R.string.batch_periodic_sequence),
                "1", false);
        EditText disturbance = addField(content, getString(R.string.batch_disturbance),
                "0", true);
        Spinner mode = addSpinner(content, R.string.batch_edit_mode,
                R.array.batch_edit_modes, 0);

        Runnable updateTarget = () -> {
            boolean noteMode = noteTargets.get(target.getSelectedItemPosition());
            eventType.setEnabled(!noteMode);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item,
                    resourceEntries(noteMode ? R.array.batch_note_fields
                            : R.array.batch_event_fields));
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            field.setAdapter(adapter);
        };
        setSpinnerChangeListener(target, updateTarget);
        updateTarget.run();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.batch_title)
                .setView(scroll)
                .setNegativeButton(R.string.action_cancel, null)
                .setNeutralButton(R.string.batch_stick, null)
                .setPositiveButton(R.string.action_apply, null)
                .create();
        showEditorWindow(dialog, () -> {
            Button stick = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            stick.setVisibility(events.isEmpty() ? View.GONE : View.VISIBLE);
            stick.setOnClickListener(view -> {
                EventType type = EventType.values()[eventType.getSelectedItemPosition()];
                BatchEditOperation.Result result = editorView.stickSelectedEvents(type);
                if (result.error != BatchEditOperation.Error.NONE) {
                    showMessage(batchErrorMessage(result.error));
                    return;
                }
                dialog.dismiss();
                showMessage(getResources().getQuantityString(R.plurals.batch_stick_applied,
                        result.events.size(), result.events.size()));
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                BatchValueTransform.Spec profile;
                try {
                    profile = batchProfile(lower, upper, easing, sequence, disturbance,
                            System.nanoTime());
                } catch (IllegalArgumentException exception) {
                    showMessage(getString(R.string.batch_invalid_profile));
                    return;
                }
                BatchValueTransform.Mode selectedMode = BatchValueTransform.Mode.values()[
                        mode.getSelectedItemPosition()];
                boolean noteMode = noteTargets.get(target.getSelectedItemPosition());
                BatchEditOperation.Result result;
                if (noteMode) {
                    result = editorView.applyNoteBatch(
                            BatchEditOperation.NoteField.values()[field.getSelectedItemPosition()],
                            profile, selectedMode);
                } else {
                    result = editorView.applyEventBatch(
                            BatchEditOperation.EventField.values()[field.getSelectedItemPosition()],
                            profile, selectedMode,
                            EventType.values()[eventType.getSelectedItemPosition()]);
                }
                if (result.error != BatchEditOperation.Error.NONE) {
                    showMessage(batchErrorMessage(result.error));
                    return;
                }
                dialog.dismiss();
                int count = noteMode ? result.notes.size() : result.events.size();
                showMessage(getResources().getQuantityString(
                        R.plurals.batch_applied, count, count));
            });
        });
    }

    @Override
    public void requestEventClone() {
        List<LineEvent> events = editorView.getSelectedEventsForBatch();
        if (events.isEmpty()) {
            showMessage(getString(R.string.event_clone_empty));
            return;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = createDialogContent(scroll);
        addHint(content, getResources().getQuantityString(R.plurals.event_clone_help,
                events.size(), events.size(), editorView.getEventLayerIndex()));
        EditText lines = addField(content, getString(R.string.event_clone_line_sequence),
                Integer.toString(editorView.getLineIndex()), false);
        EditText timeIncrement = addField(content,
                getString(R.string.event_clone_time_increment), "0:0/1", false);
        EditText xUled = addField(content, getString(R.string.event_clone_x_uled),
                "0 0 1 0", false);
        EditText xSequence = addField(content, getString(R.string.event_clone_x_sequence),
                "1", false);
        EditText yUled = addField(content, getString(R.string.event_clone_y_uled),
                "0 0 1 0", false);
        EditText ySequence = addField(content, getString(R.string.event_clone_y_sequence),
                "1", false);
        EditText rotateUled = addField(content, getString(R.string.event_clone_rotate_uled),
                "0 0 1 0", false);
        EditText rotateSequence = addField(content,
                getString(R.string.event_clone_rotate_sequence), "1", false);
        EditText alphaUled = addField(content, getString(R.string.event_clone_alpha_uled),
                "0 0 1 0", false);
        EditText alphaSequence = addField(content,
                getString(R.string.event_clone_alpha_sequence), "1", false);
        CheckBox keepSource = addCheckBox(content, R.string.event_clone_keep_source, false);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.event_clone_title)
                .setView(scroll)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.event_clone_apply, null)
                .create();
        showEditorWindow(dialog, () -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    EventCloneOperation.Spec spec = new EventCloneOperation.Spec();
                    long seed = System.nanoTime();
                    try {
                        spec.lineSequence = EventCloneOperation.parseLineSequence(
                                lines.getText().toString());
                        spec.timeIncrement = BeatTime.parseFlexible(
                                timeIncrement.getText().toString());
                        spec.xProfile = cloneProfile(xUled, xSequence, seed + 1);
                        spec.yProfile = cloneProfile(yUled, ySequence, seed + 2);
                        spec.rotateProfile = cloneProfile(rotateUled, rotateSequence, seed + 3);
                        spec.alphaProfile = cloneProfile(alphaUled, alphaSequence, seed + 4);
                        spec.keepSource = keepSource.isChecked();
                    } catch (IllegalArgumentException exception) {
                        showMessage(getString(R.string.event_clone_invalid));
                        return;
                    }
                    EventCloneOperation.Result result = editorView.applyEventClone(spec);
                    if (result.error != EventCloneOperation.Error.NONE) {
                        showMessage(eventCloneErrorMessage(result.error));
                        return;
                    }
                    dialog.dismiss();
                    showMessage(getResources().getQuantityString(R.plurals.event_clone_applied,
                            result.events.size(), result.events.size()));
                }));
    }

    private BatchValueTransform.Spec batchProfile(EditText lower, EditText upper,
                                                  EditText easing, EditText sequence,
                                                  EditText disturbance, long seed) {
        BatchValueTransform.Spec result = new BatchValueTransform.Spec();
        result.lowerBound = parseDouble(lower);
        result.upperBound = parseDouble(upper);
        result.easingType = parseInteger(easing);
        result.periodicSequence = BatchValueTransform.parseSequence(
                sequence.getText().toString());
        result.disturbance = parseDouble(disturbance);
        result.randomSeed = seed;
        if (!BatchValueTransform.isValid(result)) {
            throw new IllegalArgumentException("invalid profile");
        }
        return result;
    }

    private BatchValueTransform.Spec cloneProfile(EditText uled, EditText sequence,
                                                  long seed) {
        String[] values = uled.getText().toString().trim().split("[\\s,;]+", -1);
        if (values.length != 4) throw new IllegalArgumentException("U.L.E.D needs four values");
        BatchValueTransform.Spec result = new BatchValueTransform.Spec();
        result.lowerBound = Double.parseDouble(values[0]);
        result.upperBound = Double.parseDouble(values[1]);
        result.easingType = Integer.parseInt(values[2]);
        result.disturbance = Double.parseDouble(values[3]);
        result.periodicSequence = BatchValueTransform.parseSequence(
                sequence.getText().toString());
        result.randomSeed = seed;
        if (!BatchValueTransform.isValid(result)) {
            throw new IllegalArgumentException("invalid U.L.E.D profile");
        }
        return result;
    }

    private List<String> eventTypeLabels() {
        List<String> result = new ArrayList<>();
        for (EventType type : EventType.values()) result.add(eventTypeLabel(type));
        return result;
    }

    private List<String> resourceEntries(int resource) {
        String[] entries = getResources().getStringArray(resource);
        List<String> result = new ArrayList<>(entries.length);
        java.util.Collections.addAll(result, entries);
        return result;
    }

    private String batchErrorMessage(BatchEditOperation.Error error) {
        switch (error) {
            case EMPTY_SELECTION: return getString(R.string.batch_empty_selection);
            case MIXED_EVENT_TYPES: return getString(R.string.batch_mixed_events);
            case UNSUPPORTED_FIELD: return getString(R.string.batch_unsupported_field);
            case INVALID_PROFILE: return getString(R.string.batch_invalid_profile);
            case TARGET_NOT_FOUND: return getString(R.string.validation_target_changed);
            default: return getString(R.string.batch_invalid_result);
        }
    }

    private String eventCloneErrorMessage(EventCloneOperation.Error error) {
        switch (error) {
            case EMPTY_SELECTION: return getString(R.string.event_clone_empty);
            case INVALID_LINE_SEQUENCE: return getString(R.string.event_clone_invalid_lines);
            case INVALID_TIME_INCREMENT: return getString(R.string.event_clone_invalid_time);
            case INVALID_PROFILE: return getString(R.string.event_clone_invalid_profile);
            case TOO_MANY_EVENTS: return getString(R.string.event_clone_too_many);
            case EVENT_OVERLAP: return getString(R.string.event_clone_overlap);
            case RESERVED_LAYER: return getString(R.string.validation_event_reserved_layer);
            case TARGET_NOT_FOUND: return getString(R.string.validation_target_changed);
            default: return getString(R.string.event_clone_invalid_result);
        }
    }

    private void showComplexMove() {
        ChartDocument chart = editorView.getChart();
        if (chart == null) return;
        BeatTime startBeat = editorView.getCurrentBeatTime();
        if (startBeat.compareTo(new BeatTime(1, 0, 1)) < 0) {
            startBeat = new BeatTime(1, 0, 1);
        }
        BeatTime endBeat = startBeat.plus(new BeatTime(2, 0, 1));

        ScrollView scrollView = new ScrollView(this);
        LinearLayout content = createDialogContent(scrollView);
        addHint(content, getString(R.string.complex_move_scope,
                editorView.getLineIndex(), editorView.getEventLayerIndex()));
        addHint(content, getString(R.string.complex_move_time_help));
        EditText startTime = addField(content, getString(R.string.complex_move_start_time),
                startBeat.toString(), false);
        EditText endTime = addField(content, getString(R.string.complex_move_end_time),
                endBeat.toString(), false);
        EditText xEquation = addField(content, getString(R.string.complex_move_x_equation),
                getString(R.string.complex_move_default_x), false);
        EditText yEquation = addField(content, getString(R.string.complex_move_y_equation),
                getString(R.string.complex_move_default_y), false);
        addHint(content, getString(R.string.complex_move_equation_help));
        EditText xTimeEasing = addField(content,
                getString(R.string.complex_move_x_time_easing),
                getString(R.string.complex_move_default_easing), false);
        EditText yTimeEasing = addField(content,
                getString(R.string.complex_move_y_time_easing),
                getString(R.string.complex_move_default_easing), false);
        addHint(content, getString(R.string.complex_move_time_easing_help));
        EditText generationDensity = addField(content,
                getString(R.string.complex_move_density),
                getString(R.string.complex_move_default_density), true);

        ComplexMovePreviewView preview = new ComplexMovePreviewView(this);
        preview.setContentDescription(getString(R.string.complex_move_preview_description));
        content.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(190)));
        TextView previewStatus = addHint(content, "");

        Runnable refreshPreview = () -> {
            try {
                ComplexMoveGenerator.Result result = ComplexMoveGenerator.preview(
                        readComplexMoveSpec(startTime, endTime, xEquation, yEquation,
                                xTimeEasing, yTimeEasing, generationDensity));
                if (result.error == ComplexMoveGenerator.Error.NONE) {
                    preview.setPath(result.path);
                    previewStatus.setText(getResources().getQuantityString(
                            R.plurals.complex_move_preview_ready, result.segmentCount,
                            result.segmentCount));
                } else {
                    preview.setPath(null);
                    previewStatus.setText(complexMoveErrorMessage(result.error, result.detail));
                }
            } catch (IllegalArgumentException exception) {
                preview.setPath(null);
                previewStatus.setText(R.string.complex_move_preview_invalid);
            }
        };
        watch(refreshPreview, startTime, endTime, xEquation, yEquation,
                xTimeEasing, yTimeEasing, generationDensity);
        refreshPreview.run();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.complex_move_title)
                .setView(scrollView)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_generate, null)
                .create();
        showEditorWindow(dialog, () -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        ComplexMoveGenerator.Result result = editorView.applyComplexMove(
                                readComplexMoveSpec(startTime, endTime, xEquation, yEquation,
                                        xTimeEasing, yTimeEasing, generationDensity));
                        if (result.error != ComplexMoveGenerator.Error.NONE) {
                            showMessage(complexMoveErrorMessage(result.error, result.detail));
                            return;
                        }
                        dialog.dismiss();
                        showMessage(getResources().getQuantityString(
                                R.plurals.complex_move_generated, result.segmentCount,
                                result.segmentCount));
                    } catch (IllegalArgumentException exception) {
                        showMessage(getString(R.string.complex_move_preview_invalid));
                    }
                }));
    }

    private ComplexMoveGenerator.Spec readComplexMoveSpec(
            EditText startTime, EditText endTime,
            EditText xEquation, EditText yEquation,
            EditText xTimeEasing, EditText yTimeEasing,
            EditText generationDensity) {
        ComplexMoveGenerator.Spec spec = new ComplexMoveGenerator.Spec();
        spec.startTime = BeatTime.parseFlexible(startTime.getText().toString());
        spec.endTime = BeatTime.parseFlexible(endTime.getText().toString());
        spec.xExpression = xEquation.getText().toString();
        spec.yExpression = yEquation.getText().toString();
        spec.xTimeEasing = ComplexMoveGenerator.TimeEasing.parse(
                xTimeEasing.getText().toString());
        spec.yTimeEasing = ComplexMoveGenerator.TimeEasing.parse(
                yTimeEasing.getText().toString());
        spec.density = parseDouble(generationDensity);
        return spec;
    }

    private String complexMoveErrorMessage(ComplexMoveGenerator.Error error, String detail) {
        switch (error) {
            case INVALID_TIME: return getString(R.string.complex_move_error_time);
            case INVALID_DENSITY: return getString(R.string.complex_move_error_density);
            case TOO_MANY_SEGMENTS: return getString(R.string.complex_move_error_too_many);
            case INVALID_EASING: return getString(R.string.complex_move_error_easing);
            case INVALID_EXPRESSION: return getString(R.string.complex_move_error_expression,
                    nonEmpty(detail, getString(R.string.complex_move_preview_invalid)));
            case NON_FINITE_RESULT: return getString(R.string.complex_move_error_result);
            case X_OUT_OF_RANGE: return getString(R.string.complex_move_error_x_range);
            case Y_OUT_OF_RANGE: return getString(R.string.complex_move_error_y_range);
            case RESERVED_LAYER: return getString(R.string.complex_move_error_reserved);
            case EVENT_OVERLAP: return getString(R.string.complex_move_error_overlap);
            default: return getString(R.string.complex_move_preview_invalid);
        }
    }

    private void showCurveNotes() {
        ChartDocument chart = editorView.getChart();
        List<Note> notes = editorView.getCurrentLineNotes();
        if (chart == null || notes.size() < 2) {
            showMessage(getString(R.string.curve_notes_need_two));
            return;
        }
        JudgeLine line = chart.judgeLines.get(editorView.getLineIndex());
        List<Note> selected = editorView.getSelectedNotesForCurve();
        Note defaultStart = selected.size() >= 2 ? selected.get(0) : notes.get(0);
        Note defaultEnd = selected.size() >= 2
                ? selected.get(selected.size() - 1) : notes.get(notes.size() - 1);
        int startIndex = Math.max(0, notes.indexOf(defaultStart));
        int endIndex = Math.max(0, notes.indexOf(defaultEnd));
        if (startIndex == endIndex) endIndex = startIndex == notes.size() - 1 ? 0 : startIndex + 1;

        List<String> endpointLabels = new ArrayList<>();
        for (int index = 0; index < notes.size(); index++) {
            Note note = notes.get(index);
            endpointLabels.add(getString(R.string.curve_notes_endpoint, index + 1,
                    noteTypeLabel(note.type), note.startTime.toString(),
                    formatNumber(note.positionX)));
        }

        ScrollView scrollView = new ScrollView(this);
        LinearLayout content = createDialogContent(scrollView);
        addHint(content, getString(R.string.curve_notes_help));
        Spinner startNote = addStringSpinner(content,
                getString(R.string.curve_notes_start), endpointLabels, startIndex);
        Spinner endNote = addStringSpinner(content,
                getString(R.string.curve_notes_end), endpointLabels, endIndex);
        EditText curveDensity = addField(content,
                getString(R.string.curve_notes_density),
                getString(R.string.curve_notes_default_density), true);
        addHint(content, getString(R.string.curve_notes_density_help,
                editorView.getSubdivision()));
        Spinner noteType = addSpinner(content, R.string.curve_notes_type,
                R.array.curve_note_types, 1);
        Spinner easing = addSpinner(content, R.string.curve_notes_easing,
                R.array.event_easing_entries, 0);
        CurveNotePreviewView preview = new CurveNotePreviewView(this);
        preview.setContentDescription(getString(R.string.curve_notes_preview_description));
        content.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(190)));
        TextView previewStatus = addHint(content, "");

        Runnable refreshPreview = () -> {
            try {
                CurveNoteGenerator.Result result = CurveNoteGenerator.generate(
                        line, notes.get(startNote.getSelectedItemPosition()),
                        notes.get(endNote.getSelectedItemPosition()),
                        parseDouble(curveDensity), editorView.getSubdivision(),
                        curveNoteType(noteType.getSelectedItemPosition()),
                        easing.getSelectedItemPosition() + Easing.MIN_TYPE);
                if (result.error == CurveNoteGenerator.Error.NONE) {
                    preview.setPath(result.path);
                    previewStatus.setText(getResources().getQuantityString(
                            R.plurals.curve_notes_preview_ready, result.notes.size(),
                            result.notes.size()));
                } else {
                    preview.setPath(null);
                    previewStatus.setText(curveNotesErrorMessage(result.error));
                }
            } catch (IllegalArgumentException exception) {
                preview.setPath(null);
                previewStatus.setText(R.string.curve_notes_preview_invalid);
            }
        };
        setSpinnerChangeListener(startNote, refreshPreview);
        setSpinnerChangeListener(endNote, refreshPreview);
        setSpinnerChangeListener(noteType, refreshPreview);
        setSpinnerChangeListener(easing, refreshPreview);
        watch(refreshPreview, curveDensity);
        refreshPreview.run();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.curve_notes_title)
                .setView(scrollView)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_generate, null)
                .create();
        showEditorWindow(dialog, () -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        CurveNoteGenerator.Result result = editorView.applyCurveNotes(
                                notes.get(startNote.getSelectedItemPosition()),
                                notes.get(endNote.getSelectedItemPosition()),
                                parseDouble(curveDensity),
                                curveNoteType(noteType.getSelectedItemPosition()),
                                easing.getSelectedItemPosition() + Easing.MIN_TYPE);
                        if (result.error != CurveNoteGenerator.Error.NONE) {
                            showMessage(curveNotesErrorMessage(result.error));
                            return;
                        }
                        dialog.dismiss();
                        showMessage(getResources().getQuantityString(
                                R.plurals.curve_notes_generated, result.notes.size(),
                                result.notes.size()));
                    } catch (IllegalArgumentException exception) {
                        showMessage(getString(R.string.curve_notes_preview_invalid));
                    }
                }));
    }

    private String curveNotesErrorMessage(CurveNoteGenerator.Error error) {
        switch (error) {
            case TARGET_NOT_FOUND: return getString(R.string.curve_notes_error_target);
            case SAME_NOTE: return getString(R.string.curve_notes_error_same);
            case INVALID_TIME_ORDER: return getString(R.string.curve_notes_error_order);
            case INVALID_DENSITY:
            case INVALID_SUBDIVISION: return getString(R.string.curve_notes_error_density);
            case INVALID_NOTE_TYPE: return getString(R.string.curve_notes_error_type);
            case INVALID_EASING: return getString(R.string.curve_notes_error_easing);
            case TOO_MANY_NOTES: return getString(R.string.curve_notes_error_too_many);
            case NO_INTERMEDIATE_NOTES: return getString(R.string.curve_notes_error_empty);
            case X_OUT_OF_RANGE: return getString(R.string.curve_notes_error_x_range);
            default: return getString(R.string.curve_notes_preview_invalid);
        }
    }

    private String noteTypeLabel(NoteType type) {
        switch (type) {
            case TAP: return getString(R.string.controls_tap);
            case DRAG: return getString(R.string.controls_drag);
            case FLICK: return getString(R.string.controls_flick);
            case HOLD: return getString(R.string.controls_hold);
            default: return type.name();
        }
    }

    private static NoteType curveNoteType(int position) {
        switch (position) {
            case 1: return NoteType.DRAG;
            case 2: return NoteType.FLICK;
            default: return NoteType.TAP;
        }
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
                            () -> projectLibrary.removeProject(projectId), state -> {
                        if (active) {
                            currentPackage = null;
                            currentPackageSourceUri = null;
                            currentProjectId = null;
                            currentProjectName = null;
                            releaseAudio();
                            loadFallbackChart();
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
        ChartDocument chart = editorView.getChart();
        if (chart == null) return;
        Double maximumBeat = null;
        long duration = audioDurationMs();
        if (duration > 0L) {
            maximumBeat = chart.audioMillisToBeat(duration, editorView.getPackageOffsetMs());
        }
        ChartDiagnostics.Report report = ChartDiagnostics.analyze(chart, maximumBeat);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        TextView summary = new TextView(this);
        summary.setText(getString(R.string.diagnostics_summary, report.errorCount,
                report.warningCount, report.cautionCount, report.items.size(),
                report.totalCount));
        summary.setTextSize(14f);
        summary.setPadding(0, 0, 0, dp(8));
        root.addView(summary);

        List<ChartDiagnostic> diagnostics = new ArrayList<>(report.items);
        List<String> labels = new ArrayList<>(diagnostics.size());
        for (ChartDiagnostic diagnostic : diagnostics) {
            labels.add(diagnosticLabel(diagnostic));
        }
        ListView list = new ListView(this);
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.diagnostics_title)
                .setView(root)
                .setNegativeButton(R.string.bpm_list_close, null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            ChartDiagnostic diagnostic = diagnostics.get(position);
            if (editorView.navigateToDiagnostic(diagnostic)) {
                dialog.dismiss();
                showMessage(getString(R.string.diagnostics_navigated));
            }
        });
        showEditorWindow(dialog, null);
    }

    private String diagnosticLabel(ChartDiagnostic diagnostic) {
        String severity;
        switch (diagnostic.severity) {
            case ERROR: severity = getString(R.string.diagnostics_severity_error); break;
            case WARNING: severity = getString(R.string.diagnostics_severity_warning); break;
            default: severity = getString(R.string.diagnostics_severity_caution); break;
        }
        String location;
        if (diagnostic.lineIndex < 0) {
            location = getString(R.string.diagnostics_location_global, diagnostic.beat);
        } else if (diagnostic.layerIndex < 0) {
            location = getString(R.string.diagnostics_location_note,
                    diagnostic.lineIndex, diagnostic.beat);
        } else {
            location = getString(R.string.diagnostics_location_event,
                    diagnostic.lineIndex, diagnostic.layerIndex, diagnostic.beat);
        }
        return severity + " · " + location + "\n" + diagnosticMessage(diagnostic);
    }

    private String diagnosticMessage(ChartDiagnostic diagnostic) {
        switch (diagnostic.code) {
            case INVALID_BPM:
                return getString(R.string.diagnostic_invalid_bpm);
            case NEGATIVE_BPM_START:
                return getString(R.string.diagnostic_negative_bpm_start);
            case DUPLICATE_BPM_START:
                return getString(R.string.diagnostic_duplicate_bpm_start);
            case NOTE_TIME_OUT_OF_RANGE:
                return getString(R.string.diagnostic_note_time_out_of_range);
            case HOLD_INTERVAL_INVALID:
                return getString(R.string.diagnostic_hold_interval_invalid);
            case NOTE_X_TOO_LARGE:
                return getString(R.string.diagnostic_note_x_too_large,
                        diagnostic.note == null ? 0.0 : diagnostic.note.positionX);
            case FAKE_NOTE:
                return getString(R.string.diagnostic_fake_note);
            case CUSTOM_NOTE_SIZE:
                return getString(R.string.diagnostic_custom_note_size,
                        diagnostic.note == null ? 1.0 : diagnostic.note.size);
            case CUSTOM_VISIBLE_TIME:
                return getString(R.string.diagnostic_custom_visible_time,
                        diagnostic.note == null ? 999999.0 : diagnostic.note.visibleTime);
            case EVENT_TIME_OUT_OF_RANGE:
                return getString(R.string.diagnostic_event_time_out_of_range);
            case EVENT_INTERVAL_INVALID:
                return getString(R.string.diagnostic_event_interval_invalid);
            case EVENT_OVERLAP:
                return getString(R.string.diagnostic_event_overlap,
                        diagnostic.event == null ? "Event"
                                : eventTypeLabel(diagnostic.event.type));
            case ALPHA_OUT_OF_RANGE:
                return getString(R.string.diagnostic_alpha_out_of_range);
            case RESERVED_LAYER_NORMAL_EVENT:
                return getString(R.string.diagnostic_reserved_layer_event);
            default:
                return diagnostic.code.name();
        }
    }

    private void showLineManager() {
        ChartDocument chart = editorView.getChart();
        if (chart == null) return;

        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(pad, pad, pad, pad);

        ScrollView listScroll = new ScrollView(this);
        listScroll.setFillViewport(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(list);
        root.addView(listScroll, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1.08f));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(14), 0, 0, 0);
        root.addView(details, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));

        TextView selectedTitle = new TextView(this);
        selectedTitle.setTextSize(18f);
        selectedTitle.setPadding(0, 0, 0, dp(6));
        details.addView(selectedTitle);
        TextView summary = new TextView(this);
        summary.setTextSize(15f);
        summary.setPadding(0, 0, 0, dp(8));
        details.addView(summary);
        TextView propertiesSummary = new TextView(this);
        propertiesSummary.setTextSize(13f);
        details.addView(propertiesSummary);
        View spacer = new View(this);
        details.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        LinearLayout firstActions = new LinearLayout(this);
        firstActions.setOrientation(LinearLayout.HORIZONTAL);
        Button add = addActionButton(firstActions, R.string.line_manager_add);
        Button properties = addActionButton(firstActions, R.string.properties_button);
        details.addView(firstActions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout secondActions = new LinearLayout(this);
        secondActions.setOrientation(LinearLayout.HORIZONTAL);
        Button delete = addActionButton(secondActions, R.string.line_manager_delete);
        Button close = addActionButton(secondActions, R.string.bpm_list_close);
        details.addView(secondActions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        int[] selectedIndex = new int[]{editorView.getLineIndex()};
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            selectedIndex[0] = Math.max(0,
                    Math.min(selectedIndex[0], chart.judgeLines.size() - 1));
            list.removeAllViews();
            for (int index = 0; index < chart.judgeLines.size(); index++) {
                JudgeLine line = chart.judgeLines.get(index);
                boolean active = index == selectedIndex[0];
                Button row = new Button(this);
                row.setAllCaps(false);
                row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                row.setMinHeight(dp(48));
                row.setAlpha(active ? 1.0f : 0.78f);
                row.setText(getString(R.string.line_manager_row, index, line.name,
                        active ? getString(R.string.line_manager_active_suffix) : ""));
                int nextIndex = index;
                row.setOnClickListener(view -> {
                    selectedIndex[0] = nextIndex;
                    editorView.selectLine(nextIndex);
                    refresh[0].run();
                });
                list.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            JudgeLine selected = chart.judgeLines.get(selectedIndex[0]);
            selectedTitle.setText(getString(R.string.line_manager_row, selectedIndex[0],
                    selected.name, getString(R.string.line_manager_active_suffix)));
            summary.setText(getString(R.string.line_manager_summary,
                    selected.notes.size(), selected.countEvents()));
            propertiesSummary.setText(getString(R.string.line_manager_details,
                    selected.texture, selected.group, formatNumber(selected.bpmFactor),
                    selected.zOrder, getString(selected.cover
                            ? R.string.line_manager_cover_on : R.string.line_manager_cover_off)));
            delete.setEnabled(selectedIndex[0] != 0);
        };

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.line_manager_title)
                .setView(root)
                .create();
        add.setOnClickListener(view -> {
            editorView.addJudgeLine();
            selectedIndex[0] = editorView.getLineIndex();
            refresh[0].run();
        });
        properties.setOnClickListener(view -> {
            JudgeLine target = chart.judgeLines.get(selectedIndex[0]);
            dialog.dismiss();
            showLineProperties(target);
        });
        delete.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(R.string.line_delete_title)
                .setMessage(getString(R.string.line_delete_message, selectedIndex[0],
                        chart.judgeLines.get(selectedIndex[0]).name))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.line_manager_delete, (ignored, which) -> {
                    EditorView.LineApplyResult result = editorView.deleteActiveLine();
                    if (result != EditorView.LineApplyResult.APPLIED) {
                        showLineApplyError(result);
                        return;
                    }
                    selectedIndex[0] = editorView.getLineIndex();
                    refresh[0].run();
                })
                .show());
        close.setOnClickListener(view -> dialog.dismiss());
        refresh[0].run();
        showEditorWindow(dialog, null);
    }

    private void showLineProperties(JudgeLine line) {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout content = createDialogContent(scrollView);
        EditText name = addField(content, getString(R.string.field_line_name), line.name, false);
        EditText texture = addField(content, getString(R.string.field_line_texture), line.texture, false);
        EditText group = addField(content, getString(R.string.field_line_group), Integer.toString(line.group), true);
        EditText bpmFactor = addField(content, getString(R.string.field_line_bpm_factor), formatNumber(line.bpmFactor), true);
        EditText zOrder = addField(content, getString(R.string.field_line_z_order), Integer.toString(line.zOrder), true);
        CheckBox cover = addCheckBox(content, R.string.field_line_cover, line.cover);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.line_properties_title)
                .setView(scrollView)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_apply, null)
                .create();
        showEditorWindow(dialog, () -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        JudgeLine edited = line.copyProperties();
                        edited.name = name.getText().toString().trim();
                        edited.texture = texture.getText().toString().trim();
                        edited.group = parseInteger(group);
                        edited.bpmFactor = parseDouble(bpmFactor);
                        edited.zOrder = parseInteger(zOrder);
                        edited.cover = cover.isChecked();
                        EditorView.LineApplyResult result = editorView.applyJudgeLineProperties(line, edited);
                        if (result != EditorView.LineApplyResult.APPLIED) {
                            showLineApplyError(result);
                            return;
                        }
                        if (currentPackage != null) loadEditorIllustration(currentPackage);
                        dialog.dismiss();
                        showMessage(getString(R.string.line_properties_applied));
                        showLineManager();
                    } catch (IllegalArgumentException exception) {
                        showMessage(getString(R.string.line_validation_invalid));
                    }
                }));
    }

    private void showLineApplyError(EditorView.LineApplyResult result) {
        int message;
        switch (result) {
            case LINE_ZERO_PROTECTED: message = R.string.line_validation_zero_protected; break;
            case LAST_LINE_REQUIRED: message = R.string.line_validation_last_required; break;
            case TARGET_NOT_FOUND: message = R.string.validation_target_changed; break;
            default: message = R.string.line_validation_invalid; break;
        }
        showMessage(getString(message));
    }

    @Override
    public void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean isAudioReady() {
        if (currentAudioIsMp3) return pcmAudioPlayer != null && audioPrepared;
        return mediaPlayer != null && audioPrepared;
    }

    @Override
    public boolean isAudioPlaying() {
        if (currentAudioIsMp3) {
            return isAudioReady() && pcmAudioPlayer.isPlaying();
        }
        try {
            return isAudioReady() && (audioStartPending || mediaPlayer.isPlaying());
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    @Override
    public void playHitSound(NoteType type) {
        SoundPool pool = hitSoundPool;
        Integer soundId = hitSoundIds.get(type);
        if (pool == null || soundId == null || soundId <= 0
                || !loadedHitSounds.contains(soundId) || editorSettings == null) return;
        float volume = (float) Math.max(0.0, Math.min(1.0,
                editorSettings.soundEffectVolume));
        if (volume <= 0f) return;
        pool.play(soundId, volume, volume, 1, 0, 1f);
    }

    @Override
    public void startAudio(long positionMs, float speed) {
        if (!isAudioReady()) return;
        if (currentAudioIsMp3) {
            pcmAudioPlayer.start(positionMs, speed);
            editorView.audioStateChanged();
            return;
        }
        try {
            int duration = mediaPlayer.getDuration();
            int target = (int) Math.max(0L, Math.min(positionMs, Math.max(0, duration - 1)));
            int generation = ++audioCommandGeneration;
            float targetSpeed = Math.max(0.25f, Math.min(2.0f, speed));
            audioStartPending = true;
            audioSeekPending = false;
            pendingAudioPositionMs = target;
            if (mediaPlayer.isPlaying()) mediaPlayer.pause();
            playbackSeekCoordinator.begin();
            mediaPlayer.setOnSeekCompleteListener(player -> {
                if (player != mediaPlayer || generation != audioCommandGeneration) return;
                try {
                    long reportedPosition = player.getCurrentPosition();
                    if (playbackSeekCoordinator.onSeekComplete(target, reportedPosition)
                            == PlaybackSeekCoordinator.Action.RETRY) {
                        // Let an OEM decoder publish its new compressed-audio position before
                        // retrying. Immediate recursive seeks can repeatedly consume the same
                        // stale compressed-audio callback and leave decoder/grid on different seeks.
                        mainHandler.postDelayed(() -> {
                            if (player != mediaPlayer
                                    || generation != audioCommandGeneration
                                    || !audioStartPending) return;
                            try {
                                player.seekTo(target, MediaPlayer.SEEK_CLOSEST);
                            } catch (IllegalArgumentException | IllegalStateException exception) {
                                failPendingAudioStart(player);
                            }
                        }, AUDIO_SEEK_RETRY_DELAY_MS);
                        return;
                    }
                    player.setOnSeekCompleteListener(null);
                    PlaybackParams params = player.getPlaybackParams();
                    params.setSpeed(targetSpeed);
                    params.setPitch(1.0f);
                    player.setPlaybackParams(params);
                    playbackPositionTracker.startAfterSeek(
                            target, targetSpeed, System.nanoTime());
                    audioStartPending = false;
                    playbackSeekCoordinator.reset();
                    editorView.audioStateChanged();
                } catch (IllegalArgumentException | IllegalStateException exception) {
                    failPendingAudioStart(player);
                }
            });
            mediaPlayer.seekTo(target, MediaPlayer.SEEK_CLOSEST);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            failPendingAudioStart(mediaPlayer);
        }
    }

    private void failPendingAudioStart(MediaPlayer player) {
        audioCommandGeneration++;
        audioStartPending = false;
        audioSeekPending = false;
        playbackSeekCoordinator.reset();
        playbackPositionTracker.reset();
        if (player != null) {
            try {
                player.setOnSeekCompleteListener(null);
                if (player.isPlaying()) player.pause();
            } catch (IllegalStateException ignored) {
                // The player is already being released or recreated.
            }
        }
        editorView.audioStateChanged();
        showMessage(getString(R.string.audio_player_not_ready));
    }

    @Override
    public void requestEditNoteProperties(Note note) {
        if (note == null) return;

        ScrollView scrollView = new ScrollView(this);
        LinearLayout content = createDialogContent(scrollView);
        Spinner type = addSpinner(content, R.string.field_note_type, R.array.note_types, note.type.ordinal());
        EditText startTime = addField(content, getString(R.string.field_start_beat), note.startTime.toString(), false);
        EditText endTime = addField(content, getString(R.string.field_end_beat_hold), note.endTime.toString(), false);
        EditText positionX = addField(content, getString(R.string.field_position_x), formatNumber(note.positionX), true);
        EditText speed = addField(content, getString(R.string.field_note_speed), formatNumber(note.speed), true);
        Spinner side = addSpinner(content, R.string.field_note_side, R.array.note_sides, note.above == 1 ? 0 : 1);
        CheckBox fake = addCheckBox(content, R.string.field_note_fake, note.fake);
        EditText size = addField(content, getString(R.string.field_note_size), formatNumber(note.size), true);
        EditText yOffset = addField(content, getString(R.string.field_note_y_offset), formatNumber(note.yOffset), true);
        EditText visibleTime = addField(content, getString(R.string.field_note_visible_time), formatNumber(note.visibleTime), true);
        EditText alpha = addField(content, getString(R.string.field_note_alpha), Integer.toString(note.alpha), true);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.note_properties_title)
                .setView(scrollView)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_apply, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                Note edited = note.copy();
                edited.type = NoteType.values()[type.getSelectedItemPosition()];
                edited.startTime = BeatTime.parse(startTime.getText().toString());
                edited.endTime = edited.type == NoteType.HOLD
                        ? BeatTime.parse(endTime.getText().toString())
                        : edited.startTime;
                edited.positionX = parseDouble(positionX);
                edited.speed = parseDouble(speed);
                edited.above = side.getSelectedItemPosition() == 0 ? 1 : 0;
                edited.fake = fake.isChecked();
                edited.size = parseDouble(size);
                edited.yOffset = parseDouble(yOffset);
                edited.visibleTime = parseDouble(visibleTime);
                edited.alpha = parseInteger(alpha);

                PropertyValidator.Error validation = PropertyValidator.validate(edited);
                if (validation != PropertyValidator.Error.NONE) {
                    showValidationError(validation);
                    return;
                }
                EditorView.PropertyApplyResult result = editorView.applyNoteProperties(note, edited);
                if (result != EditorView.PropertyApplyResult.APPLIED) {
                    showApplyError(result);
                    return;
                }
                dialog.dismiss();
                showMessage(getString(R.string.note_properties_applied));
            } catch (IllegalArgumentException exception) {
                showMessage(getString(R.string.validation_invalid_number_or_beat));
            }
        }));
        dialog.show();
    }

    @Override
    public void requestEditEventProperties(LineEvent event) {
        if (event == null) return;

        ScrollView scrollView = new ScrollView(this);
        LinearLayout content = createDialogContent(scrollView);
        EditText startTime = addField(content, getString(R.string.field_start_beat), event.startTime.toString(), false);
        EditText endTime = addField(content, getString(R.string.field_end_beat), event.endTime.toString(), false);
        EditText startValue = addField(content, getString(R.string.field_event_start_value), formatNumber(event.start), true);
        EditText endValue = addField(content, getString(R.string.field_event_end_value), formatNumber(event.end), true);
        CheckBox locked = addCheckBox(content, R.string.field_event_locked, Double.compare(event.start, event.end) == 0);
        Spinner easingType = addSpinner(content, R.string.field_event_easing_type,
                R.array.event_easing_entries,
                Math.max(Easing.MIN_TYPE, Math.min(Easing.MAX_TYPE, event.easingType)) - 1);
        TextView easingHelp = new TextView(this);
        easingHelp.setText(R.string.field_event_easing_help);
        easingHelp.setTextSize(12f);
        easingHelp.setPadding(0, dp(2), 0, dp(5));
        content.addView(easingHelp);
        TextView previewLabel = new TextView(this);
        previewLabel.setText(R.string.field_event_easing_preview);
        previewLabel.setTextSize(14f);
        previewLabel.setPadding(0, dp(8), 0, dp(3));
        content.addView(previewLabel);
        EasingPreviewView easingPreview = new EasingPreviewView(this);
        easingPreview.setContentDescription(getString(R.string.field_event_easing_preview_description));
        content.addView(easingPreview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(148)));
        EditText easingLeft = addField(content, getString(R.string.field_event_easing_left), formatNumber(event.easingLeft), true);
        EditText easingRight = addField(content, getString(R.string.field_event_easing_right), formatNumber(event.easingRight), true);
        EditText linkGroup = addField(content, getString(R.string.field_event_link_group), Integer.toString(event.linkGroup), true);
        CheckBox bezier = addCheckBox(content, R.string.field_event_bezier, event.bezier);
        EditText bezierX1 = addField(content, getString(R.string.field_event_bezier_x1), formatNumber(event.bezierPoints[0]), true);
        EditText bezierY1 = addField(content, getString(R.string.field_event_bezier_y1), formatNumber(event.bezierPoints[1]), true);
        EditText bezierX2 = addField(content, getString(R.string.field_event_bezier_x2), formatNumber(event.bezierPoints[2]), true);
        EditText bezierY2 = addField(content, getString(R.string.field_event_bezier_y2), formatNumber(event.bezierPoints[3]), true);

        boolean speedEvent = event.type == EventType.SPEED;
        Runnable refreshEasingUi = () -> updateEasingEditor(
                speedEvent, easingType, easingLeft, easingRight, bezier,
                bezierX1, bezierY1, bezierX2, bezierY2, easingPreview);
        easingType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refreshEasingUi.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                refreshEasingUi.run();
            }
        });
        bezier.setOnCheckedChangeListener((button, checked) -> refreshEasingUi.run());
        TextWatcher easingWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                refreshEasingUi.run();
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        };
        easingLeft.addTextChangedListener(easingWatcher);
        easingRight.addTextChangedListener(easingWatcher);
        bezierX1.addTextChangedListener(easingWatcher);
        bezierY1.addTextChangedListener(easingWatcher);
        bezierX2.addTextChangedListener(easingWatcher);
        bezierY2.addTextChangedListener(easingWatcher);
        refreshEasingUi.run();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.event_properties_title, eventTypeLabel(event.type)))
                .setView(scrollView)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_apply, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                LineEvent edited = event.copy();
                edited.startTime = BeatTime.parse(startTime.getText().toString());
                edited.endTime = BeatTime.parse(endTime.getText().toString());
                edited.start = parseDouble(startValue);
                edited.end = locked.isChecked() ? edited.start : parseDouble(endValue);
                edited.linkGroup = parseInteger(linkGroup);
                if (speedEvent) {
                    edited.easingType = 1;
                    edited.easingLeft = 0.0;
                    edited.easingRight = 1.0;
                    edited.bezier = false;
                } else {
                    edited.easingType = easingType.getSelectedItemPosition() + Easing.MIN_TYPE;
                    edited.easingLeft = parseDouble(easingLeft);
                    edited.easingRight = parseDouble(easingRight);
                    edited.bezier = bezier.isChecked();
                    edited.bezierPoints[0] = parseDouble(bezierX1);
                    edited.bezierPoints[1] = parseDouble(bezierY1);
                    edited.bezierPoints[2] = parseDouble(bezierX2);
                    edited.bezierPoints[3] = parseDouble(bezierY2);
                }

                PropertyValidator.Error validation = PropertyValidator.validate(edited);
                if (validation != PropertyValidator.Error.NONE) {
                    showValidationError(validation);
                    return;
                }
                EditorView.PropertyApplyResult result = editorView.applyEventProperties(event, edited);
                if (result != EditorView.PropertyApplyResult.APPLIED) {
                    showApplyError(result);
                    return;
                }
                dialog.dismiss();
                showMessage(getString(R.string.event_properties_applied));
            } catch (IllegalArgumentException exception) {
                showMessage(getString(R.string.validation_invalid_number_or_beat));
            }
        }));
        dialog.show();
    }

    private void updateEasingEditor(boolean speedEvent, Spinner easingType,
                                    EditText easingLeft, EditText easingRight,
                                    CheckBox bezier, EditText bezierX1, EditText bezierY1,
                                    EditText bezierX2, EditText bezierY2,
                                    EasingPreviewView preview) {
        boolean customBezier = !speedEvent && bezier.isChecked();
        easingType.setEnabled(!speedEvent && !customBezier);
        easingLeft.setEnabled(!speedEvent);
        easingRight.setEnabled(!speedEvent);
        bezier.setEnabled(!speedEvent);
        bezierX1.setEnabled(customBezier);
        bezierY1.setEnabled(customBezier);
        bezierX2.setEnabled(customBezier);
        bezierY2.setEnabled(customBezier);

        int type = speedEvent ? Easing.MIN_TYPE
                : easingType.getSelectedItemPosition() + Easing.MIN_TYPE;
        preview.setCurve(type,
                previewNumber(easingLeft, 0.0), previewNumber(easingRight, 1.0),
                customBezier,
                previewNumber(bezierX1, 0.0), previewNumber(bezierY1, 0.0),
                previewNumber(bezierX2, 0.0), previewNumber(bezierY2, 0.0));
    }

    private static double previewNumber(EditText field, double fallback) {
        try {
            double value = Double.parseDouble(field.getText().toString().trim());
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Override
    public void pauseAudio() {
        if (!isAudioReady()) return;
        if (currentAudioIsMp3) {
            pcmAudioPlayer.pause();
            return;
        }
        try {
            audioCommandGeneration++;
            audioStartPending = false;
            audioSeekPending = false;
            playbackSeekCoordinator.reset();
            playbackPositionTracker.reset();
            mediaPlayer.setOnSeekCompleteListener(null);
            if (mediaPlayer.isPlaying()) mediaPlayer.pause();
        } catch (IllegalStateException ignored) {
            // Player will be recreated after another audio import.
        }
    }

    @Override
    public void seekAudio(long positionMs) {
        if (!isAudioReady()) return;
        if (currentAudioIsMp3) {
            pcmAudioPlayer.seek(positionMs);
            editorView.audioStateChanged();
            return;
        }
        try {
            int duration = mediaPlayer.getDuration();
            int target = (int) Math.max(0L, Math.min(positionMs, Math.max(0, duration - 1)));
            int generation = ++audioCommandGeneration;
            audioStartPending = false;
            audioSeekPending = true;
            pendingAudioPositionMs = target;
            playbackSeekCoordinator.reset();
            playbackPositionTracker.reset();
            if (mediaPlayer.isPlaying()) mediaPlayer.pause();
            mediaPlayer.setOnSeekCompleteListener(player -> {
                if (player != mediaPlayer || generation != audioCommandGeneration) return;
                try {
                    player.setOnSeekCompleteListener(null);
                } catch (IllegalStateException ignored) {
                    // The player is already being recreated.
                }
                audioSeekPending = false;
                pendingAudioPositionMs = target;
                editorView.audioStateChanged();
            });
            mediaPlayer.seekTo(target, MediaPlayer.SEEK_CLOSEST);
            editorView.audioStateChanged();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            audioCommandGeneration++;
            audioSeekPending = false;
            playbackPositionTracker.reset();
            try {
                if (mediaPlayer != null) mediaPlayer.setOnSeekCompleteListener(null);
            } catch (IllegalStateException ignored) {
                // The player is already being recreated.
            }
            editorView.audioStateChanged();
            showMessage(getString(R.string.audio_player_not_ready));
        }
    }

    @Override
    public long audioPositionMs() {
        if (!isAudioReady()) return 0L;
        if (currentAudioIsMp3) return pcmAudioPlayer.positionMillis();
        try {
            long fallback = mediaPlayer.getCurrentPosition();
            MediaTimestamp timestamp = mediaPlayer.getTimestamp();
            long timestampMediaUs = timestamp == null ? -1L : timestamp.getAnchorMediaTimeUs();
            long timestampSystemNs = timestamp == null ? -1L
                    : android.os.Build.VERSION.SDK_INT >= 29
                    ? timestamp.getAnchorSystemNanoTime()
                    : timestamp.getAnchorSytemNanoTime();
            float timestampRate = timestamp == null ? -1f : timestamp.getMediaClockRate();
            long nowSystemNs = System.nanoTime();
            if (audioStartPending || audioSeekPending) return pendingAudioPositionMs;
            long position = playbackPositionTracker.positionMillis(
                    timestampMediaUs, timestampSystemNs, timestampRate,
                    nowSystemNs, fallback);
            return Math.min(position, Math.max(0, mediaPlayer.getDuration()));
        } catch (IllegalStateException ignored) {
            return audioStartPending || audioSeekPending ? pendingAudioPositionMs : 0L;
        }
    }

    @Override
    public long audioDurationMs() {
        if (!isAudioReady()) return 0L;
        if (currentAudioIsMp3) return pcmAudioPlayer.durationMillis();
        try {
            return mediaPlayer.getDuration();
        } catch (IllegalStateException ignored) {
            return 0L;
        }
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
        if (editorView != null) {
            editorView.setBackgroundIllustration(null);
            editorView.setPreviewLineTextures(null);
        }
        releaseHitSounds();
        releaseAudio();
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
        if (editorSettings == null) return;
        float volume = (float) editorSettings.musicVolume;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.setVolume(volume, volume);
            } catch (IllegalStateException ignored) {
                // The player may be transitioning between sources.
            }
        }
        if (pcmAudioPlayer != null) pcmAudioPlayer.setVolume(volume);
    }

    private void initializeHitSounds() {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        SoundPool pool = new SoundPool.Builder()
                .setMaxStreams(64)
                .setAudioAttributes(attributes)
                .build();
        pool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
            if (soundPool == hitSoundPool && status == 0) {
                loadedHitSounds.add(sampleId);
            }
        });
        hitSoundPool = pool;
        hitSoundIds.put(NoteType.TAP, pool.load(this, R.raw.hitsound_click, 1));
        hitSoundIds.put(NoteType.HOLD, pool.load(this, R.raw.hitsound_hold, 1));
        hitSoundIds.put(NoteType.FLICK, pool.load(this, R.raw.hitsound_flick, 1));
        hitSoundIds.put(NoteType.DRAG, pool.load(this, R.raw.hitsound_drag, 1));
    }

    private void releaseHitSounds() {
        SoundPool pool = hitSoundPool;
        hitSoundPool = null;
        hitSoundIds.clear();
        loadedHitSounds.clear();
        if (pool != null) pool.release();
    }

    private void loadInitialChart() {
        try {
            ProjectLibrary.State state = projectLibrary.load();
            String currentId = state.getCurrentProjectId();
            if (currentId != null) {
                ProjectLibrary.OpenResult opened = projectLibrary.openProject(
                        currentId, System.currentTimeMillis());
                activatePackage(opened.getChartPackage(), currentId, null,
                        opened.getRecord().getName());
                return;
            }
        } catch (Exception exception) {
            showMessage(getString(R.string.project_library_error, safeMessage(exception)));
        } catch (OutOfMemoryError error) {
            currentPackage = null;
            currentProjectId = null;
            currentProjectName = null;
            showMessage(getString(R.string.project_memory_error));
        }
        loadFallbackChart();
    }

    private void loadFallbackChart() {
        clearEditorIllustration();
        try {
            editorView.setChart(ChartIo.readAutosave(this));
            editorView.setProjectName(getString(R.string.project_autosave));
            showMessage("Autosave restored");
        } catch (Exception noAutosave) {
            try {
                editorView.setChart(ChartIo.readAsset(this, "demo_chart.json"));
                editorView.setProjectName(getString(R.string.project_demo));
            } catch (Exception exception) {
                showMessage("Unable to load demo: " + safeMessage(exception));
            }
        }
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
        try {
            projectExecutor.execute(() -> ChartEvaluator.prepare(
                    chartPackage.getChart(), chartPackage.isUseRpe170Speed()));
        } catch (RuntimeException ignored) {
            // Preview can still prepare lazily if the activity is shutting down.
        }
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
            projectExecutor.execute(() -> {
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
        releaseAudio();
        audioUri = uri;
        currentAudioIsMp3 = isMp3Audio(uri);
        audioPrepared = false;
        if (currentAudioIsMp3) {
            decodeMp3Audio(uri);
        } else {
            prepareMediaPlayerAudio(uri);
        }
        if (remember) {
            getPreferences(MODE_PRIVATE).edit().putString("audio_uri", uri.toString()).apply();
        }
    }

    private void prepareMediaPlayerAudio(Uri uri) throws IOException {
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build());
        mediaPlayer.setDataSource(this, uri);
        mediaPlayer.setOnPreparedListener(player -> {
            audioPrepared = true;
            applyAudioVolume();
            editorView.audioStateChanged();
            showMessage("Audio loaded");
        });
        mediaPlayer.setOnCompletionListener(player -> {
            audioCommandGeneration++;
            audioStartPending = false;
            audioSeekPending = false;
            playbackSeekCoordinator.reset();
            playbackPositionTracker.reset();
            editorView.audioCompleted();
        });
        mediaPlayer.setOnErrorListener((player, what, extra) -> {
            audioCommandGeneration++;
            audioStartPending = false;
            audioSeekPending = false;
            playbackSeekCoordinator.reset();
            playbackPositionTracker.reset();
            audioPrepared = false;
            editorView.audioStateChanged();
            showMessage("Audio decoder error");
            return true;
        });
        mediaPlayer.prepareAsync();
    }

    private void decodeMp3Audio(Uri uri) throws IOException {
        int generation = ++audioLoadGeneration;
        File output = new File(getCacheDir(), "xpe-mp3-" + generation + ".pcm");
        decodedMp3PcmFile = output;
        showMessage("Decoding MP3 audio…");
        try {
            audioDecodeTask = projectExecutor.submit(() -> {
                PcmAudioAsset asset;
                try {
                    asset = Mp3PcmDecoder.decode(this, uri, output);
                } catch (Exception exception) {
                    mainHandler.post(() -> {
                        if (generation != audioLoadGeneration || isFinishing() || isDestroyed()) {
                            return;
                        }
                        audioDecodeTask = null;
                        audioPrepared = false;
                        editorView.audioStateChanged();
                        showMessage("MP3 decoder error: " + safeMessage(exception));
                    });
                    return;
                }
                mainHandler.post(() -> {
                    if (generation != audioLoadGeneration || isFinishing() || isDestroyed()) {
                        asset.file.delete();
                        return;
                    }
                    audioDecodeTask = null;
                    decodedMp3PcmFile = asset.file;
                    pcmAudioPlayer = new PcmAudioPlayer(asset, mainHandler,
                            new PcmAudioPlayer.Listener() {
                                @Override
                                public void onCompletion() {
                                    if (generation == audioLoadGeneration && currentAudioIsMp3) {
                                        editorView.audioCompleted();
                                    }
                                }

                                @Override
                                public void onError(String message) {
                                    if (generation != audioLoadGeneration || !currentAudioIsMp3) {
                                        return;
                                    }
                                    editorView.audioCompleted();
                                    showMessage("PCM playback error: " + message);
                                }
                            });
                    applyAudioVolume();
                    audioPrepared = true;
                    editorView.audioStateChanged();
                    showMessage("MP3 decoded and loaded");
                });
            });
        } catch (RuntimeException exception) {
            output.delete();
            decodedMp3PcmFile = null;
            throw new IOException("Unable to start MP3 decoder", exception);
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
        audioLoadGeneration++;
        if (audioDecodeTask != null) {
            audioDecodeTask.cancel(true);
            audioDecodeTask = null;
        }
        if (pcmAudioPlayer != null) {
            pcmAudioPlayer.release();
            pcmAudioPlayer = null;
        }
        if (decodedMp3PcmFile != null) {
            decodedMp3PcmFile.delete();
            decodedMp3PcmFile = null;
        }
        audioCommandGeneration++;
        audioStartPending = false;
        audioSeekPending = false;
        playbackSeekCoordinator.reset();
        pendingAudioPositionMs = 0L;
        playbackPositionTracker.reset();
        audioPrepared = false;
        currentAudioIsMp3 = false;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.reset();
            } catch (IllegalStateException ignored) {
                // Ignore and release below.
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private boolean isMp3Audio(Uri uri) {
        String displayName = AndroidPackageIo.displayName(getContentResolver(), uri);
        String mimeType = null;
        try {
            mimeType = getContentResolver().getType(uri);
        } catch (RuntimeException ignored) {
            // File URIs and some document providers expose only the display name.
        }
        return AudioSourceFormat.isMp3(displayName, mimeType)
                || AudioSourceFormat.isMp3(uri == null ? null : uri.toString(), mimeType);
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

    private TextView addHint(LinearLayout parent, String text) {
        TextView hint = new TextView(this);
        hint.setText(text);
        hint.setTextSize(12f);
        hint.setPadding(0, dp(4), 0, dp(5));
        parent.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return hint;
    }

    private Spinner addStringSpinner(LinearLayout parent, String label,
                                     List<String> entries, int selected) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(14f);
        title.setPadding(0, dp(8), 0, dp(3));
        parent.addView(title);

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, entries);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(selected, adapter.getCount() - 1)));
        parent.addView(spinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return spinner;
    }

    private static void setSpinnerChangeListener(Spinner spinner, Runnable action) {
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                action.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                action.run();
            }
        });
    }

    private static void watch(Runnable action, EditText... fields) {
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                action.run();
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        };
        for (EditText field : fields) field.addTextChangedListener(watcher);
    }

    private Spinner addSpinner(LinearLayout parent, int labelResource, int entriesResource, int selected) {
        TextView title = new TextView(this);
        title.setText(labelResource);
        title.setTextSize(14f);
        title.setPadding(0, dp(8), 0, dp(3));
        parent.addView(title);

        Spinner spinner = new Spinner(this);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, entriesResource, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(selected, adapter.getCount() - 1)));
        parent.addView(spinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return spinner;
    }

    private CheckBox addCheckBox(LinearLayout parent, int textResource, boolean checked) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(textResource);
        checkBox.setChecked(checked);
        checkBox.setPadding(0, dp(6), 0, dp(2));
        parent.addView(checkBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return checkBox;
    }

    private Button addActionButton(LinearLayout parent, int textResource) {
        Button button = new Button(this);
        button.setText(textResource);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        parent.addView(button, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        return button;
    }

    private void setCompactButtons(LinearLayout row, Button... buttons) {
        int windowWidth = Math.round(
                getResources().getDisplayMetrics().widthPixels * 0.84f);
        int sidePadding = Math.round(windowWidth * 0.15f);
        row.setPadding(sidePadding, 0, sidePadding, 0);
        for (Button button : buttons) {
            if (button == null) continue;
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setMinHeight(0);
            button.setMinimumHeight(0);
            button.setTextSize(11.5f);
            button.setPadding(dp(3), 0, dp(3), 0);
            android.view.ViewGroup.LayoutParams params = button.getLayoutParams();
            if (params != null) {
                params.height = dp(34);
                button.setLayoutParams(params);
            }
        }
    }

    private void showBpmApplyError(EditorView.BpmApplyResult result) {
        int message;
        switch (result) {
            case DUPLICATE_START_TIME: message = R.string.bpm_validation_duplicate; break;
            case FIRST_ENTRY_LOCKED: message = R.string.bpm_validation_first_locked; break;
            case LAST_ENTRY_REQUIRED: message = R.string.bpm_validation_last_required; break;
            case TARGET_NOT_FOUND: message = R.string.validation_target_changed; break;
            default: message = R.string.bpm_validation_invalid; break;
        }
        showMessage(getString(message));
    }

    private static String compactBpm(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.000001) return Long.toString(Math.round(value));
        return String.format(Locale.US, "%.6f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private void showValidationError(PropertyValidator.Error error) {
        int message;
        switch (error) {
            case MISSING_TYPE: message = R.string.validation_missing_type; break;
            case NON_FINITE_NUMBER: message = R.string.validation_non_finite; break;
            case NEGATIVE_START_TIME: message = R.string.validation_negative_start; break;
            case END_TIME_NOT_AFTER_START: message = R.string.validation_end_after_start; break;
            case NOTE_X_OUT_OF_RANGE: message = R.string.validation_note_x; break;
            case NOTE_ALPHA_OUT_OF_RANGE: message = R.string.validation_note_alpha; break;
            case NOTE_SIZE_NOT_POSITIVE: message = R.string.validation_note_size; break;
            case NOTE_VISIBLE_TIME_NEGATIVE: message = R.string.validation_note_visible_time; break;
            case EVENT_ALPHA_OUT_OF_RANGE: message = R.string.validation_event_alpha; break;
            case EVENT_EASING_OUT_OF_RANGE: message = R.string.validation_event_easing; break;
            case EVENT_EASING_WINDOW_INVALID: message = R.string.validation_event_easing_window; break;
            case EVENT_LINK_GROUP_NEGATIVE: message = R.string.validation_event_link_group; break;
            default: message = R.string.validation_invalid_number_or_beat; break;
        }
        showMessage(getString(message));
    }

    private void showApplyError(EditorView.PropertyApplyResult result) {
        int message = result == EditorView.PropertyApplyResult.EVENT_OVERLAP
                ? R.string.validation_event_overlap
                : result == EditorView.PropertyApplyResult.TARGET_NOT_FOUND
                ? R.string.validation_target_changed
                : result == EditorView.PropertyApplyResult.XY_BINDING_INVALID
                ? R.string.validation_xy_binding_pair
                : R.string.validation_invalid_number_or_beat;
        showMessage(getString(message));
    }

    private String eventTypeLabel(EventType type) {
        switch (type) {
            case MOVE_X: return getString(R.string.event_type_move_x);
            case MOVE_Y: return getString(R.string.event_type_move_y);
            case ROTATE: return getString(R.string.event_type_rotate);
            case ALPHA: return getString(R.string.event_type_alpha);
            case SPEED: return getString(R.string.event_type_speed);
            default: return type.name();
        }
    }

    private static double parseDouble(EditText field) {
        double value = Double.parseDouble(field.getText().toString().trim());
        if (!Double.isFinite(value)) throw new NumberFormatException("number must be finite");
        return value;
    }

    private static int parseInteger(EditText field) {
        return Integer.parseInt(field.getText().toString().trim());
    }

    private static int parseRgb(String text) {
        if (text == null) throw new NumberFormatException("RGB color is required");
        String value = text.trim();
        if (value.startsWith("#")) value = value.substring(1);
        if (!value.matches("[0-9A-Fa-f]{6}")) {
            throw new NumberFormatException("RGB color must use #RRGGBB");
        }
        return Integer.parseInt(value, 16);
    }

    private static String formatNumber(double value) {
        return Double.toString(value);
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

    private static String nonEmpty(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? exception.getClass().getSimpleName() : message;
    }
}
