package com.xpe.mobile;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import com.xpe.mobile.editor.EditorTimelineMetrics;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.preview.PreviewShaderView;
import com.xpe.mobile.preview.ShaderTween;
import com.xpe.mobile.shadereditor.ShaderDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A dedicated landscape editor: Preview left, vertical effect/event lanes
 * right, forms below.
 */
@SuppressLint("ViewConstructor")
final class ShaderEditorView extends ViewGroup {
    interface Host {
        void changed();
        void importShader();
        void save();
    }
    final ShaderDocument document;
    private final EditorView editor;
    private final Host host;
    private final PreviewPane preview;
    private final PreviewShaderView gpu;
    final Timeline timeline;
    private final LinearLayout toolbar, bottom, form;
    private final View playbackSeek;
    private final Button expand;
    private final EditorListScrollView scroll;
    private final TextView status;
    private final Button play, undo, redo;
    private Button units;
    private String diagnostic;
    private final CheckBox loop;
    private boolean beats = true, collapsed, expanded;
    private int page;
    private String parameter;
    private int eventIndex;
    private final float density;
    private static final int[] CURVE_COLORS = {0xffffb74d, 0xff81c784, 0xff64b5f6, 0xfff5f5f5};

    ShaderEditorView(Context context, EditorView editor, ShaderDocument document, Host host) {
        super(context);
        this.editor = editor;
        this.document = document;
        this.host = host;
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.rgb(13, 18, 24));
        toolbar = row();
        addView(toolbar);
        toolbar.setBackgroundColor(Color.rgb(24, 30, 37));
        toolbar.setPadding(dp(6), 0, 0, 0);
        HorizontalScrollView toolbarScroll = new HorizontalScrollView(context);
        toolbarScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout controls = row();
        toolbarScroll.addView(controls, new ViewGroup.LayoutParams(-2, -1));
        toolbar.addView(toolbarScroll, new LinearLayout.LayoutParams(0, -1, 1));
        play = toolbarAction(controls, "Play", 58, this::togglePlayback);
        loop = new CheckBox(context);
        loop.setText(R.string.shader_loop);
        loop.setTextSize(10.5f);
        controls.addView(loop, new LinearLayout.LayoutParams(dp(64), dp(38)));
        loop.setOnCheckedChangeListener((v, value) -> invalidatePreview());
        units = toolbarAction(controls, "FX beats", 64, this::toggleUnits);
        toolbarAction(controls, "+ Effect", 64, this::addEffect);
        toolbarAction(controls, "Import GLSL", 78, host::importShader);
        undo = toolbarAction(controls, "Undo", 52, () -> {
            document.undo();
            changed(true);
        });
        redo = toolbarAction(controls, "Redo", 52, () -> {
            document.redo();
            changed(true);
        });
        toolbarAction(controls, "Save", 52, host::save);
        playbackSeek = new View(context) {
            @Override
            protected void onDraw(Canvas canvas) {
                editor.drawShaderPlaybackSeek(canvas, dp(10), getWidth() - dp(10));
            }
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                getParent().requestDisallowInterceptTouchEvent(true);
                editor.shaderPlaybackSeekTouch(event.getActionMasked(), event.getX(), event.getY());
                invalidatePreview();
                if (event.getActionMasked() == MotionEvent.ACTION_UP)
                    performClick();
                return true;
            }
            @Override
            public boolean performClick() {
                super.performClick();
                return true;
            }
        };
        playbackSeek.setContentDescription("Audio seek");
        toolbar.addView(playbackSeek, new LinearLayout.LayoutParams(dp(210), -1));
        status = text("Shaders");
        status.setPadding(dp(8), 0, 0, 0);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        preview = new PreviewPane(context);
        gpu = new PreviewShaderView(context);
        gpu.setOnTouchListener((v, e) -> {
            boolean handled = preview.panTouch(e);
            if (e.getActionMasked() == MotionEvent.ACTION_UP) v.performClick();
            return handled;
        });
        gpu.setFrameListener(preview::invalidate);
        gpu.setErrorListener(this::error);
        timeline = new Timeline(context);
        addView(preview);
        addView(gpu);
        addView(timeline);
        bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(Color.rgb(26, 33, 42));
        addView(bottom);
        LinearLayout pages = row();
        bottom.addView(pages, new LinearLayout.LayoutParams(-1, dp(36)));
        action(pages, "Effect", () -> {
            page = 0;
            openProperties();
            timeline.invalidate();
            buildForm();
        });
        action(pages, "Parameters", () -> {
            page = 1;
            openProperties();
            timeline.invalidate();
            buildForm();
        });
        action(pages, "Animation", () -> {
            page = 2;
            openProperties();
            timeline.invalidate();
            buildForm();
        });
        action(pages, "FX BPM", () -> {
            page = 3;
            openProperties();
            timeline.invalidate();
            buildForm();
        });
        action(pages, "≡ Effects", this::effectList);
        pages.addView(status, new LinearLayout.LayoutParams(0, -1, 1));
        expand = action(pages, "Expand", () -> {
            expanded = !expanded;
            collapsed = false;
            expandLabel();
            requestLayout();
        });
        action(pages, "▼ / ▲", () -> {
            collapsed = !collapsed;
            requestLayout();
        });
        scroll = new EditorListScrollView(context);
        bottom.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), 0, dp(8), dp(8));
        scroll.addView(form);
        if (document.count() > 0)
            document.selected = 0;
        buildForm();
    }
    int dp(float value) {
        return Math.round(value * density);
    }
    private void toggleUnits() {
        beats = !beats;
        units.setText(beats ? "FX beats" : "Seconds");
        timeline.invalidate();
        buildForm();
    }
    private void togglePlayback() {
        if (!editor.isPlaying() && loop.isChecked() && document.selected() != null) {
            double seconds = editor.shaderEditorSeconds();
            if (seconds < document.start(document.selected)
                || seconds >= document.end(document.selected))
                editor.shaderEditorSeek(Math.max(0, document.start(document.selected)));
        }
        editor.shaderEditorPlay();
        invalidatePreview();
    }
    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }
    private TextView text(String label) {
        TextView view = new TextView(getContext());
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(12);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private Button action(LinearLayout parent, String label, Runnable callback) {
        Button view = WorkbenchView.button(getContext(), label);
        view.setTextSize(11);
        view.setSingleLine(true);
        parent.addView(view, new LinearLayout.LayoutParams(-2, dp(32)));
        view.setOnClickListener(v -> safe(callback));
        return view;
    }
    private void openProperties() {
        collapsed = false;
        scroll.scrollTo(0, 0);
        requestLayout();
    }
    private void expandLabel() {
        expand.setText(expanded ? "Compact" : "Expand");
    }
    private Button toolbarAction(LinearLayout parent, String label, int width, Runnable callback) {
        Button view = action(parent, label, callback);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 10.5f * density);
        view.setPadding(0, 0, 0, 0);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setBackground(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        view.setBackgroundTintList(new android.content.res.ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {Color.rgb(39, 45, 52), Color.rgb(53, 63, 74)}));
        view.setTextColor(new android.content.res.ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {Color.rgb(125, 134, 142), Color.WHITE}));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(width), dp(38));
        lp.rightMargin = dp(4);
        lp.gravity = Gravity.CENTER_VERTICAL;
        view.setLayoutParams(lp);
        return view;
    }
    private void safe(Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            error(ex.getMessage());
        }
    }
    void error(String message) {
        diagnostic = message;
        status.setText(
            getResources().getString(R.string.shader_error, message == null ? "error" : message));
        status.setTextColor(0xffffab91);
    }
    void invalidatePreview() {
        preview.invalidate();
        timeline.invalidate();
        playbackSeek.invalidate();
    }
    void imported() {
        page = 0;
        parameter = null;
        eventIndex = 0;
        timeline.reveal(document.selected);
        changed(true);
    }
    void onShown() {
        setVisibility(VISIBLE);
        invalidatePreview();
    }
    void onHidden() {
        timeline.cancel();
        preview.cancelPan();
        editor.shaderPlaybackSeekTouch(MotionEvent.ACTION_CANCEL, 0, 0);
        host.changed();
        gpu.hidePreview();
        setVisibility(GONE);
    }
    private void changed(boolean rebuild) {
        diagnostic = null;
        host.changed();
        undo.setEnabled(document.canUndo());
        redo.setEnabled(document.canRedo());
        timeline.invalidate();
        preview.invalidate();
        if (rebuild)
            buildForm();
    }
    private void select(int index, boolean seek) {
        document.selected = index;
        eventIndex = 0;
        parameter = null;
        if (seek) timeline.reveal(index);
        if (seek)
            editor.shaderEditorSeek(document.start(index));
        buildForm();
        invalidatePreview();
    }
    private void addEffect() {
        List<String> names = document.shaderNames();
        new AlertDialog.Builder(getContext())
            .setTitle("Add effect")
            .setItems(names.toArray(new String[0]), (d, i) -> safe(() -> {
                document.add(names.get(i), editor.shaderEditorSeconds());
                parameter = null;
                page = 0;
                timeline.reveal(document.selected);
                changed(true);
            }))
            .setNegativeButton("Cancel", null)
            .show();
    }
    private void effectList() {
        String[] names = new String[document.count()];
        for (int i = 0; i < names.length; i++)
            names[i] = "#" + (i + 1) + " · " + document.effect(i).optString("shader") + " · "
                + fmt(document.start(i)) + "s";
        new AlertDialog.Builder(getContext())
            .setTitle("Effects in application order")
            .setItems(names, (d, i) -> select(i, true))
            .setNegativeButton("Cancel", null)
            .show();
    }
    private static String fmt(double value) {
        return String.format(Locale.US, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
    private EditText field(LinearLayout row, String label, String initial) {
        row.addView(text(label), new LinearLayout.LayoutParams(-2, dp(36)));
        EditText value = new EditText(getContext());
        value.setText(initial);
        value.setTextColor(Color.WHITE);
        value.setTextSize(12);
        value.setSingleLine(true);
        value.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
            | InputType.TYPE_NUMBER_FLAG_SIGNED);
        value.setSelectAllOnFocus(true);
        row.addView(value, new LinearLayout.LayoutParams(0, dp(40), 1));
        return value;
    }
    private double number(EditText value) {
        double result = Double.parseDouble(value.getText().toString().trim());
        if (!Double.isFinite(result))
            throw new IllegalArgumentException("Enter a finite value");
        return result;
    }
    private double time(EditText field) {
        double value =
            beats ? BeatTime.parseFlexible(field.getText().toString()).toDouble() : number(field);
        return document.seconds(value, beats);
    }
    private void buildForm() {
        form.removeAllViews();
        undo.setEnabled(document.canUndo());
        redo.setEnabled(document.canRedo());
        if (page == 3) {
            bpmForm();
            return;
        }
        JSONObject selected = document.selected();
        if (selected == null) {
            form.addView(text("Add a preset effect or import a GLSL file. Drag empty timeline to "
                + "seek; pinch to zoom."));
            return;
        }
        if (page == 0) {
            effectForm();
            return;
        }
        List<ShaderDocument.Parameter> params = document.parameters();
        if (params.isEmpty()) {
            form.addView(text("This shader exposes no editable float / vec2 / color parameters."));
            return;
        }
        if (parameter == null || params.stream().noneMatch(p -> p.name.equals(parameter)))
            parameter = params.get(0).name;
        Spinner choice = new Spinner(getContext());
        List<String> labels = new ArrayList<>();
        for (ShaderDocument.Parameter param : params) labels.add(param.name);
        choice.setAdapter(new ArrayAdapter<>(
            getContext(), android.R.layout.simple_spinner_dropdown_item, labels));
        choice.setSelection(labels.indexOf(parameter));
        LinearLayout primary = row();
        form.addView(primary);
        primary.addView(choice, new LinearLayout.LayoutParams(dp(160), dp(40)));
        choice.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> v) {}
            public void onItemSelected(
                android.widget.AdapterView<?> v, View child, int position, long id) {
                String name = labels.get(position);
                if (!name.equals(parameter)) {
                    parameter = name;
                    eventIndex = 0;
                    buildForm();
                    timeline.invalidate();
                }
            }
        });
        ShaderDocument.Parameter selectedParam =
            params.stream().filter(p -> p.name.equals(parameter)).findFirst().get();
        if (page == 1)
            parameterForm(selectedParam, primary);
        else
            animationForm(selectedParam, primary);
    }
    private void effectForm() {
        JSONObject effect = document.selected();
        LinearLayout times = row();
        form.addView(times);
        TextView identity =
            text("#" + (document.selected + 1) + " · " + effect.optString("shader"));
        identity.setSingleLine(true);
        identity.setEllipsize(android.text.TextUtils.TruncateAt.END);
        times.addView(identity, new LinearLayout.LayoutParams(dp(130), dp(40)));
        CheckBox global = new CheckBox(getContext());
        global.setText(R.string.shader_global);
        global.setTextSize(12);
        global.setChecked(effect.optBoolean("global"));
        times.addView(global);
        global.setOnCheckedChangeListener((v, on) -> safe(() -> {
            document.global(on);
            changed(false);
        }));
        EditText start = field(
                     times, "Start ", fmt(document.axis(document.start(document.selected), beats))),
                 end = field(
                     times, "End ", fmt(document.axis(document.end(document.selected), beats)));
        action(times, "Apply", () -> {
            document.range(document.selected, time(start), time(end));
            changed(true);
            timeline.invalidate();
        });
        LinearLayout tools = row();
        form.addView(tools);
        action(tools, "Jump to start", () -> {
            editor.shaderEditorSeek(document.start(document.selected));
            invalidatePreview();
        });
        action(tools, "Copy", () -> {
            document.duplicate();
            changed(true);
        });
        action(tools, "Delete",
            ()
                -> new AlertDialog.Builder(getContext())
                    .setMessage("Delete this effect?")
                    .setPositiveButton("Delete",
                        (d, w) -> {
                            document.delete();
                            parameter = null;
                            changed(true);
                        })
                    .setNegativeButton("Cancel", null)
                    .show());
        action(tools, "Earlier", () -> {
            document.reorder(-1);
            changed(true);
        });
        action(tools, "Later", () -> {
            document.reorder(1);
            changed(true);
        });
        form.addView(text("Local: game viewport before particles/HUD. Global: complete Preview "
            + "after HUD. Select an event; drag S / E handles to change its range."));
    }
    private EditText[] values(LinearLayout parent, Object value, int size, String prefix) {
        double[] values = document.components(value, size);
        EditText[] fields = new EditText[size];
        String[] suffix = size == 2 ? new String[] {"X", "Y"} : new String[] {"R", "G", "B", "A"};
        for (int i = 0; i < size; i++)
            fields[i] = field(parent, prefix + (size == 1 ? "" : suffix[i]) + " ", fmt(values[i]));
        return fields;
    }
    private Object value(EditText[] fields, int size) {
        double[] values = new double[size];
        for (int i = 0; i < size; i++) values[i] = number(fields[i]);
        return document.valueFrom(values, size);
    }
    private void parameterForm(ShaderDocument.Parameter param, LinearLayout inputs) {
        Object value = document.value(param);
        LinearLayout header = row();
        form.addView(header);
        action(header, "Animate", () -> {
            parameter = param.name;
            if (!document.animated(document.value(param)))
                document.value(param.name, document.animate(param));
            page = 2;
            openProperties();
            eventIndex = 0;
            changed(true);
        });
        action(header, "Reset", () -> {
            document.value(param.name, null);
            changed(true);
        });
        if (document.animated(value)) {
            inputs.addView(text("Animated"));
            action(inputs, "Edit animation", () -> {
                page = 2;
                openProperties();
                buildForm();
                timeline.invalidate();
            });
            return;
        }
        if (param.size == 4) {
            ColorPickerButton color = ColorPickerButton.addTo(inputs, param.name, rgbaColor(value), true);
            color.setOnColorChanged(v -> { document.value(param.name, colorValue(v)); changed(true); });
            return;
        }
        EditText[] fields = values(inputs, value, param.size, "");
        action(inputs, "Apply", () -> {
            document.value(param.name, value(fields, param.size));
            changed(true);
        });
        double[] initial = document.components(value, param.size);
        LinearLayout sliders = header;
        for (int i = 0; i < param.size; i++) {
            final int component = i;
            double original = initial[i];
            double minimum = param.size == 4
                ? 0
                : Math.min(0, original - Math.max(1, Math.abs(original))),
                   maximum = param.size == 4
                ? 255
                : Math.max(1, original + Math.max(1, Math.abs(original)));
            SeekBar slider = new SeekBar(getContext());
            slider.setMax(param.size == 4 ? 255 : 1000);
            slider.setProgress(
                (int) Math.round((original - minimum) / (maximum - minimum) * slider.getMax()));
            sliders.addView(slider, new LinearLayout.LayoutParams(0, dp(28), 1));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(SeekBar v) {
                    document.beginGesture();
                }
                public void onStopTrackingTouch(SeekBar v) {
                    document.endGesture();
                    changed(false);
                }
                public void onProgressChanged(SeekBar v, int progress, boolean user) {
                    if (!user)
                        return;
                    safe(() -> {
                        double[] current = document.components(document.value(param), param.size);
                        current[component] = minimum + (maximum - minimum) * progress / v.getMax();
                        if (param.size == 4)
                            current[component] = Math.round(current[component]);
                        document.value(param.name, document.valueFrom(current, param.size));
                        fields[component].setText(fmt(current[component]));
                        changed(false);
                    });
                }
            });
        }
    }
    private int rgbaColor(Object value) {
        double[] rgba = document.components(value, 4);
        return Color.argb((int) rgba[3], (int) rgba[0], (int) rgba[1], (int) rgba[2]);
    }
    private JSONArray colorValue(int color) {
        return new JSONArray().put(Color.red(color)).put(Color.green(color))
                .put(Color.blue(color)).put(Color.alpha(color));
    }
    private void animationForm(ShaderDocument.Parameter param, LinearLayout navigation) {
        Object value = document.value(param);
        if (!document.animated(value)) {
            action(navigation, "Create animation", () -> {
                document.value(param.name, document.animate(param));
                eventIndex = 0;
                changed(true);
            });
            return;
        }
        JSONArray events = (JSONArray) value;
        if (eventIndex < 0) {
            navigation.addView(text("Tap an animation event to select it"));
            return;
        }
        eventIndex = Math.max(0, Math.min(eventIndex, events.length() - 1));
        JSONObject event = events.optJSONObject(eventIndex);
        navigation.addView(text("Event " + (eventIndex + 1) + " / " + events.length()),
            new LinearLayout.LayoutParams(0, -2, 1));
        action(navigation, "‹", () -> {
            eventIndex = Math.max(0, eventIndex - 1);
            buildForm();
            timeline.invalidate();
        });
        action(navigation, "›", () -> {
            eventIndex = Math.min(events.length() - 1, eventIndex + 1);
            buildForm();
            timeline.invalidate();
        });
        action(navigation, "+ Event", () -> {
            try {
                JSONArray next = new JSONArray(events.toString());
                JSONObject last = events.getJSONObject(events.length() - 1);
                JSONObject clone = new JSONObject(last.toString());
                double from = document.tempo().time(last.getJSONArray("endTime"));
                clone.put("start", last.get("end"))
                    .put("startTime", document.timestamp(from))
                    .put("endTime",
                        document.timestamp(
                            document.tempo().seconds(document.tempo().beatAt(from) + 1)));
                next.put(clone);
                document.value(param.name, next);
                eventIndex = next.length() - 1;
                changed(true);
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex);
            }
        });
        action(navigation, "Delete event", () -> {
            JSONArray next = copy(events);
            next.remove(eventIndex);
            document.value(param.name, next.length() == 0 ? event.opt("end") : next);
            eventIndex = Math.max(0, eventIndex - 1);
            changed(true);
        });
        action(navigation, "Constant", () -> {
            document.value(param.name, event.opt("start"));
            changed(true);
        });
        LinearLayout times = row();
        form.addView(times);
        EditText start = field(
                     times, "Start ", fmt(document.axis(eventTime(event, "startTime"), beats))),
                 end = field(times, "End ", fmt(document.axis(eventTime(event, "endTime"), beats)));
        LinearLayout from = row(), to = param.size == 1 ? from : row();
        form.addView(from);
        if (to != from)
            form.addView(to);
        ColorPickerButton startColor = param.size == 4
                ? ColorPickerButton.addTo(from, "From", rgbaColor(event.opt("start")), true) : null;
        ColorPickerButton endColor = param.size == 4
                ? ColorPickerButton.addTo(to, "To", rgbaColor(event.opt("end")), true) : null;
        EditText[] startValue = param.size == 4 ? null : values(from, event.opt("start"), param.size, "From "),
                   endValue = param.size == 4 ? null : values(to, event.opt("end"), param.size, "To ");
        LinearLayout easing = row();
        form.addView(easing);
        Spinner ease = new Spinner(getContext());
        ease.setAdapter(
            new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item,
                getResources().getStringArray(R.array.event_easing_entries)));
        ease.setSelection(Math.max(0, Math.min(28, event.optInt("easingType", 1) - 1)));
        easing.addView(ease, new LinearLayout.LayoutParams(0, dp(36), 2));
        EditText left = field(easing, "Left ", fmt(event.optDouble("easingLeft", 0))),
                 right = field(easing, "Right ", fmt(event.optDouble("easingRight", 1)));
        action(easing, "Apply", () -> {
            double a = time(start), b = time(end), l = number(left), r = number(right);
            if (b <= a || l < 0 || r > 1 || r <= l
                || !Float.isFinite(ShaderTween.apply(
                    ease.getSelectedItemPosition() + 1, .5f, (float) l, (float) r)))
                throw new IllegalArgumentException(
                    "Check time range and easing window (0 ≤ left < right ≤ 1)");
            try {
                JSONObject edited = new JSONObject(event.toString());
                edited.put("startTime", document.timestamp(a))
                    .put("endTime", document.timestamp(b))
                    .put("start", startColor == null ? value(startValue, param.size) : colorValue(startColor.getColor()))
                    .put("end", endColor == null ? value(endValue, param.size) : colorValue(endColor.getColor()))
                    .put("easingType", ease.getSelectedItemPosition() + 1)
                    .put("easingLeft", l)
                    .put("easingRight", r);
                JSONArray next = copy(events);
                next.put(eventIndex, edited);
                document.value(param.name, next);
                changed(true);
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex);
            }
        });
        form.addView(text("Tap an event in the right timeline to edit it; drag its "
            + "start/end edge. Times use the effect BPM map."));
    }
    private static JSONArray copy(JSONArray array) {
        try {
            return new JSONArray(array.toString());
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }
    private double eventTime(JSONObject event, String key) {
        try {
            return document.tempo().time(event.getJSONArray(key));
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }
    private void bpmForm() {
        form.addView(text("Independent effect tempo. Existing effect/keyframe "
            + "beat positions stay "
            + "fixed when this map changes."));
        Object bpm = document.bpm();
        JSONArray map;
        try {
            map = bpm instanceof JSONArray
                ? copy((JSONArray) bpm)
                : new JSONArray().put(
                      new JSONObject().put("time", BeatTime.zero().toJson()).put("bpm", bpm));
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
        for (int i = 0; i < map.length(); i++) {
            final int index = i;
            JSONObject item = map.optJSONObject(i);
            LinearLayout row = row();
            form.addView(row);
            EditText beat = field(row, "FX beat ", fmt(ShaderDocumentBeat(item))),
                     value = field(row, "BPM ", fmt(item.optDouble("bpm")));
            action(row, "Apply", () -> {
                try {
                    JSONArray next = copy(map);
                    next.getJSONObject(index)
                        .put("time", BeatTime.parseFlexible(beat.getText().toString()).toJson())
                        .put("bpm", number(value));
                    document.bpm(next);
                    changed(true);
                } catch (Exception ex) {
                    throw new IllegalArgumentException(ex);
                }
            });
            if (map.length() > 1)
                action(row, "Delete", () -> {
                    JSONArray next = copy(map);
                    next.remove(index);
                    document.bpm(next);
                    changed(true);
                });
        }
        action(form, "+ BPM change", () -> {
            try {
                JSONArray next = copy(map);
                double current = Math.max(0, document.tempo().beatAt(editor.shaderEditorSeconds()));
                double last = ShaderDocumentBeat(next.getJSONObject(next.length() - 1));
                next.put(new JSONObject()
                        .put("time",
                            BeatTime.fromDouble(Math.max(last + 1, current), 1000000).toJson())
                        .put("bpm", 120));
                document.bpm(next);
                changed(true);
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex);
            }
        });
    }
    private double ShaderDocumentBeat(JSONObject item) {
        try {
            return com.xpe.mobile.preview.ShaderTimeline.Tempo.beat(item.getJSONArray("time"));
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        int top = Math.min(h, dp(48)),
            panel = Math.min(Math.max(0, h - top - dp(64)),
                collapsed      ? dp(36)
                    : expanded ? Math.min(dp(176), Math.round(h * .45f))
                               : dp(88));
        playbackSeek.getLayoutParams().width =
            Math.round(editor.editorPlaybackSeekWidth(w)) + dp(20);
        int body = Math.max(0, h - top - panel), split = Math.round(w * .60f);
        toolbar.measure(exact(w), exact(top));
        preview.measure(exact(split), exact(body));
        gpu.measure(exact(split), exact(body));
        timeline.measure(exact(w - split), exact(body));
        bottom.measure(exact(w), exact(panel));
    }
    private static int exact(int size) {
        return MeasureSpec.makeMeasureSpec(Math.max(0, size), MeasureSpec.EXACTLY);
    }
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t, top = toolbar.getMeasuredHeight(),
            split = preview.getMeasuredWidth(), edge = top + preview.getMeasuredHeight();
        toolbar.layout(0, 0, w, top);
        preview.layout(0, top, split, edge);
        gpu.layout(0, top, split, edge);
        timeline.layout(split, top, w, edge);
        bottom.layout(0, edge, w, h);
    }
    private final class PreviewPane extends View {
        private final RectF area = new RectF();
        private float panDownY;
        private double panAxis;
        private boolean panning, blocked;
        void cancelPan() {
            if (panning) editor.shaderEndPan();
            panning = false;
        }
        boolean panTouch(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                blocked = false;
                panDownY = event.getY();
                panAxis = document.axis(editor.shaderEditorSeconds(), beats);
                editor.shaderBeginPan();
                panning = true;
                invalidatePreview();
                return true;
            }
            if (event.getPointerCount() > 1) { cancelPan(); blocked = true; return true; }
            if (action == MotionEvent.ACTION_CANCEL) { cancelPan(); return true; }
            if (blocked) return true;
            if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                float dy = event.getY() - panDownY;
                if (panning) {
                    editor.shaderPanTo(document.seconds(Math.max(0, panAxis + dy / editor.timelineScale()
                            * editor.timelineScrollMultiplier()), beats));
                    invalidatePreview();
                }
                if (action == MotionEvent.ACTION_UP) cancelPan();
            }
            return true;
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            boolean handled = panTouch(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
            return handled;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        PreviewPane(Context c) {
            super(c);
        }
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            double start = -1, end = -1;
            if (loop.isChecked() && document.selected() != null) {
                start = document.start(document.selected);
                end = document.end(document.selected);
            }
            area.set(0, 0, getWidth(), getHeight());
            editor.drawShaderEditorPreview(canvas, area, gpu, start, end);
            timeline.invalidate();
            playbackSeek.invalidate();
            if (editor.isPlaying())
                postInvalidateOnAnimation();
            {
                play.setText(editor.isPlaying() ? R.string.toolbar_pause : R.string.toolbar_play);
                if (diagnostic != null) {
                    status.setTextColor(0xffffab91);
                    status.setText(diagnostic);
                } else {
                    status.setTextColor(Color.WHITE);
                    status.setText(getResources().getString(R.string.shader_clock,
                        String.format(java.util.Locale.US, "%.3f", document.axis(editor.shaderEditorSeconds(), beats)),
                        getResources().getString(
                            beats ? R.string.shader_beats_unit : R.string.shader_seconds_unit),
                        document.dirty() ? " *" : ""));
                }
            }
        }
    }
    final class Timeline extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path curve = new Path();
        private float pinchSpan;
        private boolean holdingTimeline;
        private float horizontal, downX, downY, lastX, lastY;
        private boolean horizontalGesture;
        private int target = -1, handle;
        private boolean dragging, animation, multiTouch;
        private double originalStart, originalEnd;
        private final List<String> lanes = new ArrayList<>();
        private final List<Hit> hits = new ArrayList<>();
        private long cachedRevision = -1;
        private int[] effectLanes = new int[0];
        private double[] starts = new double[0], ends = new double[0];
        private String[] labels = new String[0];
        private int hitCount;
        private long animationRevision = -1;
        private int animationSelection = -1;
        private String animationParameter, animationTitle;
        private final List<AnimationBlock> animationBlocks = new ArrayList<>();
        private final class AnimationBlock {
            final double start, end;
            final double[] from, to;
            final int easing;
            final float left, right;
            AnimationBlock(JSONObject event, int size) {
                start = eventTime(event, "startTime");
                end = eventTime(event, "endTime");
                from = document.components(event.opt("start"), size);
                to = document.components(event.opt("end"), size);
                easing = event.optInt("easingType", 1);
                left = (float) event.optDouble("easingLeft", 0);
                right = (float) event.optDouble("easingRight", 1);
            }
        }
        private void prepareAnimation() {
            if (animationRevision == document.revision() && animationSelection == document.selected
                && java.util.Objects.equals(animationParameter, parameter))
                return;
            animationRevision = document.revision();
            animationSelection = document.selected;
            animationParameter = parameter;
            animationTitle = parameter + " · parameter events";
            animationBlocks.clear();
            int size = 0;
            for (ShaderDocument.Parameter param : document.parameters())
                if (param.name.equals(parameter)) {
                    size = param.size;
                    break;
                }
            if (size == 0)
                return;
            JSONArray events = document.selected().optJSONObject("vars").optJSONArray(parameter);
            for (int i = 0; i < events.length(); i++)
                animationBlocks.add(new AnimationBlock(events.optJSONObject(i), size));
        }
        private final class Hit {
            final RectF rect = new RectF();
            int index;
            float startY, endY;
        }
        private Hit hit(float left, float top, float right, float bottom, int index) {
            if (hitCount == hits.size())
                hits.add(new Hit());
            Hit hit = hits.get(hitCount++);
            hit.rect.set(left, top, right, bottom);
            hit.index = index;
            if (animation()) {
                hit.startY = y(animationBlocks.get(index).start);
                hit.endY = y(animationBlocks.get(index).end);
            } else {
                hit.startY = y(starts[index]);
                hit.endY = y(ends[index]);
            }
            return hit;
        }
        Timeline(Context context) {
            super(context);
            setContentDescription("Vertical shader effects timeline");
        }
        private boolean animation() {
            if (page != 2 || parameter == null || document.selected() == null)
                return false;
            Object value = document.selected().optJSONObject("vars") == null
                ? null
                : document.selected().optJSONObject("vars").opt(parameter);
            return document.animated(value);
        }
        private void lanes() {
            if (cachedRevision != document.revision()) {
                cachedRevision = document.revision();
                lanes.clear();
                int count = document.count();
                effectLanes = new int[count];
                starts = new double[count];
                ends = new double[count];
                labels = new String[count];
                for (int i = 0; i < count; i++) {
                    String key = key(i);
                    if (!lanes.contains(key))
                        lanes.add(key);
                    effectLanes[i] = lanes.indexOf(key);
                    starts[i] = document.start(i);
                    ends[i] = document.end(i);
                    labels[i] = "#" + (i + 1);
                }
            }
            horizontal = Math.max(0,
                Math.min(
                    horizontal, Math.max(0, lanes.size() * laneWidth() - getWidth() + dp(36))));
        }
        private float laneWidth() {
            return Math.max(
                dp(64), (getWidth() - dp(36)) / (float) Math.max(1, Math.min(5, lanes.size())));
        }
        private String key(int i) {
            JSONObject e = document.effect(i);
            return (e.optBoolean("global") ? "G · " : "L · ") + e.optString("shader");
        }
        void reveal(int index) {
            if (index < 0)
                return;
            lanes();
            int lane = lanes.indexOf(key(index));
            horizontal = Math.max(0, lane * laneWidth() - getWidth() / 2f + laneWidth() / 2);
        }
        private float y(double seconds) {
            return EditorTimelineMetrics.baselineY(getHeight(), density)
                - (float) (document.axis(seconds, beats)
                      - document.axis(editor.shaderEditorSeconds(), beats))
                * editor.timelineScale();
        }
        private double axisY(float y) {
            return document.axis(editor.shaderEditorSeconds(), beats)
                + (EditorTimelineMetrics.baselineY(getHeight(), density) - y)
                / editor.timelineScale();
        }
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.rgb(17, 23, 30));
            hitCount = 0;
            boolean anim = animation();
            float head = dp(30), gutter = dp(36);
            double current = document.axis(editor.shaderEditorSeconds(), beats);
            editor.drawTimelineRows(canvas, 0, head, getWidth(), getHeight(), current,
                editor.timelineScale(), beats ? editor.getSubdivision() : 4);
            lanes();
            paint.setStrokeWidth(density);
            paint.setColor(Color.rgb(58, 67, 76));
            for (int i = 0; i <= (anim ? 1 : lanes.size()); i++) {
                float x =
                    anim ? (i == 0 ? gutter : getWidth()) : gutter + i * laneWidth() - horizontal;
                if (x >= gutter && x <= getWidth())
                    canvas.drawLine(x, head, x, getHeight(), paint);
            }
            int save = canvas.save();
            canvas.clipRect(gutter, head, getWidth(), getHeight());
            if (anim) {
                prepareAnimation();
                for (int i = 0; i < animationBlocks.size(); i++) {
                    AnimationBlock event = animationBlocks.get(i);
                    float a = y(event.start), b = y(event.end);
                    if (a < head || b > getHeight())
                        continue;
                    RectF rect =
                        hit(gutter + dp(4), b, getWidth() - dp(4), Math.max(b + dp(6), a), i).rect;
                    block(canvas, rect, i == eventIndex, 0xff655d93);
                    double[] from = event.from, to = event.to;
                    int size = from.length;
                    if (size == 1 && editor.timelineCurvesVisible()) {
                        editor.drawTimelineEasingCurve(canvas, rect, false, i == eventIndex,
                            f
                            -> ShaderTween.apply(event.easing, (float) f, event.left, event.right));
                    }
                    for (int component = 0;
                        component < size && size > 1 && editor.timelineCurvesVisible();
                        component++) {
                        double min = Math.min(from[component], to[component]),
                               max = Math.max(from[component], to[component]);
                        curve.reset();
                        for (int s = 0; s <= 40; s++) {
                            float f = s / 40f,
                                  p = ShaderTween.apply(event.easing, f, event.left, event.right);
                            float x = (float) (rect.left
                                + (component
                                      + (max == min
                                              ? .5
                                              : (from[component]
                                                    + (to[component] - from[component]) * p - min)
                                                  / (max - min)))
                                    * rect.width() / size);
                            if (!Float.isFinite(x))
                                continue;
                            float yy = rect.bottom - f * rect.height();
                            if (s == 0)
                                curve.moveTo(x, yy);
                            else
                                curve.lineTo(x, yy);
                        }
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(dp(1.5f));
                        paint.setColor(CURVE_COLORS[component]);
                        canvas.drawPath(curve, paint);
                        paint.setStyle(Paint.Style.FILL);
                    }
                }
            } else {
                lanes();
                for (int i = 0; i < document.count(); i++) {
                    int lane = effectLanes[i];
                    float x = gutter + lane * laneWidth() - horizontal;
                    if (x + laneWidth() < gutter || x > getWidth())
                        continue;
                    float a = y(starts[i]), b = y(ends[i]);
                    if (a < head || b > getHeight())
                        continue;
                    RectF rect =
                        hit(x + dp(3), b, x + laneWidth() - dp(3), Math.max(b + dp(6), a), i).rect;
                    block(canvas, rect, i == document.selected,
                        document.effect(i).optBoolean("global") ? 0xff725ea5 : 0xff2c8d81);
                    paint.setColor(Color.WHITE);
                    paint.setTextSize(dp(9));
                    canvas.drawText(labels[i], rect.left + dp(3),
                        Math.min(rect.bottom - dp(2), Math.max(head + dp(10), rect.top + dp(12))),
                        paint);
                }
            }
            canvas.restoreToCount(save);
            // Names stay in a reserved row, on the same background as the grid.
            save = canvas.save();
            canvas.clipRect(gutter, 0, getWidth(), head);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setTextSize(dp(10));
            if (anim)
                canvas.drawText(animationTitle, gutter, dp(18), paint);
            else
                for (int i = 0; i < lanes.size(); i++) {
                    float x = gutter + i * laneWidth() - horizontal;
                    if (x + laneWidth() < gutter || x > getWidth())
                        continue;
                    String label = lanes.get(i);
                    if (label.length() > 13)
                        label = label.substring(0, 12) + "…";
                    canvas.drawText(label, x + dp(3), dp(18), paint);
                }
            canvas.restoreToCount(save);
            // The beat ruler never moves with the lanes and cannot be covered by blocks.
            paint.setColor(Color.rgb(17, 23, 30));
            canvas.drawRect(0, 0, gutter, getHeight(), paint);
            paint.setTextSize(dp(10));
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setColor(Color.LTGRAY);
            editor.drawTimelineBeatNumbers(canvas, 0, head, gutter, getHeight(), current,
                    editor.timelineScale(), true);
            paint.setTextSize(dp(10));
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setColor(Color.LTGRAY);
            canvas.drawText(beats ? "Beat" : "s", gutter - dp(4), dp(18), paint);
            paint.setTextAlign(Paint.Align.LEFT);
            editor.drawTimelineBaseline(canvas, getWidth(), getHeight());
        }
        private void block(Canvas canvas, RectF rect, boolean selected, int color) {
            editor.drawTimelineBlock(canvas, rect, color);
            if (selected) {
                Hit hit = hits.get(hitCount - 1);
                editor.drawTimelineSelection(canvas, rect, hit.startY, hit.endY);
            }
        }
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                multiTouch = false;
                pinchSpan = 0;
                editor.shaderBeginPan();
                holdingTimeline = true;
                invalidatePreview();
            }
            if (action == MotionEvent.ACTION_CANCEL) { cancel(); return true; }
            if (event.getPointerCount() > 1) {
                if (!multiTouch) cancelEditing();
                multiTouch = true;
                float span = (float) Math.hypot(event.getX(1) - event.getX(0),
                        event.getY(1) - event.getY(0));
                if (action == MotionEvent.ACTION_MOVE && pinchSpan > dp(2) && span > dp(2)) {
                    editor.scaleShaderTimeline(span / pinchSpan);
                    invalidate();
                }
                pinchSpan = action == MotionEvent.ACTION_POINTER_UP ? 0 : span;
                return true;
            }
            if (multiTouch) {
                if (action == MotionEvent.ACTION_UP) finishHold();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                horizontalGesture = false;
                target = -1;
                dragging = false;
                animation = animation();
                handle = 0;
                Hit chosen = null;
                float bestDistance = Float.MAX_VALUE;
                if (downY > dp(30) && downX >= dp(36) && downX <= getWidth()) {
                    // Match the circular hit targets and S/E priority of Events.
                    for (int i = 0; i < hitCount; i++) {
                        Hit hit = hits.get(i);
                        float radius = Math.min(dp(26), (hit.rect.width() + dp(6)) * .48f);
                        float startDistance =
                            (float) Math.hypot(downX - hit.rect.centerX(), downY - hit.startY);
                        float endDistance =
                            (float) Math.hypot(downX - hit.rect.centerX(), downY - hit.endY);
                        float distance = Math.min(startDistance, endDistance);
                        if (hit.index == (animation ? eventIndex : document.selected))
                            distance -= .5f * density;
                        if (distance <= radius && distance <= bestDistance) {
                            chosen = hit;
                            handle = startDistance <= endDistance ? 1 : 2;
                            bestDistance = distance;
                        }
                    }
                    if (chosen == null)
                        for (int i = hitCount - 1; i >= 0; i--) {
                            Hit hit = hits.get(i);
                            if (downX >= hit.rect.left && downX <= hit.rect.right
                                && downY >= hit.rect.top - .18f * editor.timelineScale()
                                && downY <= hit.rect.bottom + .18f * editor.timelineScale()) {
                                chosen = hit;
                                break;
                            }
                        }
                }
                if (chosen != null) {
                    target = chosen.index;
                    if (animation) {
                        eventIndex = target;
                        buildForm();
                        JSONArray events =
                            document.selected().optJSONObject("vars").optJSONArray(parameter);
                        originalStart = eventTime(events.optJSONObject(target), "startTime");
                        originalEnd = eventTime(events.optJSONObject(target), "endTime");
                    } else {
                        if (document.selected != target)
                            select(target, false);
                        originalStart = document.start(target);
                        originalEnd = document.end(target);
                    }
                }
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                boolean handleMoved =
                    target >= 0 && handle != 0 && Math.abs(event.getY() - downY) >= .5f * density;
                if (!dragging
                    && (handleMoved
                        || Math.hypot(event.getX() - downX, event.getY() - downY) > 3f * density)) {
                    dragging = true;
                    horizontalGesture = target < 0 && (downY < dp(30)
                            || Math.abs(event.getX() - downX) > Math.abs(event.getY() - downY));
                    if (target >= 0 && handle != 0)
                        document.beginGesture();
                }
                if (!dragging)
                    return true;
                lastX = event.getX();
                lastY = event.getY();
                if (horizontalGesture) {
                    horizontal = Math.max(0, horizontal - dx);
                    lanes();
                    invalidate();
                    return true;
                }
                if (target < 0) {
                    editor.shaderPanTo(document.seconds(
                        Math.max(0,
                            document.axis(editor.shaderEditorSeconds(), beats)
                                + dy / editor.timelineScale() * editor.timelineScrollMultiplier()),
                        beats));
                    invalidatePreview();
                    return true;
                }
                if (handle == 0)
                    return true; // Event bodies select; only S/E handles edit time.
                updateHandle(event.getY());
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (dragging && target >= 0 && handle != 0) {
                    updateHandle(event.getY());
                    document.endGesture();
                    changed(true);
                } else if (!dragging && target < 0 && downY > dp(30)) {
                    boolean clearing = editor.clearTimelineSelectionOnTap();
                    if (document.selected >= 0) {
                        clearing = true;
                        if (animation && eventIndex >= 0) eventIndex = -1;
                        else { document.selected = -1; parameter = null; eventIndex = -1; }
                        buildForm();
                    }
                    if (!clearing && editor.isTapToSeekEnabled()) {
                        double snap = beats ? 1.0 / editor.getSubdivision() : .01;
                        editor.shaderPanTo(Math.max(0,
                                document.seconds(Math.round(axisY(event.getY()) / snap) * snap, beats)));
                    }
                    invalidatePreview();
                }
                finishHold();
                dragging = false;
                performClick();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                cancel();
                return true;
            }
            return true;
        }
        private void updateHandle(float pointerY) {
            double delta = -(pointerY - downY) / editor.timelineScale()
                * editor.eventScrollMultiplier(),
                   first = document.axis(originalStart, beats),
                   last = document.axis(originalEnd, beats),
                   snap = beats ? 1.0 / editor.getSubdivision() : .01;
            double a = handle == 2 ? first : Math.round((first + delta) / snap) * snap,
                   b = handle == 1 ? last : Math.round((last + delta) / snap) * snap;
            if (handle == 1)
                a = Math.min(a, b - snap);
            if (handle == 2)
                b = Math.max(b, a + snap);
            double start = document.seconds(a, beats), end = document.seconds(b, beats);
            safe(() -> {
                if (animation) {
                    JSONArray events =
                        copy(document.selected().optJSONObject("vars").optJSONArray(parameter));
                    JSONObject item = events.optJSONObject(target);
                    try {
                        item.put("startTime", document.timestamp(start))
                            .put("endTime", document.timestamp(end));
                    } catch (Exception ex) {
                        throw new IllegalArgumentException(ex);
                    }
                    document.value(parameter, events);
                } else
                    document.range(target, start, end);
                changed(false);
            });
        }
        private void finishHold() {
            if (holdingTimeline) {
                holdingTimeline = false;
                editor.shaderEndPan();
            }
        }
        private void cancelEditing() {
            document.cancelGesture();
            dragging = false;
            target = -1;
            changed(false);
        }
        private void cancel() {
            cancelEditing();
            finishHold();
        }
        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
