package com.xpe.mobile;

import com.xpe.mobile.i18n.UiText;

import android.app.AlertDialog;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.xpe.mobile.EditorOperationDialogSection.TimeEasingInput;
import com.xpe.mobile.editor.ComplexMoveGenerator;
import com.xpe.mobile.editor.ComplexMovePreviewView;
import com.xpe.mobile.editor.CurveNoteGenerator;
import com.xpe.mobile.editor.CurveNotePreviewView;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.Easing;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import java.util.ArrayList;
import java.util.List;

/** GeneratedChartDialogs responsibilities extracted from EditorOperationDialogSection. */
final class GeneratedChartDialogs {
    private final EditorOperationDialogSection delegateOwner;

    GeneratedChartDialogs(EditorOperationDialogSection delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void showComplexMove() {
        ChartDocument chart = delegateOwner.editorView.getChart();
        if (chart == null) return;
        BeatTime startBeat = delegateOwner.editorView.getCurrentBeatTime();
        if (startBeat.compareTo(new BeatTime(1, 0, 1)) < 0) {
            startBeat = new BeatTime(1, 0, 1);
        }
        BeatTime endBeat = startBeat.plus(new BeatTime(2, 0, 1));

        ScrollView scrollView = new ScrollView(delegateOwner);
        LinearLayout content = delegateOwner.createDialogContent(scrollView);
        delegateOwner.addHint(content, () -> delegateOwner.getString(R.string.complex_move_scope,
                delegateOwner.editorView.getLineIndex(), delegateOwner.editorView.getEventLayerIndex()));
        delegateOwner.addHint(content, R.string.complex_move_time_help);
        EditText startTime = delegateOwner.addField(content, R.string.complex_move_start_time,
                startBeat.toString(), false);
        EditText endTime = delegateOwner.addField(content, R.string.complex_move_end_time,
                endBeat.toString(), false);
        EditText xEquation = delegateOwner.addField(content, R.string.complex_move_x_equation,
                delegateOwner.getString(R.string.complex_move_default_x), false);
        EditText yEquation = delegateOwner.addField(content, R.string.complex_move_y_equation,
                delegateOwner.getString(R.string.complex_move_default_y), false);
        delegateOwner.addHint(content, R.string.complex_move_equation_help);
        EasingSelector xCurve = EasingSelector.addTo(content, R.string.complex_move_x_time_easing, 1);
        EasingSelector yCurve = EasingSelector.addTo(content, R.string.complex_move_y_time_easing, 1);
        Spinner xEasingType = xCurve.spinner, yEasingType = yCurve.spinner;
        delegateOwner.addHint(content, R.string.complex_move_time_easing_help);
        CheckBox easingWindows = delegateOwner.addCheckBox(content, R.string.complex_move_easing_windows, false);
        LinearLayout windowFields = new LinearLayout(delegateOwner);
        windowFields.setOrientation(LinearLayout.VERTICAL);
        content.addView(windowFields, new LinearLayout.LayoutParams(-1, -2));
        EditText xLeft = delegateOwner.addField(windowFields, () -> "X · " + delegateOwner.getString(R.string.field_event_easing_left), "0", true);
        EditText xRight = delegateOwner.addField(windowFields, () -> "X · " + delegateOwner.getString(R.string.field_event_easing_right), "1", true);
        EditText yLeft = delegateOwner.addField(windowFields, () -> "Y · " + delegateOwner.getString(R.string.field_event_easing_left), "0", true);
        EditText yRight = delegateOwner.addField(windowFields, () -> "Y · " + delegateOwner.getString(R.string.field_event_easing_right), "1", true);
        windowFields.setVisibility(View.GONE);
        TimeEasingInput xTimeEasing = new TimeEasingInput(xEasingType, easingWindows, xLeft, xRight);
        TimeEasingInput yTimeEasing = new TimeEasingInput(yEasingType, easingWindows, yLeft, yRight);
        EditText generationDensity = delegateOwner.addField(content,
                R.string.complex_move_density,
                delegateOwner.getString(R.string.complex_move_default_density), true);

        ComplexMovePreviewView preview = new ComplexMovePreviewView(delegateOwner);
        UiText.description(preview, R.string.complex_move_preview_description);
        content.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, delegateOwner.dp(190)));
        TextView previewStatus = delegateOwner.addHint(content, "");

        xCurve.bindWindow(xLeft, xRight, easingWindows::isChecked);
        yCurve.bindWindow(yLeft, yRight, easingWindows::isChecked);
        Runnable refreshPreview = () -> {
            try {
                xCurve.setWindow(easingWindows.isChecked() ? EditorDialogSection.parseDouble(xLeft) : 0,
                        easingWindows.isChecked() ? EditorDialogSection.parseDouble(xRight) : 1);
                yCurve.setWindow(easingWindows.isChecked() ? EditorDialogSection.parseDouble(yLeft) : 0,
                        easingWindows.isChecked() ? EditorDialogSection.parseDouble(yRight) : 1);
                ComplexMoveGenerator.Result result = ComplexMoveGenerator.preview(
                        delegateOwner.readComplexMoveSpec(startTime, endTime, xEquation, yEquation,
                                xTimeEasing, yTimeEasing, generationDensity));
                if (result.error == ComplexMoveGenerator.Error.NONE) {
                    preview.setPath(result.path);
                    UiText.text(previewStatus, () -> delegateOwner.getResources().getQuantityString(
                            R.plurals.complex_move_preview_ready, result.segmentCount,
                            result.segmentCount));
                } else {
                    preview.setPath(null);
                    UiText.text(previewStatus, () -> delegateOwner.complexMoveErrorMessage(result.error, result.detail));
                }
            } catch (IllegalArgumentException exception) {
                preview.setPath(null);
                UiText.text(previewStatus, R.string.complex_move_preview_invalid);
            }
        };
        EditorOperationDialogSection.watch(refreshPreview, startTime, endTime, xEquation, yEquation,
                generationDensity, xLeft, xRight, yLeft, yRight);
        xCurve.setOnChanged(refreshPreview);
        yCurve.setOnChanged(refreshPreview);
        easingWindows.setOnCheckedChangeListener((button, enabled) -> {
            windowFields.setVisibility(enabled ? View.VISIBLE : View.GONE);
            refreshPreview.run();
        });
        refreshPreview.run();

        EditorPanelWindow dialog = new EditorPanelWindow.Builder(delegateOwner, delegateOwner.host.workbench())
                .setTitle(R.string.complex_move_title)
                .setView(scrollView)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_generate, null)
                .create();
        delegateOwner.showEditorWindow(dialog, () -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        ComplexMoveGenerator.Result result = delegateOwner.editorView.applyComplexMove(
                                delegateOwner.readComplexMoveSpec(startTime, endTime, xEquation, yEquation,
                                        xTimeEasing, yTimeEasing, generationDensity));
                        if (result.error != ComplexMoveGenerator.Error.NONE) {
                            delegateOwner.showMessage(delegateOwner.complexMoveErrorMessage(result.error, result.detail));
                            return;
                        }
                        dialog.dismiss();
                        delegateOwner.showMessage(delegateOwner.getResources().getQuantityString(
                                R.plurals.complex_move_generated, result.segmentCount,
                                result.segmentCount));
                    } catch (IllegalArgumentException exception) {
                        delegateOwner.showMessage(delegateOwner.getString(R.string.complex_move_preview_invalid));
                    }
                }));
    }

    void showCurveNotes() {
        ChartDocument chart = delegateOwner.editorView.getChart();
        List<Note> notes = delegateOwner.editorView.getCurrentLineNotes();
        if (chart == null || notes.size() < 2) {
            delegateOwner.showMessage(delegateOwner.getString(R.string.curve_notes_need_two));
            return;
        }
        JudgeLine line = chart.judgeLines.get(delegateOwner.editorView.getLineIndex());
        List<Note> selected = delegateOwner.editorView.getSelectedNotesForCurve();
        Note defaultStart = selected.size() >= 2 ? selected.get(0) : notes.get(0);
        Note defaultEnd = selected.size() >= 2
                ? selected.get(selected.size() - 1) : notes.get(notes.size() - 1);
        int startIndex = Math.max(0, notes.indexOf(defaultStart));
        int endIndex = Math.max(0, notes.indexOf(defaultEnd));
        if (startIndex == endIndex) endIndex = startIndex == notes.size() - 1 ? 0 : startIndex + 1;

        java.util.function.Supplier<List<String>> endpointLabels = () -> {
            List<String> labels = new ArrayList<>();
            for (int index = 0; index < notes.size(); index++) {
                Note note = notes.get(index);
                labels.add(delegateOwner.getString(R.string.curve_notes_endpoint, index + 1,
                        delegateOwner.noteTypeLabel(note.type), note.startTime.toString(),
                        EditorOperationDialogSection.formatNumber(note.positionX)));
            }
            return labels;
        };

        ScrollView scrollView = new ScrollView(delegateOwner);
        LinearLayout content = delegateOwner.createDialogContent(scrollView);
        delegateOwner.addHint(content, R.string.curve_notes_help);
        Spinner startNote = delegateOwner.addStringSpinner(content,
                R.string.curve_notes_start, endpointLabels, startIndex);
        Spinner endNote = delegateOwner.addStringSpinner(content,
                R.string.curve_notes_end, endpointLabels, endIndex);
        EditText curveDensity = delegateOwner.addField(content,
                R.string.curve_notes_density,
                delegateOwner.getString(R.string.curve_notes_default_density), true);
        delegateOwner.addHint(content, () -> delegateOwner.getString(R.string.curve_notes_density_help,
                delegateOwner.editorView.getSubdivision()));
        Spinner noteType = delegateOwner.addSpinner(content, R.string.curve_notes_type,
                R.array.curve_note_types, 1);
        EasingSelector curve = EasingSelector.addTo(content, R.string.curve_notes_easing, 1);
        Spinner easing = curve.spinner;
        CurveNotePreviewView preview = new CurveNotePreviewView(delegateOwner);
        UiText.description(preview, R.string.curve_notes_preview_description);
        content.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, delegateOwner.dp(190)));
        TextView previewStatus = delegateOwner.addHint(content, "");

        Runnable refreshPreview = () -> {
            try {
                CurveNoteGenerator.Result result = CurveNoteGenerator.generate(
                        line, notes.get(startNote.getSelectedItemPosition()),
                        notes.get(endNote.getSelectedItemPosition()),
                        EditorOperationDialogSection.parseDouble(curveDensity), delegateOwner.editorView.getSubdivision(),
                        EditorOperationDialogSection.curveNoteType(noteType.getSelectedItemPosition()),
                        easing.getSelectedItemPosition() + Easing.MIN_TYPE);
                if (result.error == CurveNoteGenerator.Error.NONE) {
                    preview.setPath(result.path);
                    UiText.text(previewStatus, () -> delegateOwner.getResources().getQuantityString(
                            R.plurals.curve_notes_preview_ready, result.notes.size(),
                            result.notes.size()));
                } else {
                    preview.setPath(null);
                    UiText.text(previewStatus, () -> delegateOwner.curveNotesErrorMessage(result.error));
                }
            } catch (IllegalArgumentException exception) {
                preview.setPath(null);
                UiText.text(previewStatus, R.string.curve_notes_preview_invalid);
            }
        };
        EditorOperationDialogSection.setSpinnerChangeListener(startNote, refreshPreview);
        EditorOperationDialogSection.setSpinnerChangeListener(endNote, refreshPreview);
        EditorOperationDialogSection.setSpinnerChangeListener(noteType, refreshPreview);
        curve.setOnChanged(refreshPreview);
        EditorOperationDialogSection.watch(refreshPreview, curveDensity);
        refreshPreview.run();

        EditorPanelWindow dialog = new EditorPanelWindow.Builder(delegateOwner, delegateOwner.host.workbench())
                .setTitle(R.string.curve_notes_title)
                .setView(scrollView)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_generate, null)
                .create();
        delegateOwner.showEditorWindow(dialog, () -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        CurveNoteGenerator.Result result = delegateOwner.editorView.applyCurveNotes(
                                notes.get(startNote.getSelectedItemPosition()),
                                notes.get(endNote.getSelectedItemPosition()),
                                EditorOperationDialogSection.parseDouble(curveDensity),
                                EditorOperationDialogSection.curveNoteType(noteType.getSelectedItemPosition()),
                                easing.getSelectedItemPosition() + Easing.MIN_TYPE);
                        if (result.error != CurveNoteGenerator.Error.NONE) {
                            delegateOwner.showMessage(delegateOwner.curveNotesErrorMessage(result.error));
                            return;
                        }
                        dialog.dismiss();
                        delegateOwner.showMessage(delegateOwner.getResources().getQuantityString(
                                R.plurals.curve_notes_generated, result.notes.size(),
                                result.notes.size()));
                    } catch (IllegalArgumentException exception) {
                        delegateOwner.showMessage(delegateOwner.getString(R.string.curve_notes_preview_invalid));
                    }
                }));
    }
}
