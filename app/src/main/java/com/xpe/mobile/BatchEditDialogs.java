package com.xpe.mobile;

import android.app.AlertDialog;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import com.xpe.mobile.editor.BatchEditOperation;
import com.xpe.mobile.editor.BatchValueTransform;
import com.xpe.mobile.editor.EventCloneOperation;
import com.xpe.mobile.model.BeatTime;
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
        delegateOwner.addHint(content, delegateOwner.getString(R.string.batch_help));

        List<String> targetEntries = new ArrayList<>();
        List<Boolean> noteTargets = new ArrayList<>();
        if (!notes.isEmpty()) {
            targetEntries.add(delegateOwner.getString(R.string.batch_target_notes, notes.size()));
            noteTargets.add(true);
        }
        if (!events.isEmpty()) {
            targetEntries.add(delegateOwner.getString(R.string.batch_target_events, events.size()));
            noteTargets.add(false);
        }
        Spinner target = delegateOwner.addStringSpinner(content, delegateOwner.getString(R.string.batch_target),
                targetEntries, 0);
        Spinner eventType = delegateOwner.addStringSpinner(content, delegateOwner.getString(R.string.batch_event_type),
                delegateOwner.eventTypeLabels(), 0);
        Spinner field = delegateOwner.addStringSpinner(content, delegateOwner.getString(R.string.batch_data_type),
                delegateOwner.resourceEntries(R.array.batch_note_fields), 0);
        EditText lower = delegateOwner.addField(content, delegateOwner.getString(R.string.batch_lower_bound), "0", true);
        EditText upper = delegateOwner.addField(content, delegateOwner.getString(R.string.batch_upper_bound), "0", true);
        EditText easing = delegateOwner.addField(content, delegateOwner.getString(R.string.batch_easing), "1", true);
        EditText sequence = delegateOwner.addField(content, delegateOwner.getString(R.string.batch_periodic_sequence),
                "1", false);
        EditText disturbance = delegateOwner.addField(content, delegateOwner.getString(R.string.batch_disturbance),
                "0", true);
        Spinner mode = delegateOwner.addSpinner(content, R.string.batch_edit_mode,
                R.array.batch_edit_modes, 0);

        Runnable updateTarget = () -> {
            boolean noteMode = noteTargets.get(target.getSelectedItemPosition());
            eventType.setEnabled(!noteMode);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(delegateOwner,
                    android.R.layout.simple_spinner_item,
                    delegateOwner.resourceEntries(noteMode ? R.array.batch_note_fields
                            : R.array.batch_event_fields));
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            field.setAdapter(adapter);
        };
        EditorOperationDialogSection.setSpinnerChangeListener(target, updateTarget);
        updateTarget.run();

        EditorPanelWindow dialog = new EditorPanelWindow.Builder(delegateOwner, delegateOwner.host.workbench())
                .setTitle(R.string.batch_title)
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
                    profile = delegateOwner.batchProfile(lower, upper, easing, sequence, disturbance,
                            System.nanoTime());
                } catch (IllegalArgumentException exception) {
                    delegateOwner.showMessage(delegateOwner.getString(R.string.batch_invalid_profile));
                    return;
                }
                BatchValueTransform.Mode selectedMode = BatchValueTransform.Mode.values()[
                        mode.getSelectedItemPosition()];
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
        List<LineEvent> events = delegateOwner.editorView.getSelectedEventsForBatch();
        if (events.isEmpty()) {
            delegateOwner.showMessage(delegateOwner.getString(R.string.event_clone_empty));
            return;
        }
        ScrollView scroll = new ScrollView(delegateOwner);
        LinearLayout content = delegateOwner.createDialogContent(scroll);
        delegateOwner.addHint(content, delegateOwner.getResources().getQuantityString(R.plurals.event_clone_help,
                events.size(), events.size(), delegateOwner.editorView.getEventLayerIndex()));
        EditText lines = delegateOwner.addField(content, delegateOwner.getString(R.string.event_clone_line_sequence),
                Integer.toString(delegateOwner.editorView.getLineIndex()), false);
        EditText timeIncrement = delegateOwner.addField(content,
                delegateOwner.getString(R.string.event_clone_time_increment), "0:0/1", false);
        EditText xUled = delegateOwner.addField(content, delegateOwner.getString(R.string.event_clone_x_uled),
                "0 0 1 0", false);
        EditText xSequence = delegateOwner.addField(content, delegateOwner.getString(R.string.event_clone_x_sequence),
                "1", false);
        EditText yUled = delegateOwner.addField(content, delegateOwner.getString(R.string.event_clone_y_uled),
                "0 0 1 0", false);
        EditText ySequence = delegateOwner.addField(content, delegateOwner.getString(R.string.event_clone_y_sequence),
                "1", false);
        EditText rotateUled = delegateOwner.addField(content, delegateOwner.getString(R.string.event_clone_rotate_uled),
                "0 0 1 0", false);
        EditText rotateSequence = delegateOwner.addField(content,
                delegateOwner.getString(R.string.event_clone_rotate_sequence), "1", false);
        EditText alphaUled = delegateOwner.addField(content, delegateOwner.getString(R.string.event_clone_alpha_uled),
                "0 0 1 0", false);
        EditText alphaSequence = delegateOwner.addField(content,
                delegateOwner.getString(R.string.event_clone_alpha_sequence), "1", false);
        CheckBox keepSource = delegateOwner.addCheckBox(content, R.string.event_clone_keep_source, false);

        EditorPanelWindow dialog = new EditorPanelWindow.Builder(delegateOwner, delegateOwner.host.workbench())
                .setTitle(R.string.event_clone_title)
                .setView(scroll)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.event_clone_apply, null)
                .create();
        delegateOwner.showEditorWindow(dialog, () -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    EventCloneOperation.Spec spec = new EventCloneOperation.Spec();
                    long seed = System.nanoTime();
                    try {
                        spec.lineSequence = EventCloneOperation.parseLineSequence(
                                lines.getText().toString());
                        spec.timeIncrement = BeatTime.parseFlexible(
                                timeIncrement.getText().toString());
                        spec.xProfile = delegateOwner.cloneProfile(xUled, xSequence, seed + 1);
                        spec.yProfile = delegateOwner.cloneProfile(yUled, ySequence, seed + 2);
                        spec.rotateProfile = delegateOwner.cloneProfile(rotateUled, rotateSequence, seed + 3);
                        spec.alphaProfile = delegateOwner.cloneProfile(alphaUled, alphaSequence, seed + 4);
                        spec.keepSource = keepSource.isChecked();
                    } catch (IllegalArgumentException exception) {
                        delegateOwner.showMessage(delegateOwner.getString(R.string.event_clone_invalid));
                        return;
                    }
                    EventCloneOperation.Result result = delegateOwner.editorView.applyEventClone(spec);
                    if (result.error != EventCloneOperation.Error.NONE) {
                        delegateOwner.showMessage(delegateOwner.eventCloneErrorMessage(result.error));
                        return;
                    }
                    dialog.dismiss();
                    delegateOwner.showMessage(delegateOwner.getResources().getQuantityString(R.plurals.event_clone_applied,
                            result.events.size(), result.events.size()));
                }));
    }
}
