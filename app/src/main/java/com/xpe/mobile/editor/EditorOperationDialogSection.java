package com.xpe.mobile.editor;

import android.app.Activity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import com.xpe.mobile.R;
import com.xpe.mobile.i18n.UiText;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.Easing;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.NoteType;
import java.util.ArrayList;
import java.util.List;

/** Batch editing, cloning and generated-movement dialog workflows. */
final class EditorOperationDialogSection extends EditorDialogSection {
    private final BatchEditDialogs batchEditDialogs = new BatchEditDialogs(this);
    private final GeneratedChartDialogs generatedChartDialogs = new GeneratedChartDialogs(this);

    EditorOperationDialogSection(Activity activity, EditorView editorView,
                                 EditorDialogController.Host host) {
        super(activity, editorView, host);
    }

    public void showAdvancedBatchEdit() {
        batchEditDialogs.showAdvancedBatchEdit();
    }

    public void showEventClone() {
        batchEditDialogs.showEventClone();
    }

    BatchValueTransform.Spec batchProfile(EditText lower, EditText upper,
                                                  int easingType, EditText sequence,
                                                  EditText disturbance, long seed) {
        BatchValueTransform.Spec result = new BatchValueTransform.Spec();
        result.lowerBound = parseDouble(lower);
        result.upperBound = parseDouble(upper);
        result.easingType = easingType;
        result.periodicSequence = BatchValueTransform.parseSequence(
                sequence.getText().toString());
        result.disturbance = parseDouble(disturbance);
        result.randomSeed = seed;
        if (!BatchValueTransform.isValid(result)) {
            throw new IllegalArgumentException("invalid profile");
        }
        return result;
    }

    List<String> eventTypeLabels() {
        List<String> result = new ArrayList<>();
        for (EventType type : EventType.values()) result.add(eventTypeLabel(type));
        return result;
    }

    List<String> resourceEntries(int resource) {
        String[] entries = UiText.resources(this).getStringArray(resource);
        List<String> result = new ArrayList<>(entries.length);
        java.util.Collections.addAll(result, entries);
        return result;
    }

    String batchErrorMessage(BatchEditOperation.Error error) {
        switch (error) {
            case EMPTY_SELECTION: return getString(R.string.batch_empty_selection);
            case MIXED_EVENT_TYPES: return getString(R.string.batch_mixed_events);
            case UNSUPPORTED_FIELD: return getString(R.string.batch_unsupported_field);
            case INVALID_PROFILE: return getString(R.string.batch_invalid_profile);
            case TARGET_NOT_FOUND: return getString(R.string.validation_target_changed);
            default: return getString(R.string.batch_invalid_result);
        }
    }

    String eventCloneErrorMessage(EventCloneOperation.Error error) {
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
        generatedChartDialogs.showComplexMove();
    }

    ComplexMoveGenerator.Spec readComplexMoveSpec(
            EditText startTime, EditText endTime,
            EditText xEquation, EditText yEquation,
            TimeEasingInput xTimeEasing, TimeEasingInput yTimeEasing,
            EditText generationDensity) {
        ComplexMoveGenerator.Spec spec = new ComplexMoveGenerator.Spec();
        spec.startTime = BeatTime.parseFlexible(startTime.getText().toString());
        spec.endTime = BeatTime.parseFlexible(endTime.getText().toString());
        spec.xExpression = xEquation.getText().toString();
        spec.yExpression = yEquation.getText().toString();
        spec.xTimeEasing = readTimeEasing(xTimeEasing);
        spec.yTimeEasing = readTimeEasing(yTimeEasing);
        spec.density = parseDouble(generationDensity);
        return spec;
    }

    private ComplexMoveGenerator.TimeEasing readTimeEasing(TimeEasingInput input) {
        return new ComplexMoveGenerator.TimeEasing(input.type.getSelectedItemPosition() + Easing.MIN_TYPE,
                input.windows.isChecked() ? parseDouble(input.left) : 0,
                input.windows.isChecked() ? parseDouble(input.right) : 1);
    }

    static final class TimeEasingInput {
        final Spinner type;
        final CheckBox windows;
        final EditText left, right;
        TimeEasingInput(Spinner type, CheckBox windows, EditText left, EditText right) {
            this.type = type; this.windows = windows; this.left = left; this.right = right;
        }
    }

    String complexMoveErrorMessage(ComplexMoveGenerator.Error error, String detail) {
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
        generatedChartDialogs.showCurveNotes();
    }

    String curveNotesErrorMessage(CurveNoteGenerator.Error error) {
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

    String noteTypeLabel(NoteType type) {
        switch (type) {
            case TAP: return getString(R.string.controls_tap);
            case DRAG: return getString(R.string.controls_drag);
            case FLICK: return getString(R.string.controls_flick);
            case HOLD: return getString(R.string.controls_hold);
            default: return type.name();
        }
    }

    static NoteType curveNoteType(int position) {
        switch (position) {
            case 1: return NoteType.DRAG;
            case 2: return NoteType.FLICK;
            default: return NoteType.TAP;
        }
    }
}
