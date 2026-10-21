package com.xpe.mobile.shadereditor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import com.xpe.mobile.R;
import com.xpe.mobile.editor.EditorListScrollView;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.editor.SceneEditorControls;
import com.xpe.mobile.editor.SceneEditorLayout;
import com.xpe.mobile.i18n.UiDialogs;
import com.xpe.mobile.i18n.UiText;
import com.xpe.mobile.preview.PreviewShaderView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A dedicated landscape editor: Preview left, vertical effect/event lanes
 * right, forms below.
 */
@SuppressLint("ViewConstructor")
public final class ShaderEditorView extends ViewGroup {
    final ShaderAnimationForms shaderAnimationForms = new ShaderAnimationForms(this);
    final ShaderEffectForms shaderEffectForms = new ShaderEffectForms(this);

    public interface Host {
        void changed();
        void importShader();
        void save();
    }
    final ShaderDocument document;
    final EditorView editor;
    private final Host host;
    private final ShaderPreviewPane preview;
    final PreviewShaderView gpu;
    final ShaderEditorTimeline timeline;
    final LinearLayout toolbar, bottom, form;
    final View playbackSeek;
    private final Button expand;
    private final EditorListScrollView scroll;
    final TextView status;
    final Button play, undo, redo;
    private Button units;
    String diagnostic;
    private int diagnosticResource;
    final CheckBox loop;
    boolean beats = true, collapsed, expanded;
    int page;
    String parameter;
    int eventIndex;
    final float density;
    static final int[] CURVE_COLORS = {0xffffb74d, 0xff81c784, 0xff64b5f6, 0xfff5f5f5};

    public ShaderEditorView(Context context, EditorView editor, ShaderDocument document, Host host) {
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
        play = toolbarAction(controls, R.string.toolbar_play, 58, this::togglePlayback);
        UiText.text(play, () -> getContext().getText(editor.isPlaying() ? R.string.toolbar_pause : R.string.toolbar_play));
        loop = new CheckBox(context);
        UiText.text(loop, R.string.shader_loop);
        loop.setTextSize(10.5f);
        controls.addView(loop, new LinearLayout.LayoutParams(dp(64), dp(38)));
        loop.setOnCheckedChangeListener((v, value) -> invalidatePreview());
        units = toolbarAction(controls, R.string.shader_fx_beats, 64, this::toggleUnits);
        toolbarAction(controls, R.string.shader_add_effect, 64, this::addEffect);
        toolbarAction(controls, R.string.ui_import_glsl, 78, host::importShader);
        undo = toolbarAction(controls, R.string.toolbar_undo, 52, () -> {
            document.undo();
            changed(true);
        });
        redo = toolbarAction(controls, R.string.toolbar_redo, 52, () -> {
            document.redo();
            changed(true);
        });
        toolbarAction(controls, R.string.toolbar_save, 52, host::save);
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
        UiText.description(playbackSeek, R.string.ui_audio_seek);
        toolbar.addView(playbackSeek, new LinearLayout.LayoutParams(dp(210), -1));
        status = text(R.string.workbench_shaders);
        UiText.text(status, this::statusLabel);
        status.setPadding(dp(8), 0, 0, 0);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        preview = new ShaderPreviewPane(context, this);
        gpu = new PreviewShaderView(context);
        gpu.setOnTouchListener((v, e) -> {
            boolean handled = preview.panTouch(e);
            if (e.getActionMasked() == MotionEvent.ACTION_UP) v.performClick();
            return handled;
        });
        gpu.setFrameListener(preview::invalidate);
        gpu.setErrorListener(this::error);
        timeline = new ShaderEditorTimeline(context, this);
        addView(preview);
        addView(gpu);
        addView(timeline);
        bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(Color.rgb(26, 33, 42));
        addView(bottom);
        LinearLayout pages = row();
        bottom.addView(pages, new LinearLayout.LayoutParams(-1, dp(36)));
        action(pages, R.string.ui_effect, () -> {
            page = 0;
            openProperties();
            timeline.invalidate();
            buildForm();
        });
        action(pages, R.string.ui_parameters, () -> {
            page = 1;
            openProperties();
            timeline.invalidate();
            buildForm();
        });
        action(pages, R.string.ui_animation, () -> {
            page = 2;
            openProperties();
            timeline.invalidate();
            buildForm();
        });
        action(pages, R.string.ui_fx_bpm, () -> {
            page = 3;
            openProperties();
            timeline.invalidate();
            buildForm();
        });
        action(pages, R.string.ui_effects, this::effectList);
        pages.addView(status, new LinearLayout.LayoutParams(0, -1, 1));
        expand = action(pages, R.string.shader_expand, () -> {
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
        units.setText(getContext().getString(beats ? R.string.shader_fx_beats : R.string.shader_seconds));
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
    LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }
    TextView text(java.util.function.Supplier<String> label) {
        TextView view = text(""); UiText.text(view, label); return view;
    }
    TextView text(int label) {
        TextView view = text("");
        UiText.text(view, label);
        return view;
    }
    Button action(LinearLayout parent, int label, Runnable callback) {
        Button view = action(parent, "", callback);
        UiText.text(view, label);
        return view;
    }
    private Button toolbarAction(LinearLayout parent, int label, int width, Runnable callback) {
        Button view = toolbarAction(parent, "", width, callback);
        UiText.text(view, label);
        return view;
    }
    @Override public android.content.res.Resources getResources() {
        return UiText.resources(getContext());
    }

    public void relocalizeUi() {
        UiText.relocalize(this);
        expandLabel();
        units.setText(getContext().getString(beats ? R.string.shader_fx_beats : R.string.shader_seconds));
        timeline.relocalizeUi();
        invalidatePreview();
    }
    TextView text(String label) {
        TextView view = new TextView(getContext());
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(12);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    Button action(LinearLayout parent, String label, Runnable callback) {
        return SceneEditorControls.action(getContext(), parent, label, () -> safe(callback));
    }

    void openProperties() {
        collapsed = false;
        scroll.scrollTo(0, 0);
        requestLayout();
    }
    private void expandLabel() {
        expand.setText(getContext().getString(expanded ? R.string.shader_compact : R.string.shader_expand));
    }
    private Button toolbarAction(LinearLayout parent, String label, int width, Runnable callback) {
        return SceneEditorControls.toolbarAction(getContext(), parent, label, width, () -> safe(callback));
    }

    void safe(Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            diagnosticResource = 0;
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof com.xpe.mobile.i18n.UiErrors.ValidationException) {
                    diagnosticResource = ((com.xpe.mobile.i18n.UiErrors.ValidationException) cause).resource;
                    break;
                }
            }
            diagnostic = ex.getMessage();
            status.setText(statusLabel()); status.setTextColor(0xffffab91);
        }
    }
    public void error(String message) {
        diagnosticResource = 0;
        diagnostic = message;
        status.setText(
            UiText.resources(getContext()).getString(R.string.shader_error, message == null ? getContext().getString(R.string.shader_error_unknown) : message));
        status.setTextColor(0xffffab91);
    }
    String statusLabel() {
        if (diagnostic != null) return getContext().getString(R.string.shader_error,
                diagnosticResource == 0 ? diagnostic : getContext().getString(diagnosticResource));
        return getContext().getString(R.string.shader_clock,
                String.format(Locale.US, "%.3f", document.axis(editor.shaderEditorSeconds(), beats)),
                getContext().getString(beats ? R.string.shader_beats_unit : R.string.shader_seconds_unit),
                document.dirty() ? " *" : "");
    }
    public void invalidatePreview() {
        preview.invalidate();
        timeline.invalidate();
        playbackSeek.invalidate();
    }
    public void imported() {
        page = 0;
        parameter = null;
        eventIndex = 0;
        timeline.reveal(document.selected);
        changed(true);
    }
    public void onShown() {
        setVisibility(VISIBLE);
        invalidatePreview();
    }
    public void onHidden() {
        timeline.cancel();
        preview.cancelPan();
        editor.shaderPlaybackSeekTouch(MotionEvent.ACTION_CANCEL, 0, 0);
        host.changed();
        gpu.hidePreview();
        setVisibility(GONE);
    }
    void changed(boolean rebuild) {
        diagnostic = null;
        host.changed();
        undo.setEnabled(document.canUndo());
        redo.setEnabled(document.canRedo());
        timeline.invalidate();
        preview.invalidate();
        if (rebuild)
            buildForm();
    }
    void select(int index, boolean seek) {
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
        new UiDialogs.Builder(getContext())
            .setTitle(R.string.ui_add_effect)
            .setItems(names.toArray(new String[0]), (d, i) -> safe(() -> {
                document.add(names.get(i), editor.shaderEditorSeconds());
                parameter = null;
                page = 0;
                timeline.reveal(document.selected);
                changed(true);
            }))
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }
    private void effectList() {
        String[] names = new String[document.count()];
        for (int i = 0; i < names.length; i++)
            names[i] = "#" + (i + 1) + " · " + document.effect(i).optString("shader") + " · "
                + fmt(document.start(i)) + "s";
        new UiDialogs.Builder(getContext())
            .setTitle(R.string.ui_effects_in_application_order)
            .setItems(names, (d, i) -> select(i, true))
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }
    static String fmt(double value) {
        return String.format(Locale.US, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    double time(EditText field) {
        return shaderEffectForms.time(field);
    }
    void buildForm() {
        form.removeAllViews();
        undo.setEnabled(document.canUndo());
        redo.setEnabled(document.canRedo());
        if (page == 3) {
            shaderAnimationForms.bpmForm();
            return;
        }
        JSONObject selected = document.selected();
        if (selected == null) {
            form.addView(text(R.string.ui_add_a_preset_effect_or_import_a_glsl_file_drag_empty_timeline_to_seek_pinch_to_zoom));
            return;
        }
        if (page == 0) {
            shaderEffectForms.effectForm();
            return;
        }
        List<ShaderDocument.Parameter> params = document.parameters();
        if (params.isEmpty()) {
            form.addView(text(R.string.ui_this_shader_exposes_no_editable_float_vec2_color_parameters));
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
            shaderEffectForms.parameterForm(selectedParam, primary);
        else
            shaderAnimationForms.animationForm(selectedParam, primary);
    }

    static JSONArray copy(JSONArray array) {
        return ShaderAnimationForms.copy(array);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        int top = SceneEditorLayout.top(h, density),
            panel = SceneEditorLayout.panel(h, density, collapsed, expanded);
        playbackSeek.getLayoutParams().width =
            Math.round(editor.editorPlaybackSeekWidth(w)) + dp(20);
        int body = Math.max(0, h - top - panel), split = SceneEditorLayout.split(w);
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

}
