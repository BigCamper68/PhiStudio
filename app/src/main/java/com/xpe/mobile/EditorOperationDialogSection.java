package com.xpe.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.xpe.mobile.editor.BatchEditOperation;
import com.xpe.mobile.editor.BatchValueTransform;
import com.xpe.mobile.editor.ComplexMoveGenerator;
import com.xpe.mobile.editor.ComplexMovePreviewView;
import com.xpe.mobile.editor.CurveNoteGenerator;
import com.xpe.mobile.editor.CurveNotePreviewView;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.editor.EventCloneOperation;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.Easing;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;

import java.util.ArrayList;
import java.util.List;

/** Batch editing, cloning and generated-movement dialog workflows. */
final class EditorOperationDialogSection extends EditorDialogSection {
    EditorOperationDialogSection(Activity activity, EditorView editorView,
                                 EditorDialogController.Host host) {
        super(activity, editorView, host);
    }

    public void showAdvancedBatchEdit() {
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

    public void showEventClone() {
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

    public void showComplexMove() {
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

    public void showCurveNotes() {
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
}
