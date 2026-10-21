package com.xpe.mobile.editor;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.xpe.mobile.R;
import com.xpe.mobile.i18n.UiDialogs;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.Easing;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.StoryboardEventType;
import java.util.Locale;

/** Dialogs for extended judge-line storyboard events. */
final class StoryboardDialogSection extends EditorDialogSection {
    private interface StoryboardTypeAction {
        void run(StoryboardEventType type);
    }

    private static final class StoryboardEntry {
        final StoryboardEventType type;
        final ExtendedLineEvents.TimedEvent event;

        StoryboardEntry(StoryboardEventType type, ExtendedLineEvents.TimedEvent event) {
            this.type = type;
            this.event = event;
        }
    }

    StoryboardDialogSection(Activity activity, EditorView editorView,
                            EditorDialogController.Host host) {
        super(activity, editorView, host);
    }

    public void showStoryboardEditor() {
        host.workbench().switchTab(1);
    }

    private void chooseStoryboardType(StoryboardTypeAction action) {
        StoryboardEventType[] types = StoryboardEventType.editableTypes();
        new UiDialogs.Builder(this)
                .setTitle(R.string.storyboard_choose_type)
                .setItems(() -> {
                    CharSequence[] labels = new CharSequence[types.length];
                    for (int index = 0; index < types.length; index++) labels[index] = storyboardTypeLabel(types[index]);
                    return labels;
                }, (dialog, which) -> action.run(types[which]))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    public void showStoryboardEventForm(StoryboardEventType type,
                                         ExtendedLineEvents.TimedEvent source,
                                         boolean creating, Runnable afterSave) {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout content = createDialogContent(scrollView);
        addHint(content, storyboardHelpResource(type));
        EditText startTime = addField(content, R.string.field_start_beat,
                source.startTime.toString(), false);
        EditText endTime = addField(content, R.string.field_end_beat,
                source.endTime.toString(), false);
        addHint(content, R.string.storyboard_time_help);
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
        TextView startValue = type == StoryboardEventType.COLOR
                ? ColorPickerButton.addTo(content, R.string.storyboard_start_value,
                        ((ExtendedLineEvents.ColorEvent) source).startRgb, false)
                : addField(content,
                R.string.storyboard_start_value,
                storyboardStartValue(source), numeric);
        TextView endValue = type == StoryboardEventType.COLOR
                ? ColorPickerButton.addTo(content, R.string.storyboard_end_value,
                        ((ExtendedLineEvents.ColorEvent) source).endRgb, false)
                : addField(content,
                R.string.storyboard_end_value,
                storyboardEndValue(source), numeric);
        if (type == StoryboardEventType.TEXT) {
            configureStoryboardTextField((EditText) startValue);
            configureStoryboardTextField((EditText) endValue);
        }
        CheckBox locked = addCheckBox(content, R.string.field_event_locked,
                storyboardValuesEqual(source));
        EasingSelector curve = EasingSelector.addTo(content, R.string.field_event_easing_type, source.easingType);
        Spinner easingType = curve.spinner;
        EasingPreviewView easingPreview = curve.preview;
        EditText easingLeft = addField(content, R.string.field_event_easing_left,
                formatNumber(source.easingLeft), true);
        EditText easingRight = addField(content, R.string.field_event_easing_right,
                formatNumber(source.easingRight), true);
        EditText linkGroup = addField(content, R.string.field_event_link_group,
                Integer.toString(source.linkGroup), true);
        CheckBox bezier = addCheckBox(content, R.string.field_event_bezier, source.bezier);
        EditText bezierX1 = addField(content, R.string.field_event_bezier_x1,
                formatNumber(source.bezierPoints[0]), true);
        EditText bezierY1 = addField(content, R.string.field_event_bezier_y1,
                formatNumber(source.bezierPoints[1]), true);
        EditText bezierX2 = addField(content, R.string.field_event_bezier_x2,
                formatNumber(source.bezierPoints[2]), true);
        EditText bezierY2 = addField(content, R.string.field_event_bezier_y2,
                formatNumber(source.bezierPoints[3]), true);

        Runnable refreshEasing = () -> updateEasingEditor(
                false, easingType, easingLeft, easingRight, bezier,
                bezierX1, bezierY1, bezierX2, bezierY2, easingPreview);
        curve.setOnChanged(refreshEasing);
        bezier.setOnCheckedChangeListener((button, checked) -> refreshEasing.run());
        watch(refreshEasing, easingLeft, easingRight,
                bezierX1, bezierY1, bezierX2, bezierY2);
        locked.setOnCheckedChangeListener((button, checked) -> endValue.setEnabled(!checked));
        endValue.setEnabled(!locked.isChecked());
        refreshEasing.run();

        EditorPanelWindow dialog = new EditorPanelWindow.Builder(this, host.workbench())
                .setTitle(() -> getString(creating ? R.string.storyboard_new_title
                        : R.string.storyboard_edit_title, storyboardTypeLabel(type)))
                .selectionProperties(creating ? null : source, 1)
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
                        editorView.selectStoryboardEvent(type, creating ? edited : source);
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
                                      TextView startValue, TextView endValue,
                                      boolean locked) {
        if (event instanceof ExtendedLineEvents.NumericEvent) {
            ExtendedLineEvents.NumericEvent numeric =
                    (ExtendedLineEvents.NumericEvent) event;
            numeric.start = parseDouble((EditText) startValue);
            numeric.end = locked ? numeric.start : parseDouble((EditText) endValue);
        } else if (event instanceof ExtendedLineEvents.ColorEvent) {
            ExtendedLineEvents.ColorEvent color = (ExtendedLineEvents.ColorEvent) event;
            color.startRgb = ((ColorPickerButton) startValue).getColor() & 0xffffff;
            color.endRgb = locked ? color.startRgb
                    : ((ColorPickerButton) endValue).getColor() & 0xffffff;
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

}
