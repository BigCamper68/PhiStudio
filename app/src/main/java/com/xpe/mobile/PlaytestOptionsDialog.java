package com.xpe.mobile;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.playtest.PlaytestOptions;
import java.util.Locale;
import java.util.function.DoubleConsumer;

/** Native, scrollable playtest setup. Selecting an option immediately remembers it. */
final class PlaytestOptionsDialog {
    private PlaytestOptionsDialog() { }

    static AlertDialog show(MainActivity activity, ChartDocument chart, double playheadSeconds,
                            double playheadBeat, DoubleConsumer start) {
        SharedPreferences prefs = activity.getPreferences(MainActivity.MODE_PRIVATE);
        PlaytestOptions options = PlaytestOptions.load(prefs);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout content = activity.mainMenuUi.createDialogContent(scroll);
        title(activity, content, R.string.playtest_start_position);
        RadioGroup positions = new RadioGroup(activity);
        RadioButton beginning = radio(activity, positions, activity.getString(R.string.playtest_from_beginning), "start.beginning");
        RadioButton playhead = radio(activity, positions, activity.getString(R.string.playtest_from_playhead,
                String.format(Locale.US, "%.3f", playheadBeat).replaceAll("\\.?0+$", "")), "start.playhead");
        RadioButton custom = radio(activity, positions, activity.getString(R.string.playtest_custom_position), "start.custom");
        content.addView(positions);
        LinearLayout customContent = new LinearLayout(activity);
        customContent.setOrientation(LinearLayout.VERTICAL);
        customContent.setPadding(activity.dp(12), 0, 0, 0);
        content.addView(customContent);
        RadioGroup units = new RadioGroup(activity);
        units.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton time = radio(activity, units, activity.getString(R.string.playtest_time), "custom.time");
        RadioButton beat = radio(activity, units, activity.getString(R.string.playtest_beat), "custom.beat");
        customContent.addView(units);
        EditText timeField = activity.mainMenuUi.addField(customContent, activity.getString(R.string.playtest_time_hint), options.time, false);
        timeField.setTag("playtest.time");
        EditText beatField = activity.mainMenuUi.addField(customContent, activity.getString(R.string.playtest_beat_hint), options.beat, true);
        beatField.setTag("playtest.beat");
        View timeLabel = customContent.getChildAt(1), beatLabel = customContent.getChildAt(3);
        Runnable refresh = () -> {
            customContent.setVisibility(options.start == PlaytestOptions.CUSTOM ? View.VISIBLE : View.GONE);
            timeLabel.setVisibility(options.customBeat ? View.GONE : View.VISIBLE);
            timeField.setVisibility(options.customBeat ? View.GONE : View.VISIBLE);
            beatLabel.setVisibility(options.customBeat ? View.VISIBLE : View.GONE);
            beatField.setVisibility(options.customBeat ? View.VISIBLE : View.GONE);
        };
        positions.check(options.start == PlaytestOptions.BEGINNING ? beginning.getId()
                : options.start == PlaytestOptions.PLAYHEAD ? playhead.getId() : custom.getId());
        units.check(options.customBeat ? beat.getId() : time.getId());
        positions.setOnCheckedChangeListener((group, id) -> {
            options.start = id == beginning.getId() ? PlaytestOptions.BEGINNING
                    : id == playhead.getId() ? PlaytestOptions.PLAYHEAD : PlaytestOptions.CUSTOM;
            options.save(prefs); refresh.run();
        });
        units.setOnCheckedChangeListener((group, id) -> {
            options.customBeat = id == beat.getId(); options.save(prefs); refresh.run();
        });
        watch(timeField, () -> { options.time = timeField.getText().toString(); options.save(prefs); });
        watch(beatField, () -> { options.beat = beatField.getText().toString(); options.save(prefs); });
        title(activity, content, R.string.playtest_countdown);
        RadioGroup countdown = new RadioGroup(activity);
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        float availableDp = metrics.widthPixels / metrics.density * .78f - 36;
        countdown.setOrientation(availableDp / activity.getResources().getConfiguration().fontScale >= 400
                ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        for (int seconds : new int[]{0, 2, 3, 5}) {
            RadioButton button = radio(activity, countdown, seconds == 0 ? activity.getString(R.string.playtest_off)
                    : activity.getResources().getQuantityString(R.plurals.playtest_seconds, seconds, seconds), "countdown." + seconds);
            if (options.countdown == seconds) countdown.check(button.getId());
        }
        countdown.setOnCheckedChangeListener((group, id) -> {
            RadioButton button = group.findViewById(id);
            options.countdown = Integer.parseInt(button.getTag().toString().substring("countdown.".length()));
            options.save(prefs);
        });
        content.addView(countdown);
        refresh.run();
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(R.string.menu_playtest).setView(scroll)
                .setNegativeButton(R.string.playtest_cancel, null).setPositiveButton(R.string.playtest_start, null).create();
        activity.mainMenuUi.showEditorWindow(dialog, () -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                try {
                    double seconds = options.seconds(chart, playheadSeconds);
                    if (options.start == PlaytestOptions.CUSTOM && seconds > activity.mainPlaytest.maximumStartSeconds(chart))
                        throw new IllegalArgumentException(activity.getString(R.string.playtest_after_end));
                    if (activity.editorView.getChart() != chart) { dialog.dismiss(); return; }
                    options.save(prefs); dialog.dismiss(); start.accept(seconds);
                } catch (IllegalArgumentException e) {
                    EditText field = options.customBeat ? beatField : timeField;
                    field.setError(e.getMessage()); field.requestFocus();
                }
            });
        }, .78f, .9f);
        return dialog;
    }

    private static void title(MainActivity activity, LinearLayout parent, int resource) {
        TextView text = new TextView(activity); text.setText(resource); text.setTextSize(16);
        text.setPadding(0, activity.dp(8), 0, activity.dp(4)); parent.addView(text);
    }
    private static RadioButton radio(MainActivity activity, RadioGroup parent, String label, String tag) {
        RadioButton button = new RadioButton(activity); button.setId(View.generateViewId());
        button.setText(label); button.setTextSize(14); button.setTag(tag);
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) {
            button.setTextSize(12);
            parent.addView(button, new RadioGroup.LayoutParams(0, -2, 1));
        } else parent.addView(button, new RadioGroup.LayoutParams(-1, -2));
        return button;
    }
    private static void watch(EditText field, Runnable changed) {
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { changed.run(); }
            public void afterTextChanged(Editable e) { }
        });
    }
}
