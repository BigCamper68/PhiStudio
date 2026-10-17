package com.xpe.mobile;

import android.app.AlertDialog;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import com.xpe.mobile.config.EditorSettings;

/** EditorSettingsDialog responsibilities extracted from EditorDialogController. */
final class EditorSettingsDialog {
    private final EditorDialogController delegateOwner;

    EditorSettingsDialog(EditorDialogController delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void showSettings() {
        EditorSettings currentSettings = delegateOwner.host.editorSettings();
        EditorSettings draft = currentSettings == null
                ? new EditorSettings() : currentSettings.copy();
        ScrollView scroll = new ScrollView(delegateOwner);
        LinearLayout content = delegateOwner.createDialogContent(scroll);

        delegateOwner.addMenuSection(content, R.string.settings_general);
        EditText musicVolume = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_music_volume),
                EditorDialogController.formatNumber(draft.musicVolume), true);
        EditText soundEffectVolume = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_sound_effect_volume),
                EditorDialogController.formatNumber(draft.soundEffectVolume), true);
        CheckBox highlight = delegateOwner.addCheckBox(content, R.string.settings_highlight_simultaneous,
                draft.highlightSimultaneousNotes);
        CheckBox fakeOutline = delegateOwner.addCheckBox(content, R.string.settings_fake_note_outline,
                draft.showFakeNoteOutline);
        EditText noteWidth = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_note_width),
                EditorDialogController.formatNumber(draft.noteWidthPixels), true);
        CheckBox markLineId = delegateOwner.addCheckBox(content, R.string.settings_mark_line_id,
                draft.markLineId);
        CheckBox highlightCurrentLine = delegateOwner.addCheckBox(content, R.string.settings_highlight_current_line,
                draft.highlightCurrentLine);
        ColorPickerButton lineColor = ColorPickerButton.addTo(content,
                delegateOwner.getString(R.string.settings_line_color), draft.lineColorRgb, false);
        EditText backgroundBrightness = delegateOwner.addField(content,
                delegateOwner.getString(R.string.settings_background_brightness),
                Integer.toString(draft.backgroundBrightness), true);
        EditText tapOffset = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_hitsound_tap_offset),
                Integer.toString(draft.tapFlickHitsoundOffsetMs), true);
        EditText dragOffset = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_hitsound_drag_offset),
                Integer.toString(draft.dragHitsoundOffsetMs), true);

        delegateOwner.addMenuSection(content, R.string.settings_other);
        CheckBox autosave = delegateOwner.addCheckBox(content, R.string.settings_autosave,
                draft.autosaveEnabled);
        EditText autosaveInterval = delegateOwner.addField(content,
                delegateOwner.getString(R.string.settings_autosave_interval),
                EditorDialogController.formatNumber(draft.autosaveIntervalSeconds), true);
        CheckBox tapToSeek = delegateOwner.addCheckBox(content, R.string.settings_tap_to_seek, draft.tapToSeek);
        EditText timelineScroll = delegateOwner.addField(content,
                delegateOwner.getString(R.string.settings_timeline_scroll_speed),
                EditorDialogController.formatNumber(draft.timelineScrollSpeed), true);
        EditText eventScroll = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_event_scroll_speed),
                EditorDialogController.formatNumber(draft.eventScrollSpeed), true);
        EditText playerWidth = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_player_width),
                Integer.toString(draft.playerWidth), true);
        EditText playerHeight = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_player_height),
                Integer.toString(draft.playerHeight), true);
        CheckBox autoApply = delegateOwner.addCheckBox(content, R.string.settings_auto_apply,
                draft.autoApplyPropertyEdits);
        CheckBox autoClipboard = delegateOwner.addCheckBox(content, R.string.settings_auto_clipboard,
                draft.autoMoveToClipboard);
        CheckBox showTips = delegateOwner.addCheckBox(content, R.string.settings_show_tips, draft.showTips);
        CheckBox xyBinding = delegateOwner.addCheckBox(content, R.string.settings_xy_binding,
                draft.xyBindingEnabled);
        CheckBox skipUndo = delegateOwner.addCheckBox(content, R.string.settings_skip_undo,
                draft.skipWhenUndoRedo);
        CheckBox autoStick = delegateOwner.addCheckBox(content, R.string.settings_auto_stick,
                draft.autoStickEvents);
        CheckBox splitSnap = delegateOwner.addCheckBox(content, R.string.settings_split_snap_to_grid,
                draft.splitSnapToGrid);
        CheckBox drawCurves = delegateOwner.addCheckBox(content, R.string.settings_draw_event_curves,
                draft.drawEventCurves);
        CheckBox drawNumbers = delegateOwner.addCheckBox(content, R.string.settings_draw_event_numbers,
                draft.drawEventNumbers);

        delegateOwner.addMenuSection(content, R.string.settings_hotkeys);
        delegateOwner.addHint(content, delegateOwner.getString(R.string.settings_hotkeys_help));
        EditText shortcutPlay = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_hotkey_play),
                draft.shortcutPlayPause, false);
        EditText shortcutSave = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_hotkey_save),
                draft.shortcutSave, false);
        EditText shortcutUndo = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_hotkey_undo),
                draft.shortcutUndo, false);
        EditText shortcutRedo = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_hotkey_redo),
                draft.shortcutRedo, false);
        EditText shortcutCopy = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_hotkey_copy),
                draft.shortcutCopy, false);
        EditText shortcutCut = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_hotkey_cut),
                draft.shortcutCut, false);
        EditText shortcutPaste = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_hotkey_paste),
                draft.shortcutPaste, false);
        EditText shortcutMirror = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_hotkey_mirror),
                draft.shortcutMirrorPaste, false);
        EditText shortcutDelete = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_hotkey_delete),
                draft.shortcutDelete, false);

        delegateOwner.addMenuSection(content, R.string.settings_correction);
        EditText correctionX = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_correction_x),
                EditorDialogController.formatNumber(draft.correctionXThreshold), true);
        EditText correctionCollision = delegateOwner.addField(content,
                delegateOwner.getString(R.string.settings_correction_collision),
                EditorDialogController.formatNumber(draft.correctionCollisionDistance), true);
        EditText correctionRead = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_correction_read),
                EditorDialogController.formatNumber(draft.correctionReadTimeSeconds), true);
        EditText correctionDrag = delegateOwner.addField(content, delegateOwner.getString(R.string.settings_correction_drag),
                EditorDialogController.formatNumber(draft.correctionDragWarningSeconds), true);
        EditText correctionCombo = delegateOwner.addField(content,
                delegateOwner.getString(R.string.settings_correction_combination),
                EditorDialogController.formatNumber(draft.correctionCombinationSeconds), true);

        EditorPanelWindow dialog = new EditorPanelWindow.Builder(delegateOwner, delegateOwner.host.workbench())
                .setTitle(R.string.settings_title)
                .retainOnLineChange()
                .setView(scroll)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_apply, null)
                .create();
        delegateOwner.showEditorWindow(dialog, () -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        draft.musicVolume = EditorDialogController.parseDouble(musicVolume);
                        draft.soundEffectVolume = EditorDialogController.parseDouble(soundEffectVolume);
                        draft.highlightSimultaneousNotes = highlight.isChecked();
                        draft.showFakeNoteOutline = fakeOutline.isChecked();
                        draft.noteWidthPixels = EditorDialogController.parseDouble(noteWidth);
                        draft.markLineId = markLineId.isChecked();
                        draft.highlightCurrentLine = highlightCurrentLine.isChecked();
                        draft.lineColorRgb = lineColor.getColor() & 0xffffff;
                        draft.backgroundBrightness = EditorDialogController.parseInteger(backgroundBrightness);
                        draft.tapFlickHitsoundOffsetMs = EditorDialogController.parseInteger(tapOffset);
                        draft.dragHitsoundOffsetMs = EditorDialogController.parseInteger(dragOffset);
                        draft.autosaveEnabled = autosave.isChecked();
                        draft.autosaveIntervalSeconds = EditorDialogController.parseDouble(autosaveInterval);
                        draft.tapToSeek = tapToSeek.isChecked();
                        draft.timelineScrollSpeed = EditorDialogController.parseDouble(timelineScroll);
                        draft.eventScrollSpeed = EditorDialogController.parseDouble(eventScroll);
                        draft.playerWidth = EditorDialogController.parseInteger(playerWidth);
                        draft.playerHeight = EditorDialogController.parseInteger(playerHeight);
                        draft.autoApplyPropertyEdits = autoApply.isChecked();
                        draft.autoMoveToClipboard = autoClipboard.isChecked();
                        draft.showTips = showTips.isChecked();
                        draft.xyBindingEnabled = xyBinding.isChecked();
                        draft.skipWhenUndoRedo = skipUndo.isChecked();
                        draft.autoStickEvents = autoStick.isChecked();
                        draft.splitSnapToGrid = splitSnap.isChecked();
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
                        draft.correctionXThreshold = EditorDialogController.parseDouble(correctionX);
                        draft.correctionCollisionDistance = EditorDialogController.parseDouble(correctionCollision);
                        draft.correctionReadTimeSeconds = EditorDialogController.parseDouble(correctionRead);
                        draft.correctionDragWarningSeconds = EditorDialogController.parseDouble(correctionDrag);
                        draft.correctionCombinationSeconds = EditorDialogController.parseDouble(correctionCombo);
                        if (!draft.isValid()) throw new IllegalArgumentException();
                    } catch (IllegalArgumentException exception) {
                        delegateOwner.showMessage(delegateOwner.getString(R.string.settings_invalid));
                        return;
                    }
                    delegateOwner.host.applyEditorSettings(draft.copy());
                    dialog.dismiss();
                    delegateOwner.showMessage(delegateOwner.getString(R.string.settings_saved));
                }));
    }
}
