package com.xpe.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.xpe.mobile.editor.EasingPreviewView;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.editor.StoryboardEventValidator;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.Easing;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.StoryboardEventType;

import java.util.ArrayList;
import java.util.List;
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
        JudgeLine line = editorView.getCurrentJudgeLine();
        if (line == null) {
            showMessage(getString(R.string.storyboard_chart_required));
            return;
        }

        int pad = dp(10);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(4), pad, dp(6));
        TextView scope = addHint(root, getString(R.string.storyboard_scope,
                editorView.getLineIndex(), line.name));
        scope.setTextSize(13f);

        List<String> filters = new ArrayList<>();
        filters.add(getString(R.string.storyboard_filter_all));
        for (StoryboardEventType type : StoryboardEventType.values()) {
            filters.add(storyboardTypeLabel(type));
        }
        Spinner filter = addStringSpinner(root,
                getString(R.string.storyboard_filter), filters, 0);

        ListView list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setMinimumHeight(dp(120));
        List<String> rows = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_single_choice, rows);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        LinearLayout firstActions = new LinearLayout(this);
        firstActions.setOrientation(LinearLayout.HORIZONTAL);
        Button add = addActionButton(firstActions, R.string.storyboard_add);
        Button edit = addActionButton(firstActions, R.string.storyboard_edit);
        Button duplicate = addActionButton(firstActions, R.string.storyboard_duplicate);
        Button delete = addActionButton(firstActions, R.string.storyboard_delete);
        setCompactButtons(firstActions, add, edit, duplicate, delete);
        root.addView(firstActions);

        LinearLayout secondActions = new LinearLayout(this);
        secondActions.setOrientation(LinearLayout.HORIZONTAL);
        Button glue = addActionButton(secondActions, R.string.storyboard_glue);
        Button split = addActionButton(secondActions, R.string.storyboard_split);
        Button close = addActionButton(secondActions, R.string.storyboard_close);
        setCompactButtons(secondActions, glue, split, close);
        root.addView(secondActions);

        List<StoryboardEntry> entries = new ArrayList<>();
        StoryboardEntry[] selected = new StoryboardEntry[1];
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            StoryboardEntry previous = selected[0];
            entries.clear();
            rows.clear();
            StoryboardEventType only = filter.getSelectedItemPosition() <= 0
                    ? null : StoryboardEventType.values()[filter.getSelectedItemPosition() - 1];
            ExtendedLineEvents storyboard = line.extended;
            if (storyboard != null) {
                for (StoryboardEventType type : StoryboardEventType.values()) {
                    if (only != null && only != type) continue;
                    for (ExtendedLineEvents.TimedEvent event : storyboard.events(type)) {
                        StoryboardEntry entry = new StoryboardEntry(type, event);
                        entries.add(entry);
                        rows.add(storyboardEntryLabel(entry));
                    }
                }
            }
            adapter.notifyDataSetChanged();
            selected[0] = null;
            list.clearChoices();
            if (previous != null) {
                for (int index = 0; index < entries.size(); index++) {
                    StoryboardEntry candidate = entries.get(index);
                    if (candidate.type == previous.type && candidate.event == previous.event) {
                        selected[0] = candidate;
                        list.setItemChecked(index, true);
                        break;
                    }
                }
            }
            boolean hasSelection = selected[0] != null;
            edit.setEnabled(hasSelection);
            duplicate.setEnabled(hasSelection);
            delete.setEnabled(hasSelection);
            glue.setEnabled(hasSelection);
            split.setEnabled(hasSelection);
        };
        setSpinnerChangeListener(filter, refresh[0]);
        list.setOnItemClickListener((parent, view, position, id) -> {
            selected[0] = position >= 0 && position < entries.size()
                    ? entries.get(position) : null;
            refresh[0].run();
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.storyboard_title)
                .setView(root)
                .create();
        add.setOnClickListener(view -> {
            int position = filter.getSelectedItemPosition();
            if (position > 0) {
                StoryboardEventType type = StoryboardEventType.values()[position - 1];
                showStoryboardEventForm(type, newStoryboardEvent(type), true, refresh[0]);
            } else {
                chooseStoryboardType(type -> showStoryboardEventForm(
                        type, newStoryboardEvent(type), true, refresh[0]));
            }
        });
        edit.setOnClickListener(view -> {
            StoryboardEntry entry = selected[0];
            if (entry != null) showStoryboardEventForm(
                    entry.type, entry.event, false, refresh[0]);
        });
        duplicate.setOnClickListener(view -> {
            StoryboardEntry entry = selected[0];
            if (entry == null) return;
            try {
                ExtendedLineEvents.TimedEvent copy = entry.event.copy();
                BeatTime duration = copy.endTime.minus(copy.startTime);
                copy.startTime = copy.endTime;
                copy.endTime = copy.endTime.plus(duration);
                showStoryboardEventForm(entry.type, copy, true, refresh[0]);
            } catch (ArithmeticException exception) {
                showMessage(getString(R.string.storyboard_invalid_time));
            }
        });
        delete.setOnClickListener(view -> {
            StoryboardEntry entry = selected[0];
            if (entry == null) return;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.storyboard_delete_title)
                    .setMessage(getString(R.string.storyboard_delete_message,
                            storyboardTypeLabel(entry.type), entry.event.startTime.toString()))
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.storyboard_delete, (ignored, which) -> {
                        EditorView.StoryboardApplyResult result =
                                editorView.deleteStoryboardEvent(entry.type, entry.event);
                        if (result != EditorView.StoryboardApplyResult.APPLIED) {
                            showStoryboardApplyError(result);
                            return;
                        }
                        selected[0] = null;
                        refresh[0].run();
                        showMessage(getString(R.string.storyboard_deleted));
                    })
                    .show();
        });
        glue.setOnClickListener(view -> {
            StoryboardEntry entry = selected[0];
            if (entry == null) return;
            EditorView.StoryboardApplyResult result =
                    editorView.glueStoryboardEvent(entry.type, entry.event);
            if (result != EditorView.StoryboardApplyResult.APPLIED) {
                showStoryboardApplyError(result);
                return;
            }
            refresh[0].run();
            showMessage(getString(R.string.storyboard_glued));
        });
        split.setOnClickListener(view -> {
            StoryboardEntry entry = selected[0];
            if (entry == null) return;
            EditorView.StoryboardApplyResult result = editorView.splitStoryboardEvent(
                    entry.type, entry.event, editorView.getCurrentBeatTime());
            if (result != EditorView.StoryboardApplyResult.APPLIED) {
                showStoryboardApplyError(result);
                return;
            }
            refresh[0].run();
            showMessage(getString(R.string.storyboard_split_applied));
        });
        close.setOnClickListener(view -> dialog.dismiss());
        refresh[0].run();
        showEditorWindow(dialog, null, 0.94f, 0.96f);
    }

    private void chooseStoryboardType(StoryboardTypeAction action) {
        StoryboardEventType[] types = StoryboardEventType.values();
        String[] labels = new String[types.length];
        for (int index = 0; index < types.length; index++) {
            labels[index] = storyboardTypeLabel(types[index]);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.storyboard_choose_type)
                .setItems(labels, (dialog, which) -> action.run(types[which]))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showStoryboardEventForm(StoryboardEventType type,
                                         ExtendedLineEvents.TimedEvent source,
                                         boolean creating, Runnable afterSave) {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout content = createDialogContent(scrollView);
        addHint(content, getString(storyboardHelpResource(type)));
        EditText startTime = addField(content, getString(R.string.field_start_beat),
                source.startTime.toString(), false);
        EditText endTime = addField(content, getString(R.string.field_end_beat),
                source.endTime.toString(), false);
        addHint(content, getString(R.string.storyboard_time_help));
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
        EditText startValue = addField(content,
                getString(R.string.storyboard_start_value),
                storyboardStartValue(source), numeric);
        EditText endValue = addField(content,
                getString(R.string.storyboard_end_value),
                storyboardEndValue(source), numeric);
        if (type == StoryboardEventType.TEXT) {
            configureStoryboardTextField(startValue);
            configureStoryboardTextField(endValue);
        }
        CheckBox locked = addCheckBox(content, R.string.field_event_locked,
                storyboardValuesEqual(source));
        Spinner easingType = addSpinner(content, R.string.field_event_easing_type,
                R.array.event_easing_entries,
                Math.max(Easing.MIN_TYPE,
                        Math.min(Easing.MAX_TYPE, source.easingType)) - 1);
        TextView previewLabel = new TextView(this);
        previewLabel.setText(R.string.field_event_easing_preview);
        previewLabel.setTextSize(14f);
        previewLabel.setPadding(0, dp(8), 0, dp(3));
        content.addView(previewLabel);
        EasingPreviewView easingPreview = new EasingPreviewView(this);
        easingPreview.setContentDescription(
                getString(R.string.field_event_easing_preview_description));
        content.addView(easingPreview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(148)));
        EditText easingLeft = addField(content, getString(R.string.field_event_easing_left),
                formatNumber(source.easingLeft), true);
        EditText easingRight = addField(content, getString(R.string.field_event_easing_right),
                formatNumber(source.easingRight), true);
        EditText linkGroup = addField(content, getString(R.string.field_event_link_group),
                Integer.toString(source.linkGroup), true);
        CheckBox bezier = addCheckBox(content, R.string.field_event_bezier, source.bezier);
        EditText bezierX1 = addField(content, getString(R.string.field_event_bezier_x1),
                formatNumber(source.bezierPoints[0]), true);
        EditText bezierY1 = addField(content, getString(R.string.field_event_bezier_y1),
                formatNumber(source.bezierPoints[1]), true);
        EditText bezierX2 = addField(content, getString(R.string.field_event_bezier_x2),
                formatNumber(source.bezierPoints[2]), true);
        EditText bezierY2 = addField(content, getString(R.string.field_event_bezier_y2),
                formatNumber(source.bezierPoints[3]), true);

        Runnable refreshEasing = () -> updateEasingEditor(
                false, easingType, easingLeft, easingRight, bezier,
                bezierX1, bezierY1, bezierX2, bezierY2, easingPreview);
        setSpinnerChangeListener(easingType, refreshEasing);
        bezier.setOnCheckedChangeListener((button, checked) -> refreshEasing.run());
        watch(refreshEasing, easingLeft, easingRight,
                bezierX1, bezierY1, bezierX2, bezierY2);
        locked.setOnCheckedChangeListener((button, checked) -> endValue.setEnabled(!checked));
        endValue.setEnabled(!locked.isChecked());
        refreshEasing.run();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(creating ? R.string.storyboard_new_title
                        : R.string.storyboard_edit_title, storyboardTypeLabel(type)))
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
                                      EditText startValue, EditText endValue,
                                      boolean locked) {
        if (event instanceof ExtendedLineEvents.NumericEvent) {
            ExtendedLineEvents.NumericEvent numeric =
                    (ExtendedLineEvents.NumericEvent) event;
            numeric.start = parseDouble(startValue);
            numeric.end = locked ? numeric.start : parseDouble(endValue);
        } else if (event instanceof ExtendedLineEvents.ColorEvent) {
            ExtendedLineEvents.ColorEvent color = (ExtendedLineEvents.ColorEvent) event;
            color.startRgb = parseRgb(startValue.getText().toString());
            color.endRgb = locked ? color.startRgb
                    : parseRgb(endValue.getText().toString());
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
