package com.xpe.mobile.noiseeditor;

import android.text.InputType;
import android.view.View;
import android.widget.*;
import com.xpe.mobile.R;
import com.xpe.mobile.editor.ColorPickerButton;
import com.xpe.mobile.editor.EasingSelector;
import com.xpe.mobile.i18n.UiDialogs;
import com.xpe.mobile.i18n.UiText;
import com.xpe.mobile.model.NoiseField;
import com.xpe.mobile.noiseeditor.NoiseFieldEdits.Channel;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/** Only edited properties are rewritten: native precision and extension metadata survive. */
final class NoiseFieldForms {
    private final NoiseFieldEditorView owner;
    NoiseField base;
    private NoiseField shown;
    private boolean centerSize;
    private final List<Binding> bindings = new ArrayList<>();
    private final List<Change> changes = new ArrayList<>();
    private boolean pending;
    private Object source, session, batchSource;
    interface Change { void apply(JSONObject json) throws JSONException; }
    interface ValueChange { void apply(JSONObject json, double value) throws JSONException; }
    static final class Binding {
        final EditText input;
        final String original;
        final ValueChange change;
        double lastGood;
        boolean hasGood;
        Binding(EditText input, ValueChange change) { this.input = input; original = input.getText().toString(); this.change = change; }
        boolean changed() { return !original.equals(input.getText().toString()); }
    }
    NoiseFieldForms(NoiseFieldEditorView owner) { this.owner = owner; }
    boolean dirty() { return pending; }
    NoiseField snapshot() { return pending ? null : base; }
    private void edited(Object input) {
        if (base != owner.document.field()) { owner.rebuild(); return; }
        if (batchSource != null) input = batchSource;
        if (source != input) { source = input; session = new Object(); }
        pending = false;
        try {
            JSONObject json = NoiseFieldEdits.copy(shown.toJson());
            java.util.Map<Binding, Double> accepted = new java.util.IdentityHashMap<>();
            for (Binding binding : bindings) {
                binding.input.setError(null);
                if (!binding.changed()) continue;
                try {
                    String text = binding.input.getText().toString().trim().replace(',', '.');
                    int label = (Integer) binding.input.getTag();
                    double value = isTime(label) && owner.beats ? com.xpe.mobile.model.BeatTime.parseFlexible(text).toDouble()
                            : Double.parseDouble(text);
                    if (!Double.isFinite(value)) throw new NumberFormatException();
                    binding.change.apply(json, value); accepted.put(binding, value);
                } catch (IllegalArgumentException ex) {
                    binding.input.setError(owner.tr(R.string.noise_invalid_number)); pending = true;
                    if (binding.hasGood) binding.change.apply(json, binding.lastGood);
                }
            }
            for (Change change : changes) change.apply(json);
            preserveInvalidPair(json, "appearTime", "disappearTime", R.string.noise_appear, R.string.noise_disappear, accepted);
            preserveInvalidPair(json, "enableTime", "disableTime", R.string.noise_enable, R.string.noise_disable, accepted);
            NoiseField value = NoiseField.fromJson(json);
            owner.document.replaceLive(base, value, session);
            base = owner.document.field();
            for (Binding binding : bindings) {
                if (!binding.changed() && binding.input.getError() == null) binding.hasGood = false;
                else if (accepted.containsKey(binding)) { binding.lastGood = accepted.get(binding); binding.hasGood = true; }
            }
            owner.formCommitted();
        } catch (JSONException | IllegalArgumentException ex) { pending = true; }
        owner.invalidatePreview();
    }
    private static boolean isTime(int label) {
        return label == R.string.noise_time || label == R.string.noise_appear || label == R.string.noise_enable
                || label == R.string.noise_disable || label == R.string.noise_disappear;
    }
    private void change(Change change) { changes.add(change); edited(new Object()); }
    private void preserveInvalidPair(JSONObject json, String first, String last, int firstId, int lastId,
                                     java.util.Map<Binding, Double> accepted) throws JSONException {
        if (json.getDouble(first) <= json.getDouble(last)) return;
        pending = true;
        String error = UiText.resources(owner.getContext()).getString(R.string.noise_time_order, owner.tr(firstId), owner.tr(lastId));
        for (Binding binding : bindings)
            if (binding.input.getTag().equals(firstId) || binding.input.getTag().equals(lastId)) {
                binding.input.setError(error); accepted.remove(binding);
            }
        json.put(first, base.toJson().getDouble(first)).put(last, base.toJson().getDouble(last));
    }
    void relocalize() { UiText.relocalize(owner.form); }
    void build() {
        source = session = null;
        bindings.clear(); changes.clear(); pending = false;
        base = owner.document.field(); shown = base;
        render();
    }
    private void render() {
        owner.form.removeAllViews();
        if (shown == null) { help(R.string.noise_empty); return; }
        if (owner.document.channel == null) area();
        else animation();
    }
    private void switchCoordinates() {
        NoiseField draft = snapshot();
        if (draft == null) { owner.error(); return; }
        // A display switch neither creates a chart edit nor rounds untouched raw values.
        boolean dirty = pending;
        bindings.clear(); changes.clear(); shown = draft; centerSize = !centerSize;
        pending = dirty; render(); owner.invalidatePreview();
    }
    private void help(int id) {
        TextView view = owner.text(id); view.setTextSize(12); view.setTextColor(0xff9fbbcf);

        owner.form.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }
    private EditText input(LinearLayout row, int label, String value) {
        TextView title = owner.text(label);
        row.addView(title, new LinearLayout.LayoutParams(-2, owner.dp(36)));
        EditText edit = new EditText(owner.getContext());
        edit.setText(value); edit.setTextColor(android.graphics.Color.WHITE); edit.setTextSize(12); edit.setSingleLine(true);
        edit.setSelectAllOnFocus(true); edit.setTag(label);
        edit.setOnFocusChangeListener((view, focused) -> { source = session = null; });
        UiText.description(edit, label); title.setLabelFor(View.generateViewId()); edit.setId(title.getLabelFor());
        row.addView(edit, new LinearLayout.LayoutParams(0, owner.dp(40), 1));
        return edit;
    }
    private LinearLayout line() {
        LinearLayout row = owner.row(); owner.form.addView(row, new LinearLayout.LayoutParams(-1, -2)); return row;
    }
    private void number(LinearLayout row, int label, double value, ValueChange change) {
        EditText edit = input(row, label, owner.number(value));
        edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        bindings.add(new Binding(edit, change));
        edit.addTextChangedListener(NoiseFieldEditorView.watcher(() -> edited(edit)));
    }
    private void area() {
        LinearLayout times = line();
        int[] ids = {R.string.noise_appear, R.string.noise_enable, R.string.noise_disable, R.string.noise_disappear};
        for (int i = 0; i < ids.length; i++) {
            String phase = NoiseFieldEdits.PHASES[i];
            number(times, ids[i], owner.axis(shown.toJson().optDouble(phase)),
                    (json, value) -> json.put(phase, owner.seconds(value)));
        }
        LinearLayout bounds = line();
        owner.action(bounds, centerSize ? R.string.noise_center_size : R.string.noise_bounds, this::switchCoordinates);
        if (centerSize) {
            number(bounds, R.string.noise_center_x, (rawVector("bottomLeftPercentage", "x") + rawVector("topRightPercentage", "x")) * 50,
                    (json, value) -> center(json, "x", value / 100));
            number(bounds, R.string.noise_center_y, (rawVector("bottomLeftPercentage", "y") + rawVector("topRightPercentage", "y")) * 50,
                    (json, value) -> center(json, "y", value / 100));
            number(bounds, R.string.noise_width, (rawVector("topRightPercentage", "x") - rawVector("bottomLeftPercentage", "x")) * 100,
                    (json, value) -> size(json, "x", value / 100));
            number(bounds, R.string.noise_height, (rawVector("topRightPercentage", "y") - rawVector("bottomLeftPercentage", "y")) * 100,
                    (json, value) -> size(json, "y", value / 100));
        } else {
            number(bounds, R.string.noise_left, rawVector("bottomLeftPercentage", "x") * 100,
                    (json, value) -> json.getJSONObject("bottomLeftPercentage").put("x", value / 100));
            number(bounds, R.string.noise_right, rawVector("topRightPercentage", "x") * 100,
                    (json, value) -> json.getJSONObject("topRightPercentage").put("x", value / 100));
            number(bounds, R.string.noise_bottom, rawVector("bottomLeftPercentage", "y") * 100,
                    (json, value) -> json.getJSONObject("bottomLeftPercentage").put("y", value / 100));
            number(bounds, R.string.noise_top, rawVector("topRightPercentage", "y") * 100,
                    (json, value) -> json.getJSONObject("topRightPercentage").put("y", value / 100));
        }
        LinearLayout identity = line();
        EditText name = input(identity, R.string.noise_name, shown.toJson().optString(NoiseFieldEdits.LABEL, ""));
        String originalName = name.getText().toString();
        changes.add(json -> { if (!originalName.equals(name.getText().toString()))
            json.put(NoiseFieldEdits.LABEL, name.getText().toString()); });
        name.addTextChangedListener(NoiseFieldEditorView.watcher(() -> edited(name)));
        CheckBox subtract = new CheckBox(owner.getContext());
        UiText.text(subtract, R.string.noise_subtract); subtract.setTextColor(0xffeef4fc);
        subtract.setTextSize(12); subtract.setChecked(shown.subtract);
        identity.addView(subtract, new LinearLayout.LayoutParams(-2, owner.dp(40)));
        subtract.setOnCheckedChangeListener((button, checked) -> change(json -> json.put("isSubtract", checked)));
        LinearLayout colors = line();
        ColorPickerButton picker = ColorPickerButton.addTo(colors, R.string.noise_color, shown.color, false);
        picker.setTextSize(12);
        picker.setImmediateChanges(() -> { source = session = null; });
        boolean[] resetColor = {false};
        changes.add(json -> {
            if (!resetColor[0] && picker.getColor() == shown.color) return;
            if (picker.getColor() == NoiseField.DEFAULT_COLOR) json.remove(NoiseField.COLOR);
            else json.put(NoiseField.COLOR, new org.json.JSONArray(new int[]{
                    (picker.getColor() >>> 16) & 255, (picker.getColor() >>> 8) & 255, picker.getColor() & 255}));
        });
        picker.setOnColorChanged(color -> edited(picker));
        owner.action(colors, R.string.noise_color_default, () -> {
            picker.setColor(NoiseField.DEFAULT_COLOR);
            resetColor[0] = true; edited(new Object());
        });
        help(R.string.noise_color_help);
        help(R.string.noise_type_help);
        help(R.string.noise_phase_help);
        help(R.string.noise_guides_help);
    }
    private static void center(JSONObject json, String axis, double value) throws JSONException {
        JSONObject low = json.getJSONObject("bottomLeftPercentage"), high = json.getJSONObject("topRightPercentage");
        double half = (high.getDouble(axis) - low.getDouble(axis)) / 2;
        low.put(axis, value - half); high.put(axis, value + half);
    }
    private static void size(JSONObject json, String axis, double value) throws JSONException {
        JSONObject low = json.getJSONObject("bottomLeftPercentage"), high = json.getJSONObject("topRightPercentage");
        double center = (high.getDouble(axis) + low.getDouble(axis)) / 2;
        low.put(axis, center - value / 2); high.put(axis, center + value / 2);
    }
    private double rawVector(String vector, String axis) { return shown.toJson().optJSONObject(vector).optDouble(axis); }
    private void animation() {
        Channel channel = owner.document.channel;
        LinearLayout controls = line();
        owner.action(controls, R.string.noise_keys, this::keys);
        List<Integer> sorted = NoiseFieldEdits.sortedKeys(base, channel);
        int position = sorted.indexOf(owner.document.selectedKey);
        Button previous = owner.action(controls, "‹", () -> stepKey(-1));
        Button next = owner.action(controls, "›", () -> stepKey(1));
        previous.setMinWidth(owner.dp(48)); previous.setMinimumWidth(owner.dp(48));
        next.setMinWidth(owner.dp(48)); next.setMinimumWidth(owner.dp(48));
        UiText.description(previous, R.string.noise_previous_key); UiText.description(next, R.string.noise_next_key);
        previous.setEnabled(!sorted.isEmpty() && position != 0);
        next.setEnabled(!sorted.isEmpty() && position < sorted.size() - 1);
        Button add = owner.action(controls, R.string.noise_add_key, () -> owner.run(() -> owner.document.addKey(owner.snapTime(owner.time()))));
        Button copy = owner.action(controls, R.string.controls_copy, () -> owner.run(() -> owner.document.duplicateKey(owner.snapTime(owner.time()))));
        add.setMinWidth(owner.dp(72)); add.setMinimumWidth(owner.dp(72));
        copy.setMinWidth(owner.dp(72)); copy.setMinimumWidth(owner.dp(72));
        copy.setEnabled(position >= 0);
        int index = owner.document.selectedKey;
        if (index < 0 || index >= NoiseFieldEdits.keyCount(base, channel)) {
            help(NoiseFieldEdits.keyCount(base, channel) == 0 ? R.string.noise_no_keys : R.string.noise_choose_key);
            help(R.string.noise_animation_help); return;
        }
        try {
            JSONObject key = NoiseFieldEdits.key(shown, channel, index);
            TextView keyTitle = owner.text("");
            UiText.text(keyTitle, () -> UiText.resources(owner.getContext()).getString(R.string.noise_key,
                    index + 1, owner.timeLabel(key.optDouble("time"))));
            keyTitle.setSingleLine(true); keyTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            controls.addView(keyTitle, new LinearLayout.LayoutParams(0, owner.dp(36), 1));
            LinearLayout values = line();
            number(values, R.string.noise_time, owner.axis(key.getDouble("time")),
                    (json, value) -> key(json).put("time", owner.seconds(value)));
            if (channel == Channel.ROTATE) {
                number(values, R.string.noise_angle, key.getDouble("rotation"),
                        (json, value) -> key(json).put("rotation", value));
            } else {
                double factor = channel == Channel.MOVE ? 100 : 1;
                number(values, channel == Channel.MOVE ? R.string.noise_x : R.string.noise_scale_x,
                        key.getJSONObject(channel.vector).getDouble("x") * factor,
                        (json, value) -> key(json).getJSONObject(channel.vector).put("x", value / factor));
                number(values, channel == Channel.MOVE ? R.string.noise_y : R.string.noise_scale_y,
                        key.getJSONObject(channel.vector).getDouble("y") * factor,
                        (json, value) -> key(json).getJSONObject(channel.vector).put("y", value / factor));
            }
            if (channel.anchored()) {
                LinearLayout anchors = line();
                number(anchors, R.string.noise_anchor_x, key.getJSONObject("anchor").getDouble("x") * 100,
                        (json, value) -> key(json).getJSONObject("anchor").put("x", value / 100));
                number(anchors, R.string.noise_anchor_y, key.getJSONObject("anchor").getDouble("y") * 100,
                        (json, value) -> key(json).getJSONObject("anchor").put("y", value / 100));
                owner.action(anchors, R.string.noise_anchor_presets, this::anchorPresets);
            }
            if (channel == Channel.ROTATE) easing(R.string.noise_ease, "easeType", key.optInt("easeType"));
            else {
                easing(R.string.noise_ease_x, "easeTypeX", key.optInt("easeTypeX"));
                easing(R.string.noise_ease_y, "easeTypeY", key.optInt("easeTypeY"));
            }

            help(R.string.noise_animation_help);
        } catch (JSONException ex) { owner.error(); }
    }
    private JSONObject key(JSONObject json) throws JSONException {
        return json.getJSONArray(owner.document.channel.json).getJSONObject(owner.document.selectedKey);
    }
    private void anchorPresets() {
        NoiseField target = owner.document.field(); Channel channel = owner.document.channel;
        int index = owner.document.selectedKey;
        LinearLayout grid = owner.column();
        TextView hint = owner.text(R.string.noise_anchor_presets_help);
        hint.setPadding(owner.dp(12), owner.dp(4), owner.dp(12), owner.dp(4)); grid.addView(hint);
        android.app.AlertDialog[] dialog = new android.app.AlertDialog[1];
        String[] symbols = {"↖", "↑", "↗", "←", "•", "→", "↙", "↓", "↘"};
        for (int row = 0; row < 3; row++) {
            LinearLayout line = owner.row(); grid.addView(line);
            for (int column = 0; column < 3; column++) {
                final int x = column * 50, y = (2 - row) * 50;
                Button button = owner.action(line, symbols[row * 3 + column], () -> {
                    EditText ax = owner.form.findViewWithTag(R.string.noise_anchor_x);
                    EditText ay = owner.form.findViewWithTag(R.string.noise_anchor_y);
                    if (owner.document.field() == target && owner.document.channel == channel && owner.document.selectedKey == index
                            && ax != null && ay != null) {
                        batchSource = new Object();
                        try { ax.setText(owner.number(x)); ay.setText(owner.number(y)); }
                        finally { batchSource = null; source = session = null; }
                    }
                    dialog[0].dismiss();
                });
                button.setLayoutParams(new LinearLayout.LayoutParams(0, owner.dp(32), 1));
                UiText.description(button, () -> UiText.resources(owner.getContext()).getString(R.string.noise_anchor_preset, x, y));
            }
        }
        dialog[0] = owner.show(new UiDialogs.Builder(owner.getContext()).setTitle(R.string.noise_anchor_presets)
                .setView(grid).setNegativeButton(android.R.string.cancel, null));
    }
    private void easing(int label, String property, int initial) {
        EasingSelector picker = EasingSelector.addNoiseTo(owner.form, label, initial);
        picker.setOnChanged(() -> change(json -> key(json).put(property, picker.type())));
    }
    private void stepKey(int delta) {
        if (owner.document.field() == null) return;
        List<Integer> keys = NoiseFieldEdits.sortedKeys(owner.document.field(), owner.document.channel);
        if (keys.isEmpty()) return;
        int index = keys.indexOf(owner.document.selectedKey);
        owner.document.selectedKey = keys.get(Math.max(0, Math.min(keys.size() - 1, index + delta)));
        owner.rebuild();
        owner.seek(owner.document.field().toJson().optJSONArray(owner.document.channel.json)
                .optJSONObject(owner.document.selectedKey).optDouble("time"));
    }
    void keys() {
        if (owner.document.field() == null) return;
        NoiseField field = owner.document.field();
        Channel channel = owner.document.channel;
        List<Integer> indices = NoiseFieldEdits.sortedKeys(field, channel);
        owner.show(new UiDialogs.Builder(owner.getContext()).setTitle(R.string.noise_keys).setItems(() -> {
            CharSequence[] labels = new CharSequence[indices.size()];
            for (int i = 0; i < labels.length; i++) {
                int index = indices.get(i);
                labels[i] = UiText.resources(owner.getContext()).getString(R.string.noise_key, index + 1,
                        owner.timeLabel(field.toJson().optJSONArray(channel.json).optJSONObject(index).optDouble("time")));
            }
            return labels;
        }, (dialog, which) -> {
            if (owner.document.field() != field) return;
            owner.document.selectKey(channel, indices.get(which)); owner.rebuild();
            owner.seek(field.toJson().optJSONArray(channel.json).optJSONObject(indices.get(which)).optDouble("time"));
        }));
    }
}
