package com.xpe.mobile.app;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.view.View;
import android.widget.*;
import com.xpe.mobile.R;
import com.xpe.mobile.i18n.UiDialogs;
import com.xpe.mobile.i18n.UiErrors;
import com.xpe.mobile.i18n.UiText;
import com.xpe.mobile.video.VideoExportOptions;
import java.util.Locale;
import java.util.function.Consumer;

/** Scrollable two-column form sized for the landscape editor. */
final class VideoExportDialog {
    private final MainActivity activity;
    private final SharedPreferences preferences;
    private final boolean hasMusic;
    private final long durationMs;
    private final long minimumStartMs;
    private EditText width, height, bitrate, start, end;
    private Spinner fps;
    private CheckBox music, hits, shaders, hud;
    private SeekBar musicVolume, hitVolume;
    private TextView estimate, error;
    private AlertDialog activeDialog;

    void relocalizeUi() {
        if (activeDialog == null || !activeDialog.isShowing()) return;
        activeDialog.setTitle(activity.getText(R.string.menu_export_video));
        activeDialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(activity.getText(R.string.video_render));
        activeDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(activity.getText(R.string.action_cancel));
        UiText.relocalize(activeDialog.getWindow().getDecorView());
    }

    VideoExportDialog(MainActivity activity, boolean hasMusic, long durationMs) {
        this(activity, hasMusic, durationMs, 0);
    }

    VideoExportDialog(MainActivity activity, boolean hasMusic, long durationMs, long minimumStartMs) {
        this.activity = activity; this.hasMusic = hasMusic; this.durationMs = durationMs;
        this.minimumStartMs = Math.min(0, minimumStartMs);
        preferences = activity.getSharedPreferences("video_export", MainActivity.MODE_PRIVATE);
    }

    void show(Consumer<VideoExportOptions> render) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setClipChildren(true); scroll.setClipToPadding(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout content = activity.mainMenuUi.createDialogContent(scroll);
        content.setFocusableInTouchMode(true);
        text(content, R.string.video_export_description);
        LinearLayout columns = new LinearLayout(activity);
        content.addView(columns, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout left = column(columns), right = column(columns);
        left.setPadding(0, 0, activity.dp(12), 0);
        right.setPadding(activity.dp(12), 0, 0, 0);
        text(left, R.string.video_section_video);
        Spinner preset = spinner(left, R.string.video_resolution,
                activity.getResources().getStringArray(R.array.video_resolution_presets));
        LinearLayout dimensions = row(left);
        width = field(cell(dimensions), R.string.video_width, Integer.toString(preferences.getInt("width", 1920)));
        height = field(cell(dimensions), R.string.video_height, Integer.toString(preferences.getInt("height", 1080)));
        UiText.spinner(preset, R.array.video_resolution_presets);
        preset.setSelection(4);
        fps = spinner(left, R.string.video_fps, new String[]{"30 FPS", "60 FPS"});
        fps.setSelection(preferences.getInt("fps", 60) == 30 ? 0 : 1);
        bitrate = field(left, R.string.video_bitrate, Float.toString(preferences.getFloat("bitrate", 12f)));
        text(left, R.string.video_bitrate_hint);
        text(left, R.string.video_section_range);
        LinearLayout range = row(left);
        start = field(cell(range), R.string.video_start_seconds, minimumStartMs == 0 ? "0"
                : String.format(Locale.US, "%.3f", minimumStartMs / 1000.0));
        end = field(cell(range), R.string.video_end_seconds, String.format(Locale.US, "%.3f", durationMs / 1000.0));
        text(left, R.string.video_range_hint);
        text(right, R.string.video_section_audio);
        music = check(right, R.string.video_music, hasMusic && preferences.getBoolean("music", true));
        music.setEnabled(hasMusic);
        if (!hasMusic) text(right, R.string.video_no_music);
        musicVolume = volume(right, R.string.video_music_volume, preferences.getInt("musicVolume", 100));
        hits = check(right, R.string.video_hits, preferences.getBoolean("hits", true));
        hitVolume = volume(right, R.string.video_hits_volume, preferences.getInt("hitVolume", 100));
        text(right, R.string.video_section_appearance);
        shaders = check(right, R.string.video_shaders, preferences.getBoolean("shaders", true));
        hud = check(right, R.string.video_hud, preferences.getBoolean("hud", true));
        estimate = text(content, 0); error = text(content, 0);
        error.setTextColor(0xFFFF8A80);
        AlertDialog dialog = new UiDialogs.Builder(activity).setTitle(R.string.menu_export_video)
                .setView(scroll).setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.video_render, null).create();
        activeDialog = dialog;
        preset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int[][] sizes = {{1920,1080}, {1280,720}, {960,540},
                        {activity.editorSettings.playerWidth, activity.editorSettings.playerHeight}};
                if (position < sizes.length) {
                    width.setText(String.format(Locale.ROOT, "%d", sizes[position][0] & ~1));
                    height.setText(String.format(Locale.ROOT, "%d", sizes[position][1] & ~1));
                }
            }
        });
        fps.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { updateEstimate(); }
        });
        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { updateEstimate(); }
            public void afterTextChanged(Editable value) { }
        };
        for (EditText field : new EditText[]{width, height, bitrate, start, end}) field.addTextChangedListener(watcher);
        music.setOnCheckedChangeListener((button, checked) -> { musicVolume.setEnabled(checked); updateEstimate(); });
        hits.setOnCheckedChangeListener((button, checked) -> { hitVolume.setEnabled(checked); updateEstimate(); });
        musicVolume.setEnabled(music.isChecked()); hitVolume.setEnabled(hits.isChecked());
        activity.mainMenuUi.showEditorWindow(dialog, () -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        VideoExportOptions options = options();
                        persist(options);
                        dialog.dismiss(); render.accept(options);
                    } catch (IllegalArgumentException exception) {
                        UiText.text(error, () -> activity.getString(R.string.video_invalid_settings, UiErrors.describe(activity, exception)));
                    }
                }), 0.84f, 0.90f);
        updateEstimate();
    }

    private VideoExportOptions options() {
        try {
            double startSeconds = decimal(start), endSeconds = decimal(end), mbps = decimal(bitrate);
            if (!Double.isFinite(startSeconds) || !Double.isFinite(endSeconds) || !Double.isFinite(mbps)
                    || startSeconds < minimumStartMs / 1000.0 || endSeconds > 86400 || mbps < 1 || mbps > 80)
                throw new UiErrors.ValidationException(R.string.video_numeric_limits);
            return new VideoExportOptions(Integer.parseInt(width.getText().toString().trim()),
                    Integer.parseInt(height.getText().toString().trim()), fps.getSelectedItemPosition() == 0 ? 30 : 60,
                    (int) Math.round(mbps * 1_000_000), Math.round(startSeconds * 1000), Math.round(endSeconds * 1000),
                    music.isChecked(), hits.isChecked(), musicVolume.getProgress() / 100f,
                    hitVolume.getProgress() / 100f, shaders.isChecked(), hud.isChecked());
        } catch (NumberFormatException exception) {
            throw new UiErrors.ValidationException(R.string.video_enter_numbers);
        }
    }
    private static double decimal(EditText field) {
        return Double.parseDouble(field.getText().toString().trim().replace(',', '.'));
    }
    private void updateEstimate() {
        try {
            VideoExportOptions options = options();
            UiText.text(estimate, () -> activity.getString(R.string.video_estimate,
                    options.frameCount(), (options.endMs - options.startMs) / 1000.0,
                    options.estimatedBytes() / (1024.0 * 1024)));
            UiText.text(error, () -> "");
        } catch (IllegalArgumentException ignored) { UiText.text(estimate, R.string.video_estimate_pending); }
    }
    private void persist(VideoExportOptions options) {
        preferences.edit().putInt("width", options.width).putInt("height", options.height)
                .putInt("fps", options.fps).putFloat("bitrate", options.bitrate / 1_000_000f)
                .putBoolean("music", options.music).putBoolean("hits", options.hits)
                .putInt("musicVolume", musicVolume.getProgress()).putInt("hitVolume", hitVolume.getProgress())
                .putBoolean("shaders", options.shaders).putBoolean("hud", options.hud).apply();
    }
    private LinearLayout column(LinearLayout parent) {
        LinearLayout column = new LinearLayout(activity); column.setOrientation(LinearLayout.VERTICAL);
        parent.addView(column, new LinearLayout.LayoutParams(0, -2, 1)); return column;
    }
    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(activity); parent.addView(row); return row;
    }
    private LinearLayout cell(LinearLayout parent) { return column(parent); }
    private TextView text(LinearLayout parent, int resource) {
        TextView text = new TextView(activity); if (resource != 0) UiText.text(text, resource);
        text.setPadding(0, activity.dp(8), 0, activity.dp(4)); parent.addView(text); return text;
    }
    private EditText field(LinearLayout parent, int resource, String value) {
        EditText field = activity.mainMenuUi.addField(parent, resource, value, true);
        field.setKeyListener(DigitsKeyListener.getInstance("0123456789.,-"));
        field.setRawInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return field;
    }
    private Spinner spinner(LinearLayout parent, int resource, String[] values) {
        text(parent, resource);
        Spinner spinner = new VideoExportSpinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); parent.addView(spinner, new LinearLayout.LayoutParams(-1, -2)); return spinner;
    }
    private CheckBox check(LinearLayout parent, int resource, boolean checked) {
        CheckBox check = new CheckBox(activity); UiText.text(check, resource); check.setChecked(checked);
        parent.addView(check); return check;
    }
    private SeekBar volume(LinearLayout parent, int resource, int value) {
        TextView label = text(parent, 0);
        SeekBar bar = new SeekBar(activity); bar.setMax(100); bar.setProgress(Math.max(0, Math.min(100, value)));
        UiText.text(label, () -> activity.getString(resource, bar.getProgress()));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar seekBar) { }
            public void onStopTrackingTouch(SeekBar seekBar) { }
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText(activity.getString(resource, progress));
            }
        });
        parent.addView(bar, new LinearLayout.LayoutParams(-1, activity.dp(36))); return bar;
    }
}
