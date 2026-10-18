package com.xpe.mobile;

import com.xpe.mobile.i18n.UiText;

import android.app.AlertDialog;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import com.xpe.mobile.editor.BatchEditOperation;
import com.xpe.mobile.editor.BatchValueTransform;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import java.util.ArrayList;
import java.util.List;

/** BatchEditDialogs responsibilities extracted from EditorOperationDialogSection. */
final class BatchEditDialogs {
    private final EditorOperationDialogSection delegateOwner;

    BatchEditDialogs(EditorOperationDialogSection delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void showAdvancedBatchEdit() {
        List<Note> notes = delegateOwner.editorView.getSelectedNotesForBatch();
        List<LineEvent> events = delegateOwner.editorView.getSelectedEventsForBatch();
        if (notes.isEmpty() && events.isEmpty()) {
            delegateOwner.showMessage(delegateOwner.getString(R.string.batch_empty_selection));
            return;
        }
        ScrollView scroll = new ScrollView(delegateOwner);
        LinearLayout content = delegateOwner.createDialogContent(scroll);

        List<Boolean> noteTargets = new ArrayList<>();
        if (!notes.isEmpty()) {
            noteTargets.add(true);
        }
        if (!events.isEmpty()) {
            noteTargets.add(false);
        }
        Spinner target = delegateOwner.addStringSpinner(content, R.string.batch_target,
                () -> {
                    List<String> labels = new ArrayList<>();
                    if (!notes.isEmpty()) labels.add(delegateOwner.getString(R.string.batch_target_notes, notes.size()));
                    if (!events.isEmpty()) labels.add(delegateOwner.getString(R.string.batch_target_events, events.size()));
                    return labels;
                }, 0);
        Spinner eventType = delegateOwner.addStringSpinner(content, R.string.batch_event_type,
                delegateOwner::eventTypeLabels, events.isEmpty() ? 0 : events.get(0).type.ordinal());
        Spinner field = delegateOwner.addStringSpinner(content, R.string.batch_data_type,
                delegateOwner.resourceEntries(R.array.batch_note_fields), 0);
        LinearLayout values = new LinearLayout(delegateOwner);
        values.setOrientation(LinearLayout.VERTICAL);
        content.addView(values, new LinearLayout.LayoutParams(-1, -2));
        delegateOwner.addHint(values, R.string.batch_help);
        EditText lower = delegateOwner.addField(values, R.string.batch_lower_bound, "0", true);
        EditText upper = delegateOwner.addField(values, R.string.batch_upper_bound, "0", true);
        EasingSelector progression = EasingSelector.addTo(values, R.string.batch_easing, 1);
        EditText sequence = delegateOwner.addField(values, R.string.batch_periodic_sequence,
                "1", false);
        EditText disturbance = delegateOwner.addField(values, R.string.batch_disturbance,
                "0", true);
        Spinner mode = delegateOwner.addSpinner(values, R.string.batch_edit_mode,
                R.array.batch_edit_modes, 0);
        LinearLayout curveValues = new LinearLayout(delegateOwner);
        curveValues.setOrientation(LinearLayout.VERTICAL);
        content.addView(curveValues, new LinearLayout.LayoutParams(-1, -2));
        EasingSelector eventCurve = EasingSelector.addTo(curveValues,
                R.string.field_event_easing_type, events.isEmpty() ? 1 : events.get(0).easingType);
        android.widget.TextView curveHelp = delegateOwner.addHint(curveValues, R.string.batch_curve_help);
        Runnable updateField = () -> {
            boolean curveMode = !noteTargets.get(target.getSelectedItemPosition())
                    && field.getSelectedItemPosition() == BatchEditOperation.EventField.EASING_TYPE.ordinal();
            values.setVisibility(curveMode ? View.GONE : View.VISIBLE);
            curveValues.setVisibility(curveMode ? View.VISIBLE : View.GONE);
            boolean speedCurve = curveMode && eventType.getSelectedItemPosition() == EventType.SPEED.ordinal();
            eventCurve.spinner.setEnabled(!speedCurve);
            if (speedCurve) eventCurve.spinner.setSelection(0);
            UiText.text(curveHelp, speedCurve ? R.string.batch_unsupported_field : R.string.batch_curve_help);
        };
        EditorDialogSection.setSpinnerChangeListener(field, updateField);
        EditorDialogSection.setSpinnerChangeListener(eventType, updateField);
        Runnable updateTarget = () -> {
            boolean noteMode = noteTargets.get(target.getSelectedItemPosition());
            eventType.setEnabled(!noteMode);
            UiText.entries(field, () -> delegateOwner.resourceEntries(
                    noteTargets.get(target.getSelectedItemPosition()) ? R.array.batch_note_fields : R.array.batch_event_fields));
            updateField.run();
        };
        EditorOperationDialogSection.setSpinnerChangeListener(target, updateTarget);
        updateTarget.run();

        EditorPanelWindow dialog = new EditorPanelWindow.Builder(delegateOwner, delegateOwner.host.workbench())
                .setTitle(R.string.batch_title)
                .setWorkbenchTab(delegateOwner.editorView.getWorkbenchTab())
                .setView(scroll)
                .setNegativeButton(R.string.action_cancel, null)
                .setNeutralButton(R.string.batch_stick, null)
                .setPositiveButton(R.string.action_apply, null)
                .create();
        delegateOwner.showEditorWindow(dialog, () -> {
            Button stick = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            stick.setVisibility(events.isEmpty() ? View.GONE : View.VISIBLE);
            stick.setOnClickListener(view -> {
                EventType type = EventType.values()[eventType.getSelectedItemPosition()];
                BatchEditOperation.Result result = delegateOwner.editorView.stickSelectedEvents(type);
                if (result.error != BatchEditOperation.Error.NONE) {
                    delegateOwner.showMessage(delegateOwner.batchErrorMessage(result.error));
                    return;
                }
                dialog.dismiss();
                delegateOwner.showMessage(delegateOwner.getResources().getQuantityString(R.plurals.batch_stick_applied,
                        result.events.size(), result.events.size()));
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                BatchValueTransform.Spec profile;
                try {
                    if (curveValues.getVisibility() == View.VISIBLE) {
                        profile = new BatchValueTransform.Spec();
                        profile.lowerBound = profile.upperBound = eventCurve.type();
                    } else profile = delegateOwner.batchProfile(lower, upper, progression.type(), sequence, disturbance,
                            System.nanoTime());
                } catch (IllegalArgumentException exception) {
                    delegateOwner.showMessage(delegateOwner.getString(R.string.batch_invalid_profile));
                    return;
                }
                BatchValueTransform.Mode selectedMode = curveValues.getVisibility() == View.VISIBLE
                        ? BatchValueTransform.Mode.TO : BatchValueTransform.Mode.values()[mode.getSelectedItemPosition()];
                boolean noteMode = noteTargets.get(target.getSelectedItemPosition());
                BatchEditOperation.Result result;
                if (noteMode) {
                    result = delegateOwner.editorView.applyNoteBatch(
                            BatchEditOperation.NoteField.values()[field.getSelectedItemPosition()],
                            profile, selectedMode);
                } else {
                    result = delegateOwner.editorView.applyEventBatch(
                            BatchEditOperation.EventField.values()[field.getSelectedItemPosition()],
                            profile, selectedMode,
                            EventType.values()[eventType.getSelectedItemPosition()]);
                }
                if (result.error != BatchEditOperation.Error.NONE) {
                    delegateOwner.showMessage(delegateOwner.batchErrorMessage(result.error));
                    return;
                }
                dialog.dismiss();
                int count = noteMode ? result.notes.size() : result.events.size();
                delegateOwner.showMessage(delegateOwner.getResources().getQuantityString(
                        R.plurals.batch_applied, count, count));
            });
        });
    }

    void showEventClone() {
        new EventCloneDialog(delegateOwner).show();
    }
}
