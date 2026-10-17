package com.xpe.mobile;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import com.xpe.mobile.editor.EasingPreviewView;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.editor.PropertyValidator;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.Easing;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;

/** EventPropertyDialog responsibilities extracted from EditorDialogController. */
final class EventPropertyDialog {
    private final EditorDialogController delegateOwner;

    EventPropertyDialog(EditorDialogController delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void showEventProperties(LineEvent event) {
        if (event == null) return;

        ScrollView scrollView = new ScrollView(delegateOwner);
        LinearLayout content = delegateOwner.createDialogContent(scrollView);
        EditText startTime = delegateOwner.addField(content, delegateOwner.getString(R.string.field_start_beat), event.startTime.toString(), false);
        EditText endTime = delegateOwner.addField(content, delegateOwner.getString(R.string.field_end_beat), event.endTime.toString(), false);
        EditText startValue = delegateOwner.addField(content, delegateOwner.getString(R.string.field_event_start_value), EditorDialogController.formatNumber(event.start), true);
        EditText endValue = delegateOwner.addField(content, delegateOwner.getString(R.string.field_event_end_value), EditorDialogController.formatNumber(event.end), true);
        CheckBox locked = delegateOwner.addCheckBox(content, R.string.field_event_locked, Double.compare(event.start, event.end) == 0);
        EasingSelector curve = EasingSelector.addTo(content,
                delegateOwner.getString(R.string.field_event_easing_type), event.easingType);
        Spinner easingType = curve.spinner;
        EasingPreviewView easingPreview = curve.preview;
        delegateOwner.addHint(content, delegateOwner.getString(R.string.field_event_easing_help));
        EditText easingLeft = delegateOwner.addField(content, delegateOwner.getString(R.string.field_event_easing_left), EditorDialogController.formatNumber(event.easingLeft), true);
        EditText easingRight = delegateOwner.addField(content, delegateOwner.getString(R.string.field_event_easing_right), EditorDialogController.formatNumber(event.easingRight), true);
        EditText linkGroup = delegateOwner.addField(content, delegateOwner.getString(R.string.field_event_link_group), Integer.toString(event.linkGroup), true);
        CheckBox bezier = delegateOwner.addCheckBox(content, R.string.field_event_bezier, event.bezier);
        EditText bezierX1 = delegateOwner.addField(content, delegateOwner.getString(R.string.field_event_bezier_x1), EditorDialogController.formatNumber(event.bezierPoints[0]), true);
        EditText bezierY1 = delegateOwner.addField(content, delegateOwner.getString(R.string.field_event_bezier_y1), EditorDialogController.formatNumber(event.bezierPoints[1]), true);
        EditText bezierX2 = delegateOwner.addField(content, delegateOwner.getString(R.string.field_event_bezier_x2), EditorDialogController.formatNumber(event.bezierPoints[2]), true);
        EditText bezierY2 = delegateOwner.addField(content, delegateOwner.getString(R.string.field_event_bezier_y2), EditorDialogController.formatNumber(event.bezierPoints[3]), true);

        boolean speedEvent = event.type == EventType.SPEED;
        Runnable refreshEasingUi = () -> delegateOwner.updateEasingEditor(
                speedEvent, easingType, easingLeft, easingRight, bezier,
                bezierX1, bezierY1, bezierX2, bezierY2, easingPreview);
        curve.setOnChanged(refreshEasingUi);
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

        EditorPanelWindow dialog = new EditorPanelWindow.Builder(delegateOwner, delegateOwner.host.workbench())
                .setTitle(delegateOwner.getString(R.string.event_properties_title, delegateOwner.eventTypeLabel(event.type)))
                .selectionProperties(event, 0)
                .setView(scrollView)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_apply, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                LineEvent edited = event.copy();
                edited.startTime = BeatTime.parse(startTime.getText().toString());
                edited.endTime = BeatTime.parse(endTime.getText().toString());
                edited.start = EditorDialogController.parseDouble(startValue);
                edited.end = locked.isChecked() ? edited.start : EditorDialogController.parseDouble(endValue);
                edited.linkGroup = EditorDialogController.parseInteger(linkGroup);
                if (speedEvent) {
                    edited.easingType = 1;
                    edited.easingLeft = 0.0;
                    edited.easingRight = 1.0;
                    edited.bezier = false;
                } else {
                    edited.easingType = easingType.getSelectedItemPosition() + Easing.MIN_TYPE;
                    edited.easingLeft = EditorDialogController.parseDouble(easingLeft);
                    edited.easingRight = EditorDialogController.parseDouble(easingRight);
                    edited.bezier = bezier.isChecked();
                    edited.bezierPoints[0] = EditorDialogController.parseDouble(bezierX1);
                    edited.bezierPoints[1] = EditorDialogController.parseDouble(bezierY1);
                    edited.bezierPoints[2] = EditorDialogController.parseDouble(bezierX2);
                    edited.bezierPoints[3] = EditorDialogController.parseDouble(bezierY2);
                }

                PropertyValidator.Error validation = PropertyValidator.validate(edited);
                if (validation != PropertyValidator.Error.NONE) {
                    delegateOwner.showValidationError(validation);
                    return;
                }
                EditorView.PropertyApplyResult result = delegateOwner.editorView.applyEventProperties(event, edited);
                if (result != EditorView.PropertyApplyResult.APPLIED) {
                    delegateOwner.showApplyError(result);
                    return;
                }
                dialog.dismiss();
                delegateOwner.showMessage(delegateOwner.getString(R.string.event_properties_applied));
            } catch (IllegalArgumentException exception) {
                delegateOwner.showMessage(delegateOwner.getString(R.string.validation_invalid_number_or_beat));
            }
        }));
        dialog.show();
    }
}
