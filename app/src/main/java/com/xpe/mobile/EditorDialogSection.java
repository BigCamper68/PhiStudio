package com.xpe.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.editor.EasingPreviewView;
import com.xpe.mobile.editor.PropertyValidator;
import com.xpe.mobile.model.Easing;
import com.xpe.mobile.model.EventType;

import java.util.List;
import java.util.Locale;

/** Common, stateless view-building helpers for editor dialog sections. */
abstract class EditorDialogSection extends ContextThemeWrapper {
    protected final EditorView editorView;
    protected final EditorDialogController.Host host;

    protected EditorDialogSection(Activity activity, EditorView editorView,
                                  EditorDialogController.Host host) {
        super(activity, activity.getTheme());
        if (editorView == null || host == null) {
            throw new IllegalArgumentException("Editor view and dialog host are required");
        }
        this.editorView = editorView;
        this.host = host;
    }

    protected void addMenuSection(LinearLayout parent, int textResource) {
        TextView title = new TextView(this);
        title.setText(textResource);
        title.setTextSize(16f);
        title.setPadding(dp(4), dp(4), dp(4), dp(6));
        parent.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    protected Button addMenuButton(LinearLayout parent, int textResource) {
        Button button = new Button(this);
        button.setText(textResource);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinHeight(dp(48));
        button.setBackgroundResource(R.drawable.phistudio_menu_button);
        parent.addView(button, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return button;
    }

    protected void runMenuAction(AlertDialog menu, Runnable action) {
        menu.dismiss();
        action.run();
    }

    protected void showEditorWindow(AlertDialog dialog, Runnable afterShow) {
        showEditorWindow(dialog, afterShow, 0.72f, 0.78f);
    }

    protected void showEditorWindow(AlertDialog dialog, Runnable afterShow,
                                  float widthFraction, float heightFraction) {
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                int width = getResources().getDisplayMetrics().widthPixels;
                int height = getResources().getDisplayMetrics().heightPixels;
                window.setLayout((int) (width * widthFraction),
                        (int) (height * heightFraction));
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            if (afterShow != null) afterShow.run();
        });
        dialog.show();
    }

    protected LinearLayout createDialogContent(ScrollView scrollView) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        content.setPadding(pad, pad, pad, pad);
        scrollView.addView(content);
        return content;
    }

    protected TextView addHint(LinearLayout parent, String text) {
        TextView hint = new TextView(this);
        hint.setText(text);
        hint.setTextSize(12f);
        hint.setPadding(0, dp(4), 0, dp(5));
        parent.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return hint;
    }

    protected Spinner addStringSpinner(LinearLayout parent, String label,
                                     List<String> entries, int selected) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(14f);
        title.setPadding(0, dp(8), 0, dp(3));
        parent.addView(title);

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, entries);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(selected, adapter.getCount() - 1)));
        parent.addView(spinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return spinner;
    }

    protected static void setSpinnerChangeListener(Spinner spinner, Runnable action) {
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                action.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                action.run();
            }
        });
    }

    protected static void watch(Runnable action, EditText... fields) {
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                action.run();
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        };
        for (EditText field : fields) field.addTextChangedListener(watcher);
    }

    protected Spinner addSpinner(LinearLayout parent, int labelResource, int entriesResource, int selected) {
        TextView title = new TextView(this);
        title.setText(labelResource);
        title.setTextSize(14f);
        title.setPadding(0, dp(8), 0, dp(3));
        parent.addView(title);

        Spinner spinner = new Spinner(this);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, entriesResource, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(selected, adapter.getCount() - 1)));
        parent.addView(spinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return spinner;
    }

    protected CheckBox addCheckBox(LinearLayout parent, int textResource, boolean checked) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(textResource);
        checkBox.setChecked(checked);
        checkBox.setPadding(0, dp(6), 0, dp(2));
        parent.addView(checkBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return checkBox;
    }

    protected Button addActionButton(LinearLayout parent, int textResource) {
        Button button = new Button(this);
        button.setText(textResource);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        parent.addView(button, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        return button;
    }

    protected void setCompactButtons(LinearLayout row, Button... buttons) {
        int windowWidth = Math.round(
                getResources().getDisplayMetrics().widthPixels * 0.84f);
        int sidePadding = Math.round(windowWidth * 0.15f);
        row.setPadding(sidePadding, 0, sidePadding, 0);
        for (Button button : buttons) {
            if (button == null) continue;
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setMinHeight(0);
            button.setMinimumHeight(0);
            button.setTextSize(11.5f);
            button.setPadding(dp(3), 0, dp(3), 0);
            android.view.ViewGroup.LayoutParams params = button.getLayoutParams();
            if (params != null) {
                params.height = dp(34);
                button.setLayoutParams(params);
            }
        }
    }

    protected void showBpmApplyError(EditorView.BpmApplyResult result) {
        int message;
        switch (result) {
            case DUPLICATE_START_TIME: message = R.string.bpm_validation_duplicate; break;
            case FIRST_ENTRY_LOCKED: message = R.string.bpm_validation_first_locked; break;
            case LAST_ENTRY_REQUIRED: message = R.string.bpm_validation_last_required; break;
            case TARGET_NOT_FOUND: message = R.string.validation_target_changed; break;
            default: message = R.string.bpm_validation_invalid; break;
        }
        showMessage(getString(message));
    }

    protected static String compactBpm(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.000001) return Long.toString(Math.round(value));
        return String.format(Locale.US, "%.6f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    protected void showValidationError(PropertyValidator.Error error) {
        int message;
        switch (error) {
            case MISSING_TYPE: message = R.string.validation_missing_type; break;
            case NON_FINITE_NUMBER: message = R.string.validation_non_finite; break;
            case NEGATIVE_START_TIME: message = R.string.validation_negative_start; break;
            case END_TIME_NOT_AFTER_START: message = R.string.validation_end_after_start; break;
            case NOTE_X_OUT_OF_RANGE: message = R.string.validation_note_x; break;
            case NOTE_ALPHA_OUT_OF_RANGE: message = R.string.validation_note_alpha; break;
            case NOTE_SIZE_NOT_POSITIVE: message = R.string.validation_note_size; break;
            case NOTE_VISIBLE_TIME_NEGATIVE: message = R.string.validation_note_visible_time; break;
            case EVENT_ALPHA_OUT_OF_RANGE: message = R.string.validation_event_alpha; break;
            case EVENT_EASING_OUT_OF_RANGE: message = R.string.validation_event_easing; break;
            case EVENT_EASING_WINDOW_INVALID: message = R.string.validation_event_easing_window; break;
            case EVENT_LINK_GROUP_NEGATIVE: message = R.string.validation_event_link_group; break;
            default: message = R.string.validation_invalid_number_or_beat; break;
        }
        showMessage(getString(message));
    }

    protected void showApplyError(EditorView.PropertyApplyResult result) {
        int message = result == EditorView.PropertyApplyResult.EVENT_OVERLAP
                ? R.string.validation_event_overlap
                : result == EditorView.PropertyApplyResult.TARGET_NOT_FOUND
                ? R.string.validation_target_changed
                : result == EditorView.PropertyApplyResult.XY_BINDING_INVALID
                ? R.string.validation_xy_binding_pair
                : R.string.validation_invalid_number_or_beat;
        showMessage(getString(message));
    }

    protected String eventTypeLabel(EventType type) {
        switch (type) {
            case MOVE_X: return getString(R.string.event_type_move_x);
            case MOVE_Y: return getString(R.string.event_type_move_y);
            case ROTATE: return getString(R.string.event_type_rotate);
            case ALPHA: return getString(R.string.event_type_alpha);
            case SPEED: return getString(R.string.event_type_speed);
            default: return type.name();
        }
    }

    protected static double parseDouble(EditText field) {
        double value = Double.parseDouble(field.getText().toString().trim());
        if (!Double.isFinite(value)) throw new NumberFormatException("number must be finite");
        return value;
    }

    protected static int parseInteger(EditText field) {
        return Integer.parseInt(field.getText().toString().trim());
    }

    protected static int parseRgb(String text) {
        if (text == null) throw new NumberFormatException("RGB color is required");
        String value = text.trim();
        if (value.startsWith("#")) value = value.substring(1);
        if (!value.matches("[0-9A-Fa-f]{6}")) {
            throw new NumberFormatException("RGB color must use #RRGGBB");
        }
        return Integer.parseInt(value, 16);
    }

    protected static String formatNumber(double value) {
        return Double.toString(value);
    }

    protected EditText addField(LinearLayout parent, String label, String value, boolean numeric) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(14f);
        title.setPadding(0, dp(8), 0, dp(3));
        parent.addView(title);

        EditText field = new EditText(this);
        field.setText(value);
        field.setSingleLine(true);
        field.setSelectAllOnFocus(true);
        field.setGravity(Gravity.CENTER_VERTICAL);
        if (numeric) {
            field.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | InputType.TYPE_NUMBER_FLAG_SIGNED);
        }
        parent.addView(field, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return field;
    }

    protected int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    protected static String nonEmpty(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    protected void updateEasingEditor(boolean speedEvent, Spinner easingType,
                                      EditText easingLeft, EditText easingRight,
                                      CheckBox bezier, EditText bezierX1, EditText bezierY1,
                                      EditText bezierX2, EditText bezierY2,
                                      EasingPreviewView preview) {
        boolean customBezier = !speedEvent && bezier.isChecked();
        easingType.setEnabled(!speedEvent && !customBezier);
        easingLeft.setEnabled(!speedEvent);
        easingRight.setEnabled(!speedEvent);
        bezier.setEnabled(!speedEvent);
        bezierX1.setEnabled(customBezier);
        bezierY1.setEnabled(customBezier);
        bezierX2.setEnabled(customBezier);
        bezierY2.setEnabled(customBezier);

        int type = speedEvent ? Easing.MIN_TYPE
                : easingType.getSelectedItemPosition() + Easing.MIN_TYPE;
        preview.setCurve(type,
                previewNumber(easingLeft, 0.0), previewNumber(easingRight, 1.0),
                customBezier,
                previewNumber(bezierX1, 0.0), previewNumber(bezierY1, 0.0),
                previewNumber(bezierX2, 0.0), previewNumber(bezierY2, 0.0));
    }

    private static double previewNumber(EditText field, double fallback) {
        try {
            double value = Double.parseDouble(field.getText().toString().trim());
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    protected void showMessage(String message) {
        host.showMessage(message);
    }
}
