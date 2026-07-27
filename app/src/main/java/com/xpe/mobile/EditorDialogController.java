package com.xpe.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.ChartDiagnostic;
import com.xpe.mobile.editor.ChartDiagnostics;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.editor.EasingPreviewView;
import com.xpe.mobile.editor.PropertyValidator;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.Easing;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class EditorDialogController extends EditorDialogSection {
    interface Host {
        EditorSettings editorSettings();

        void applyEditorSettings(EditorSettings settings);

        long packageOffsetMs();

        long audioDurationMs();

        void onLineAppearanceChanged();

        void showMessage(String message);
    }

    private final StoryboardDialogSection storyboardDialogs;
    private final EditorOperationDialogSection operationDialogs;

    EditorDialogController(Activity activity, EditorView editorView, Host host) {
        super(activity, editorView, host);
        storyboardDialogs = new StoryboardDialogSection(activity, editorView, host);
        operationDialogs = new EditorOperationDialogSection(activity, editorView, host);
    }

    public void showMetadata() {
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
        long packageOffsetMs = host.packageOffsetMs();
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

    public void showBpmList() {
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

    public void showStoryboardEditor() {
        storyboardDialogs.showStoryboardEditor();
    }

    public void showSettings() {
        EditorSettings currentSettings = host.editorSettings();
        EditorSettings draft = currentSettings == null
                ? new EditorSettings() : currentSettings.copy();
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
                    host.applyEditorSettings(draft.copy());
                    dialog.dismiss();
                    showMessage(getString(R.string.settings_saved));
                }));
    }

    public void showAdvancedBatchEdit() {
        operationDialogs.showAdvancedBatchEdit();
    }

    public void showEventClone() {
        operationDialogs.showEventClone();
    }

    public void showComplexMove() {
        operationDialogs.showComplexMove();
    }

    public void showCurveNotes() {
        operationDialogs.showCurveNotes();
    }

    public void showChartDiagnostics() {
        ChartDocument chart = editorView.getChart();
        if (chart == null) return;
        Double maximumBeat = null;
        long duration = host.audioDurationMs();
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

    public void showLineManager() {
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
                        host.onLineAppearanceChanged();
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

    public void showNoteProperties(Note note) {
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

    public void showEventProperties(LineEvent event) {
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

}
