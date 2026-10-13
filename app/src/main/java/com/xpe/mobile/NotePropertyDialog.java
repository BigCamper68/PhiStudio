package com.xpe.mobile;

import android.app.AlertDialog;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.editor.PropertyValidator;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;
import java.util.ArrayList;
import java.util.List;

/** NotePropertyDialog responsibilities extracted from EditorDialogController. */
final class NotePropertyDialog {
    private final EditorDialogController delegateOwner;

    NotePropertyDialog(EditorDialogController delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void showNoteProperties(Note note) {
        if (note == null) return;

        ScrollView scrollView = new ScrollView(delegateOwner);
        LinearLayout content = delegateOwner.createDialogContent(scrollView);
        Spinner type = delegateOwner.addSpinner(content, R.string.field_note_type, R.array.note_types, note.type.ordinal());
        EditText startTime = delegateOwner.addField(content, delegateOwner.getString(R.string.field_start_beat), note.startTime.toString(), false);
        EditText endTime = delegateOwner.addField(content, delegateOwner.getString(R.string.field_end_beat_hold), note.endTime.toString(), false);
        EditText positionX = delegateOwner.addField(content, delegateOwner.getString(R.string.field_position_x), EditorDialogController.formatNumber(note.positionX), true);
        EditText speed = delegateOwner.addField(content, delegateOwner.getString(R.string.field_note_speed), EditorDialogController.formatNumber(note.speed), true);
        Spinner side = delegateOwner.addSpinner(content, R.string.field_note_side, R.array.note_sides, note.above == 1 ? 0 : 1);
        CheckBox fake = delegateOwner.addCheckBox(content, R.string.field_note_fake, note.fake);
        EditText size = delegateOwner.addField(content, delegateOwner.getString(R.string.field_note_size), EditorDialogController.formatNumber(note.size), true);
        EditText yOffset = delegateOwner.addField(content, delegateOwner.getString(R.string.field_note_y_offset), EditorDialogController.formatNumber(note.yOffset), true);
        EditText visibleTime = delegateOwner.addField(content, delegateOwner.getString(R.string.field_note_visible_time), EditorDialogController.formatNumber(note.visibleTime), true);
        EditText alpha = delegateOwner.addField(content, delegateOwner.getString(R.string.field_note_alpha), Integer.toString(note.alpha), true);
        EditText hitSound = delegateOwner.addField(content, delegateOwner.getString(R.string.field_note_hitsound),
                note.hitSound == null ? "" : note.hitSound, false);
        hitSound.setSingleLine(true);
        TextView hitSoundHint = new TextView(delegateOwner);
        hitSoundHint.setText(R.string.field_note_hitsound_hint);
        hitSoundHint.setTextSize(12f);
        content.addView(hitSoundHint);
        Button chooseHitSound = new Button(delegateOwner);
        chooseHitSound.setText(R.string.action_choose_hitsound);
        content.addView(chooseHitSound);
        chooseHitSound.setOnClickListener(view -> {
            List<String> choices = delegateOwner.host.hitSoundChoices();
            List<String> labels = new ArrayList<>();
            labels.add(delegateOwner.getString(R.string.hitsound_default));
            for (String path : choices) {
                if ("tap.mp3".equals(path)) labels.add("Tap / Hold (tap.mp3)");
                else if ("flick.mp3".equals(path)) labels.add("Flick (flick.mp3)");
                else if ("drag.mp3".equals(path)) labels.add("Drag (drag.mp3)");
                else labels.add(path);
            }
            new AlertDialog.Builder(delegateOwner).setTitle(R.string.field_note_hitsound)
                    .setItems(labels.toArray(new String[0]), (ignored, index) ->
                            hitSound.setText(index == 0 ? "" : choices.get(index - 1)))
                    .setNegativeButton(R.string.action_cancel, null).show();
        });

        EditorPanelWindow dialog = new EditorPanelWindow.Builder(delegateOwner, delegateOwner.host.workbench())
                .setTitle(R.string.note_properties_title)
                .selectionProperties(note, delegateOwner.editorView.getWorkbenchTab() == 1 ? 1 : 0)
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
                edited.positionX = EditorDialogController.parseDouble(positionX);
                edited.speed = EditorDialogController.parseDouble(speed);
                edited.above = side.getSelectedItemPosition() == 0 ? 1 : 0;
                edited.fake = fake.isChecked();
                edited.size = EditorDialogController.parseDouble(size);
                edited.yOffset = EditorDialogController.parseDouble(yOffset);
                edited.visibleTime = EditorDialogController.parseDouble(visibleTime);
                edited.alpha = EditorDialogController.parseInteger(alpha);
                String reference = hitSound.getText().toString().trim();
                edited.hitSound = reference.isEmpty() ? null : reference;
                if (!java.util.Objects.equals(edited.hitSound, note.hitSound)
                        && !delegateOwner.host.isHitSoundAvailable(edited.hitSound)) {
                    delegateOwner.showMessage(delegateOwner.getString(R.string.hitsound_file_unavailable));
                    return;
                }

                PropertyValidator.Error validation = PropertyValidator.validate(edited);
                if (validation != PropertyValidator.Error.NONE) {
                    delegateOwner.showValidationError(validation);
                    return;
                }
                EditorView.PropertyApplyResult result = delegateOwner.editorView.applyNoteProperties(note, edited);
                if (result != EditorView.PropertyApplyResult.APPLIED) {
                    delegateOwner.showApplyError(result);
                    return;
                }
                dialog.dismiss();
                delegateOwner.showMessage(delegateOwner.getString(R.string.note_properties_applied));
            } catch (IllegalArgumentException exception) {
                delegateOwner.showMessage(delegateOwner.getString(R.string.validation_invalid_number_or_beat));
            }
        }));
        dialog.show();
    }
}
