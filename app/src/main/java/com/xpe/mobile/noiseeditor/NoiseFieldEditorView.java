package com.xpe.mobile.noiseeditor;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.xpe.mobile.R;
import com.xpe.mobile.editor.EditorListScrollView;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.editor.SceneEditorControls;
import com.xpe.mobile.editor.SceneEditorLayout;
import com.xpe.mobile.editor.WorkbenchView;
import com.xpe.mobile.i18n.UiDialogs;
import com.xpe.mobile.i18n.UiText;
import com.xpe.mobile.model.NoiseField;
import com.xpe.mobile.noiseeditor.NoiseFieldEdits.Channel;
import com.xpe.mobile.preview.PreviewShaderView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONException;

/** One chart-scoped workspace; valid form edits autosave through the shared undo history. */
@SuppressLint("ViewConstructor")
public final class NoiseFieldEditorView extends ViewGroup {
    final EditorView editor;
    final NoiseFieldDocument document;
    final PreviewShaderView gpu;
    final NoiseFieldPreviewPane preview;
    final NoiseFieldTimeline timeline;
    final NoiseFieldForms forms;
    final NoiseFieldSelectionOverlay selectionOverlay;
    final LinearLayout toolbar, bottom, form, channels;
    final LinearLayout navigation;
    private final Button previousField, nextField, selectedField, overviewButton, locate;
    final View playbackSeek;
    private final Button units, expand;
    final CheckBox loopControl;
    final float density;
    boolean collapsed, expanded;
    final TextView status;
    final Button play, undo, redo, delete, fieldButton;
    final EditorListScrollView scroll;
    boolean beats = true, snap = true, guides = true, loop;
    boolean overview;
    private NoiseField gestureBase, gestureDraft;
    private String diagnostic;
    private long revision;
    private final List<AlertDialog> dialogs = new ArrayList<>();

    public NoiseFieldEditorView(Context context, EditorView editor) {
        super(context);
        this.editor = editor;
        document = editor.createNoiseFieldDocument();
        revision = document.chart.revision();
        density = getResources().getDisplayMetrics().density;
        selectionOverlay = new NoiseFieldSelectionOverlay(this);
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
        play = toolbarAction(controls, R.string.toolbar_play, 58, this::togglePlay);
        UiText.text(play, () -> getContext().getText(editor.isPlaying() ? R.string.toolbar_pause : R.string.toolbar_play));
        loopControl = new CheckBox(context);
        UiText.text(loopControl, R.string.shader_loop);
        loopControl.setTextSize(10.5f);
        controls.addView(loopControl, new LinearLayout.LayoutParams(dp(64), dp(38)));
        loopControl.setOnCheckedChangeListener((button, value) -> { loop = value; invalidatePreview(); });
        units = toolbarAction(controls, R.string.shader_fx_beats, 64, this::toggleUnits);
        Button add = toolbarAction(controls, R.string.noise_add, 64,
                () -> { if (document.channel == null) addField(); else run(() -> document.addKey(snapTime(time()))); });
        UiText.text(add, () -> tr(document.channel == null ? R.string.noise_add : R.string.noise_add_key));
        fieldButton = toolbarAction(controls, R.string.noise_fields, 64, this::fieldList);
        UiText.description(fieldButton, () -> label(document.selected));
        undo = toolbarAction(controls, R.string.toolbar_undo, 52, this::undo);
        redo = toolbarAction(controls, R.string.toolbar_redo, 52, this::redo);
        toolbarAction(controls, R.string.toolbar_save, 52, () -> { if (validateInputs()) editor.saveProjectFromMode(); });
        toolbarAction(controls, R.string.noise_more, 52, this::more);
        playbackSeek = new View(context) {
            @Override protected void onDraw(Canvas canvas) {
                editor.drawShaderPlaybackSeek(canvas, dp(10), getWidth() - dp(10));
            }
            @Override public boolean onTouchEvent(MotionEvent event) {
                getParent().requestDisallowInterceptTouchEvent(true);
                editor.shaderPlaybackSeekTouch(event.getActionMasked(), event.getX(), event.getY());
                invalidatePreview();
                if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
            }
            @Override public boolean performClick() { super.performClick(); return true; }
        };
        UiText.description(playbackSeek, R.string.ui_audio_seek);
        toolbar.addView(playbackSeek, new LinearLayout.LayoutParams(dp(210), -1));
        preview = new NoiseFieldPreviewPane(context, this);
        gpu = new PreviewShaderView(context);
        gpu.setOnTouchListener((view, event) -> {
            boolean handled = preview.panTouch(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) view.performClick();
            return handled;
        });
        gpu.setFrameListener(preview::invalidate);
        gpu.setErrorListener(message -> { diagnostic = message; updateStatus(); });
        timeline = new NoiseFieldTimeline(context, this);
        addView(preview); addView(gpu); addView(selectionOverlay); addView(timeline);
        bottom = column();
        bottom.setBackgroundColor(Color.rgb(26, 33, 42));
        addView(bottom);
        LinearLayout pages = row();
        bottom.addView(pages, new LinearLayout.LayoutParams(-1, dp(36)));
        channels = row();
        pages.addView(channels);
        int[] names = {R.string.noise_field_tab, R.string.noise_move, R.string.noise_scale, R.string.noise_rotate};
        for (int i = 0; i < names.length; i++) {
            final Channel channel = i == 0 ? null : Channel.values()[i - 1];
            Button button = action(channels, names[i], () -> {
                openProperties(); selectChannel(channel);
            });
            button.setMinWidth(dp(64)); button.setMinimumWidth(dp(64));
            button.setPadding(dp(12), 0, dp(12), 0);
            button.setTag(channel == null ? "area" : channel.name());
        }
        delete = action(pages, R.string.noise_delete, () -> {
            boolean wholeField = document.channel == null;
            run(() -> { if (wholeField) document.delete(); else document.deleteKey(); });
            if (wholeField) Toast.makeText(getContext(), R.string.noise_deleted, Toast.LENGTH_SHORT).show();
        });
        UiText.text(delete, () -> tr(document.channel == null ? R.string.noise_delete : R.string.noise_delete_key));
        status = text("");
        status.setPadding(dp(8), 0, 0, 0);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        UiText.text(status, this::statusLabel);
        pages.addView(status, new LinearLayout.LayoutParams(0, -1, 1));
        expand = action(pages, R.string.shader_expand, () -> {
            expanded = !expanded; collapsed = false; expandLabel(); requestLayout();
        });
        action(pages, "▼ / ▲", () -> { collapsed = !collapsed; requestLayout(); });
        scroll = new EditorListScrollView(context);
        bottom.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        form = column();
        form.setPadding(dp(8), 0, dp(8), dp(8));
        scroll.addView(form);
        forms = new NoiseFieldForms(this);
        navigation = row(); navigation.setBackgroundColor(Color.rgb(24, 30, 37));
        selectedField = navigationButton("", 120, this::fieldList);
        UiText.text(selectedField, () -> label(document.selected) + " ▾");
        previousField = navigationButton("‹", 0, () -> selectField(document.selected - 1));
        previousField.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
        UiText.description(previousField, R.string.noise_previous_field);
        nextField = navigationButton("›", 0, () -> selectField(document.selected + 1));
        nextField.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
        UiText.description(nextField, R.string.noise_next_field);
        overviewButton = navigationButton("", 94, () -> {
            overview = !overview; timeline.reveal(document.selected); invalidatePreview();
        });
        UiText.text(overviewButton, () -> tr(overview ? R.string.noise_selected_grid : R.string.noise_overview));
        locate = navigationButton("", 66, () -> { if (document.field() != null) seek(document.field().appear); });
        UiText.text(locate, R.string.noise_locate);
        UiText.description(locate, R.string.noise_locate_description);
        rebuild(); timeline.reveal(document.selected);
    }

    /** Narrow workbench/shortcut entry points; editing state stays in this package. */
    public boolean isDocumentValid() { return document.valid(); }
    public boolean commitPendingChanges() { return !document.valid() || validateInputs(); }
    public View navigationView() { return navigation; }
    public void copySelection() { run(document::copy); }
    public void cutSelection() { run(() -> { document.copy(); document.delete(); }); }
    public void pasteSelection() { run(() -> document.paste(time())); }
    public void deleteSelection() {
        run(() -> {
            if (document.channel != null && document.selectedKey >= 0) document.deleteKey();
            else document.delete();
        });
    }

    private Button navigationButton(String label, int width, Runnable callback) {
        Button button = WorkbenchView.button(getContext(), label);
        button.setSingleLine(true); button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.setOnClickListener(view -> callback.run());
        navigation.addView(button, new LinearLayout.LayoutParams(dp(width), -1)); return button;
    }

    int dp(float value) { return Math.round(value * density); }
    String tr(int id) { return UiText.resources(getContext()).getString(id); }
    LinearLayout row() { LinearLayout view = new LinearLayout(getContext()); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    LinearLayout column() { LinearLayout view = row(); view.setOrientation(LinearLayout.VERTICAL); return view; }
    TextView text(String value) {
        TextView view = new TextView(getContext()); view.setText(value); view.setTextColor(Color.WHITE);
        view.setTextSize(12); view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }
    TextView text(int id) { TextView view = text(""); UiText.text(view, id); return view; }
    Button action(LinearLayout parent, int id, Runnable action) {
        Button button = action(parent, "", action); UiText.text(button, id); return button;
    }
    Button action(LinearLayout parent, String label, Runnable action) {
        return SceneEditorControls.action(getContext(), parent, label, action);
    }
    private Button toolbarAction(LinearLayout parent, int label, int width, Runnable callback) {
        Button view = SceneEditorControls.toolbarAction(getContext(), parent, "", width, callback);
        UiText.text(view, label); return view;
    }
    void openProperties() { collapsed = false; scroll.scrollTo(0, 0); requestLayout(); }
    private void expandLabel() { UiText.text(expand, expanded ? R.string.shader_compact : R.string.shader_expand); }
    private void toggleUnits() {
        beats = !beats; UiText.text(units, beats ? R.string.shader_fx_beats : R.string.shader_seconds);
        rebuild(); timeline.relocalizeUi();
    }

    String label(int index) {
        if (index < 0 || index >= document.count()) return tr(R.string.noise_fields);
        String name = document.name(index);
        return name.isEmpty() ? UiText.resources(getContext()).getString(R.string.noise_field, index + 1) : name;
    }
    String number(double value) { return String.format(Locale.US, "%.6f", value).replaceFirst("\\.?0+$", ""); }
    double time() { return editor.shaderEditorSeconds(); }
    double beatAt(double seconds) {
        if (seconds >= 0) return document.chart.secondsToBeat(seconds);
        double origin = document.chart.secondsToBeat(0);
        // ChartDocument's playback conversion clamps negative seconds; authoring must retain them.
        double step = document.chart.beatToSeconds(origin) - document.chart.beatToSeconds(origin - 1);
        return origin + seconds / step;
    }
    double axis(double seconds) { return beats ? beatAt(seconds) : seconds; }
    double seconds(double axis) { return beats ? document.chart.beatToSeconds(axis) : axis; }
    String timeLabel(double seconds) { return number(axis(seconds)) + " " + tr(beats ? R.string.shader_beats_unit : R.string.shader_seconds_unit); }
    double snapTime(double seconds) {
        if (!snap) return seconds;
        int subdivision = Math.max(1, editor.getSubdivision());
        return document.chart.beatToSeconds(Math.round(beatAt(seconds) * subdivision) / (double) subdivision);
    }
    void seek(double seconds) { editor.shaderEditorSeek(Math.max(0, seconds)); invalidatePreview(); }
    private void addField() {
        run(() -> {
            document.add(time(), Math.max(.1, seconds(axis(time()) + (beats ? 4 : 2)) - time()), false);
            timeline.reveal(document.selected);
        });
    }
    void selectChannel(Channel channel) {
        int selected = -1;
        if (channel != null && document.field() != null) {
            for (int index : NoiseFieldEdits.sortedKeys(document.field(), channel)) {
                double t = document.field().toJson().optJSONArray(channel.json).optJSONObject(index).optDouble("time");
                if (selected < 0 || t <= time()) selected = index;
            }
        }
        document.selectKey(channel, selected); rebuild(); timeline.revealChannel(channel);
    }
    public void togglePlay() {
        NoiseField field = document.field();
        if (!editor.isPlaying() && loop && field != null && (time() < field.appear || time() >= field.disappear))
            seek(field.appear);
        editor.shaderEditorPlay(); invalidatePreview();
    }
    public void undo() {
        cancelGesture();
        document.undo(); rebuild();
    }
    public void redo() { document.redo(); rebuild(); }
    interface Edit { void run() throws JSONException; }
    void run(Edit edit) {
        if (!document.valid()) return;
        cancelGesture();
        try { edit.run(); rebuild(); }
        catch (JSONException ex) { error(); }
    }
    void error() {
        status.setText(tr(R.string.noise_invalid)); status.setTextColor(0xffffab91);
        if (status.getVisibility() != VISIBLE) Toast.makeText(getContext(), R.string.noise_invalid, Toast.LENGTH_LONG).show();
    }
    boolean validateInputs() {
        if (!document.valid()) return false;
        if (forms.dirty()) { error(); return false; }
        return true;
    }
    void rebuild() {
        cancelGesture();
        forms.build();
        revision = document.chart.revision();
        selectionChrome();
        updateStatus(); invalidatePreview();
    }
    void formCommitted() { revision = document.chart.revision(); }
    private void selectionChrome() {
        UiText.relocalize(fieldButton);
        int selected = document.channel == null ? 0 : document.channel.ordinal() + 1;
        for (int i = 0; i < channels.getChildCount(); i++) {
            View button = channels.getChildAt(i); button.setSelected(i == selected);
            button.setBackgroundColor(i == selected ? 0xff2d8474 : 0xff353f4a);
        }
    }
    void updateStatus() {
        if (forms == null) return;
        boolean pending = forms.dirty();
        undo.setEnabled(document.canUndo()); redo.setEnabled(document.canRedo());
        delete.setEnabled(document.field() != null && (document.channel == null
                || document.selectedKey >= 0 && document.selectedKey < NoiseFieldEdits.keyCount(document.field(), document.channel)));
        UiText.relocalize(delete);
        if (navigation != null) {
            previousField.setEnabled(document.selected > 0);
            nextField.setEnabled(document.count() > 0 && document.selected < document.count() - 1);
            locate.setEnabled(document.field() != null);
            overviewButton.setSelected(overview);
            UiText.relocalize(navigation);
        }
        status.setTextColor(diagnostic != null || pending && forms.snapshot() == null ? 0xffffab91 : 0xff9fbbcf);
        status.setText(statusLabel());
    }
    String statusLabel() {
        if (diagnostic != null) return diagnostic;
        if (forms != null && forms.dirty()) return tr(forms.snapshot() == null ? R.string.noise_invalid : R.string.noise_pending);
        return UiText.resources(getContext()).getString(R.string.noise_clock, label(document.selected), timeLabel(time()));
    }
    NoiseField timelineField() { return gestureDraft == null ? document.field() : gestureDraft; }
    NoiseField displayedField() {
        if (gestureDraft != null) return gestureDraft;
        NoiseField draft = forms == null || forms.base != document.field() ? null : forms.snapshot();
        return draft == null ? document.field() : draft;
    }
    List<NoiseField> previewFields() {
        NoiseField draft = displayedField();
        if (draft == null || draft == document.field()) return null;
        List<NoiseField> fields = new ArrayList<>(document.chart.noiseFields);
        fields.set(document.selected, draft); return fields;
    }
    boolean beginGesture() {
        if (!document.valid() || document.field() == null) return false;
        forms.build(); cancelGesture();
        gestureBase = document.field(); gestureDraft = gestureBase;
        return true;
    }
    void draftGesture(NoiseField value) { if (gestureBase != null) { gestureDraft = value; invalidatePreview(); } }
    void endGesture() {
        NoiseField base = gestureBase, next = gestureDraft;
        gestureBase = null; gestureDraft = null;
        if (base != null && next != null) document.replace(base, next);
        rebuild();
    }
    void cancelGesture() { gestureBase = null; gestureDraft = null; }
    public void invalidatePreview() {
        if (getVisibility() != VISIBLE || !document.valid()) return;
        if (forms.base != document.field() || document.chart.revision() != revision && !forms.dirty()) {
            revision = document.chart.revision(); forms.build(); selectionChrome();
        }
        preview.invalidate(); selectionOverlay.invalidate(); timeline.invalidate(); playbackSeek.invalidate(); updateStatus(); UiText.relocalize(play);
    }
    public void onShown() { setVisibility(VISIBLE); invalidatePreview(); }
    public void onHidden() {
        preview.cancelPan(); timeline.cancel();
        editor.shaderPlaybackSeekTouch(MotionEvent.ACTION_CANCEL, 0, 0);
        gpu.hidePreview(); setVisibility(GONE);
        for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
    }
    public void pause() {
        preview.cancelPan(); timeline.cancel(); editor.shaderPlaybackSeekTouch(MotionEvent.ACTION_CANCEL, 0, 0);
    }
    public void relocalizeUi() {
        UiText.relocalize(this); UiText.relocalize(navigation); forms.relocalize(); expandLabel(); timeline.relocalizeUi(); invalidatePreview();
    }
    AlertDialog show(UiDialogs.Builder builder) {
        AlertDialog dialog = builder.create(); dialogs.add(dialog);
        dialog.setOnDismissListener(v -> dialogs.remove(dialog)); dialog.show(); return dialog;
    }

    void selectField(int index) {
        if (index < 0 || index >= document.count()) return;
        Channel channel = document.channel;
        document.select(index); selectChannel(channel); timeline.reveal(index);
    }
    void fieldList() {
        if (!document.valid()) return;
        new NoiseFieldBrowser(this).show();
    }
    void more() {
        int[] ids = {R.string.noise_duplicate, R.string.noise_copy, R.string.noise_paste, R.string.noise_delete,
                R.string.noise_shift, R.string.noise_fullscreen, beats ? R.string.noise_seconds : R.string.noise_beats,
                R.string.noise_snap, R.string.noise_guides, R.string.noise_help_title};
        show(new UiDialogs.Builder(getContext()).setTitle(R.string.noise_more).setItems(() -> {
            CharSequence[] labels = new CharSequence[ids.length];
            for (int i = 0; i < ids.length; i++) labels[i] = tr(ids[i]);
            labels[7] = (snap ? "✓ " : "○ ") + labels[7];
            labels[8] = (guides ? "✓ " : "○ ") + labels[8];

            return labels;
        }, (dialog, choice) -> {
            if (choice == 8) { guides = !guides; invalidatePreview(); return; }
            if (choice == 9) {
                show(new UiDialogs.Builder(getContext()).setTitle(R.string.noise_help_title)
                        .setMessage(R.string.noise_help).setPositiveButton(android.R.string.ok, null)); return;
            }
            run(() -> {
                NoiseField field = document.field();
                switch (choice) {
                    case 0: document.duplicate(time()); break;
                    case 1: document.copy(); break;
                    case 2: document.paste(time()); break;
                    case 3: document.delete(); break;
                    case 4: if (field != null) document.replace(field, NoiseFieldEdits.shifted(field, time() - field.appear)); break;
                    case 5: if (field != null) document.replace(field, NoiseFieldEdits.bounds(field, 0, 0, 1, 1)); break;
                    case 6: beats = !beats; UiText.text(units, beats ? R.string.shader_fx_beats : R.string.shader_seconds); break;
                    case 7: snap = !snap; break;
                    default: break;
                }
            });
        }));
    }
    static TextWatcher watcher(Runnable changed) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { changed.run(); }
            @Override public void afterTextChanged(Editable value) { }
        };
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        int top = SceneEditorLayout.top(h, density), panel = SceneEditorLayout.panel(h, density, collapsed, expanded);
        playbackSeek.getLayoutParams().width = Math.round(editor.editorPlaybackSeekWidth(w)) + dp(20);
        int body = Math.max(0, h - top - panel), split = SceneEditorLayout.split(w);
        toolbar.measure(exact(w), exact(top));
        preview.measure(exact(split), exact(body)); gpu.measure(exact(split), exact(body));
        selectionOverlay.measure(exact(split), exact(body));
        timeline.measure(exact(w - split), exact(body)); bottom.measure(exact(w), exact(panel));
    }
    private static int exact(int size) { return MeasureSpec.makeMeasureSpec(Math.max(0, size), MeasureSpec.EXACTLY); }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t, top = toolbar.getMeasuredHeight(),
            split = preview.getMeasuredWidth(), edge = top + preview.getMeasuredHeight();
        toolbar.layout(0, 0, w, top); preview.layout(0, top, split, edge); gpu.layout(0, top, split, edge);
        selectionOverlay.layout(0, top, split, edge);
        timeline.layout(split, top, w, edge); bottom.layout(0, edge, w, h);
    }
    @Override protected void onDetachedFromWindow() {
        pause(); gpu.hidePreview();
        for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        super.onDetachedFromWindow();
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) invalidatePreview();
    }
}
