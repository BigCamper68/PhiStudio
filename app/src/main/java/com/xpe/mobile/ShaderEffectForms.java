package com.xpe.mobile;

import com.xpe.mobile.i18n.UiText;
import com.xpe.mobile.i18n.UiDialogs;

import android.app.AlertDialog;
import android.graphics.Color;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.shadereditor.ShaderDocument;
import org.json.JSONArray;
import org.json.JSONObject;

/** ShaderEffectForms responsibilities extracted from ShaderEditorView. */
final class ShaderEffectForms {
    private final ShaderEditorView delegateOwner;

    ShaderEffectForms(ShaderEditorView delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    EditText field(LinearLayout row, int label, String initial) {
        EditText field = field(row, "", initial);
        UiText.text((TextView) row.getChildAt(row.indexOfChild(field) - 1), label);
        return field;
    }
    EditText field(LinearLayout row, java.util.function.Supplier<String> label, String initial) {
        EditText field = field(row, "", initial);
        UiText.text((TextView) row.getChildAt(row.indexOfChild(field) - 1), label);
        return field;
    }
    EditText field(LinearLayout row, String label, String initial) {
        row.addView(delegateOwner.text(label), new LinearLayout.LayoutParams(-2, delegateOwner.dp(36)));
        EditText value = new EditText(delegateOwner.getContext());
        value.setText(initial);
        value.setTextColor(Color.WHITE);
        value.setTextSize(12);
        value.setSingleLine(true);
        value.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
            | InputType.TYPE_NUMBER_FLAG_SIGNED);
        value.setSelectAllOnFocus(true);
        row.addView(value, new LinearLayout.LayoutParams(0, delegateOwner.dp(40), 1));
        return value;
    }

    double number(EditText value) {
        double result = Double.parseDouble(value.getText().toString().trim());
        if (!Double.isFinite(result))
            throw new com.xpe.mobile.i18n.UiErrors.ValidationException(R.string.ui_enter_a_finite_value);
        return result;
    }

    double time(EditText field) {
        double value =
            delegateOwner.beats ? BeatTime.parseFlexible(field.getText().toString()).toDouble() : delegateOwner.shaderEffectForms.number(field);
        return delegateOwner.document.seconds(value, delegateOwner.beats);
    }

    void effectForm() {
        JSONObject effect = delegateOwner.document.selected();
        LinearLayout times = delegateOwner.row();
        delegateOwner.form.addView(times);
        TextView identity =
            delegateOwner.text("#" + (delegateOwner.document.selected + 1) + " · " + effect.optString("shader"));
        identity.setSingleLine(true);
        identity.setEllipsize(android.text.TextUtils.TruncateAt.END);
        times.addView(identity, new LinearLayout.LayoutParams(delegateOwner.dp(130), delegateOwner.dp(40)));
        CheckBox global = new CheckBox(delegateOwner.getContext());
        UiText.text(global, R.string.shader_global);
        global.setTextSize(12);
        global.setChecked(effect.optBoolean("global"));
        times.addView(global);
        global.setOnCheckedChangeListener((v, on) -> delegateOwner.safe(() -> {
            delegateOwner.document.global(on);
            delegateOwner.changed(false);
        }));
        EditText start = delegateOwner.shaderEffectForms.field(
                     times, R.string.ui_start, ShaderEditorView.fmt(delegateOwner.document.axis(delegateOwner.document.start(delegateOwner.document.selected), delegateOwner.beats))),
                 end = delegateOwner.shaderEffectForms.field(
                     times, R.string.ui_end, ShaderEditorView.fmt(delegateOwner.document.axis(delegateOwner.document.end(delegateOwner.document.selected), delegateOwner.beats)));
        delegateOwner.action(times, R.string.action_apply, () -> {
            delegateOwner.document.range(delegateOwner.document.selected, delegateOwner.time(start), delegateOwner.time(end));
            delegateOwner.changed(true);
            delegateOwner.timeline.invalidate();
        });
        LinearLayout tools = delegateOwner.row();
        delegateOwner.form.addView(tools);
        delegateOwner.action(tools, R.string.ui_jump_to_start, () -> {
            delegateOwner.editor.shaderEditorSeek(delegateOwner.document.start(delegateOwner.document.selected));
            delegateOwner.invalidatePreview();
        });
        delegateOwner.action(tools, R.string.controls_copy, () -> {
            delegateOwner.document.duplicate();
            delegateOwner.changed(true);
        });
        delegateOwner.action(tools, R.string.storyboard_delete,
            ()
                -> new UiDialogs.Builder(delegateOwner.getContext())
                    .setMessage(R.string.ui_delete_this_effect)
                    .setPositiveButton(R.string.storyboard_delete,
                        (d, w) -> {
                            delegateOwner.document.delete();
                            delegateOwner.parameter = null;
                            delegateOwner.changed(true);
                        })
                    .setNegativeButton(R.string.action_cancel, null)
                    .show());
        delegateOwner.action(tools, R.string.ui_earlier, () -> {
            delegateOwner.document.reorder(-1);
            delegateOwner.changed(true);
        });
        delegateOwner.action(tools, R.string.ui_later, () -> {
            delegateOwner.document.reorder(1);
            delegateOwner.changed(true);
        });
        delegateOwner.form.addView(delegateOwner.text(R.string.ui_local_game_viewport_before_particles_hud_global_complete_preview_after_hud_select_an_event_drag_s_e_handles_to_change_its_range));
    }

    EditText[] values(LinearLayout parent, Object value, int size, String prefix) {
        double[] values = delegateOwner.document.components(value, size);
        EditText[] fields = new EditText[size];
        String[] suffix = size == 2 ? new String[] {"X", "Y"} : new String[] {"R", "G", "B", "A"};
        for (int i = 0; i < size; i++)
            fields[i] = delegateOwner.shaderEffectForms.field(parent, prefix + (size == 1 ? "" : suffix[i]) + " ", ShaderEditorView.fmt(values[i]));
        return fields;
    }
    EditText[] values(LinearLayout parent, Object value, int size, int prefixResource) {
        double[] components = delegateOwner.document.components(value, size);
        EditText[] fields = new EditText[size];
        String[] suffix = size == 2 ? new String[]{"X", "Y"} : new String[]{"R", "G", "B", "A"};
        for (int i = 0; i < size; i++) {
            final int component = i;
            fields[i] = field(parent, () -> delegateOwner.getContext().getString(R.string.shader_value_component,
                    delegateOwner.getContext().getString(prefixResource), size == 1 ? "" : suffix[component]),
                    ShaderEditorView.fmt(components[i]));
        }
        return fields;
    }

    Object value(EditText[] fields, int size) {
        double[] values = new double[size];
        for (int i = 0; i < size; i++) values[i] = delegateOwner.shaderEffectForms.number(fields[i]);
        return delegateOwner.document.valueFrom(values, size);
    }

    void parameterForm(ShaderDocument.Parameter param, LinearLayout inputs) {
        Object value = delegateOwner.document.value(param);
        LinearLayout header = delegateOwner.row();
        delegateOwner.form.addView(header);
        delegateOwner.action(header, R.string.ui_animate, () -> {
            delegateOwner.parameter = param.name;
            if (!delegateOwner.document.animated(delegateOwner.document.value(param)))
                delegateOwner.document.value(param.name, delegateOwner.document.animate(param));
            delegateOwner.page = 2;
            delegateOwner.openProperties();
            delegateOwner.eventIndex = 0;
            delegateOwner.changed(true);
        });
        delegateOwner.action(header, R.string.ui_reset, () -> {
            delegateOwner.document.value(param.name, null);
            delegateOwner.changed(true);
        });
        if (delegateOwner.document.animated(value)) {
            inputs.addView(delegateOwner.text(R.string.ui_animated));
            delegateOwner.action(inputs, R.string.ui_edit_animation, () -> {
                delegateOwner.page = 2;
                delegateOwner.openProperties();
                delegateOwner.buildForm();
                delegateOwner.timeline.invalidate();
            });
            return;
        }
        if (param.size == 4) {
            ColorPickerButton color = ColorPickerButton.addTo(inputs, param.name, delegateOwner.shaderEffectForms.rgbaColor(value), true);
            color.setOnColorChanged(v -> { delegateOwner.document.value(param.name, delegateOwner.shaderEffectForms.colorValue(v)); delegateOwner.changed(true); });
            return;
        }
        EditText[] fields = delegateOwner.shaderEffectForms.values(inputs, value, param.size, "");
        delegateOwner.action(inputs, R.string.action_apply, () -> {
            delegateOwner.document.value(param.name, delegateOwner.shaderEffectForms.value(fields, param.size));
            delegateOwner.changed(true);
        });
        double[] initial = delegateOwner.document.components(value, param.size);
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
            SeekBar slider = new SeekBar(delegateOwner.getContext());
            slider.setMax(param.size == 4 ? 255 : 1000);
            slider.setProgress(
                (int) Math.round((original - minimum) / (maximum - minimum) * slider.getMax()));
            sliders.addView(slider, new LinearLayout.LayoutParams(0, delegateOwner.dp(28), 1));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(SeekBar v) {
                    delegateOwner.document.beginGesture();
                }
                public void onStopTrackingTouch(SeekBar v) {
                    delegateOwner.document.endGesture();
                    delegateOwner.changed(false);
                }
                public void onProgressChanged(SeekBar v, int progress, boolean user) {
                    if (!user)
                        return;
                    delegateOwner.safe(() -> {
                        double[] current = delegateOwner.document.components(delegateOwner.document.value(param), param.size);
                        current[component] = minimum + (maximum - minimum) * progress / v.getMax();
                        if (param.size == 4)
                            current[component] = Math.round(current[component]);
                        delegateOwner.document.value(param.name, delegateOwner.document.valueFrom(current, param.size));
                        fields[component].setText(ShaderEditorView.fmt(current[component]));
                        delegateOwner.changed(false);
                    });
                }
            });
        }
    }

    int rgbaColor(Object value) {
        double[] rgba = delegateOwner.document.components(value, 4);
        return Color.argb((int) rgba[3], (int) rgba[0], (int) rgba[1], (int) rgba[2]);
    }

    JSONArray colorValue(int color) {
        return new JSONArray().put(Color.red(color)).put(Color.green(color))
                .put(Color.blue(color)).put(Color.alpha(color));
    }
}
