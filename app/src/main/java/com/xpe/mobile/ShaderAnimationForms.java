package com.xpe.mobile;

import com.xpe.mobile.i18n.UiText;

import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.preview.ShaderTween;
import com.xpe.mobile.shadereditor.ShaderDocument;
import org.json.JSONArray;
import org.json.JSONObject;

/** ShaderAnimationForms responsibilities extracted from ShaderEditorView. */
final class ShaderAnimationForms {
    private final ShaderEditorView delegateOwner;

    ShaderAnimationForms(ShaderEditorView delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void animationForm(ShaderDocument.Parameter param, LinearLayout navigation) {
        Object value = delegateOwner.document.value(param);
        if (!delegateOwner.document.animated(value)) {
            delegateOwner.action(navigation, R.string.ui_create_animation, () -> {
                delegateOwner.document.value(param.name, delegateOwner.document.animate(param));
                delegateOwner.eventIndex = 0;
                delegateOwner.changed(true);
            });
            return;
        }
        JSONArray events = (JSONArray) value;
        if (delegateOwner.eventIndex < 0) {
            navigation.addView(delegateOwner.text(R.string.ui_tap_an_animation_event_to_select_it));
            return;
        }
        delegateOwner.eventIndex = Math.max(0, Math.min(delegateOwner.eventIndex, events.length() - 1));
        JSONObject event = events.optJSONObject(delegateOwner.eventIndex);
        navigation.addView(delegateOwner.text(() -> delegateOwner.getContext().getString(R.string.shader_event_position, delegateOwner.eventIndex + 1, events.length())),
            new LinearLayout.LayoutParams(0, -2, 1));
        delegateOwner.action(navigation, "‹", () -> {
            delegateOwner.eventIndex = Math.max(0, delegateOwner.eventIndex - 1);
            delegateOwner.buildForm();
            delegateOwner.timeline.invalidate();
        });
        delegateOwner.action(navigation, "›", () -> {
            delegateOwner.eventIndex = Math.min(events.length() - 1, delegateOwner.eventIndex + 1);
            delegateOwner.buildForm();
            delegateOwner.timeline.invalidate();
        });
        delegateOwner.action(navigation, R.string.ui_event, () -> {
            try {
                JSONArray next = new JSONArray(events.toString());
                JSONObject last = events.getJSONObject(events.length() - 1);
                JSONObject clone = new JSONObject(last.toString());
                double from = delegateOwner.document.tempo().time(last.getJSONArray("endTime"));
                clone.put("start", last.get("end"))
                    .put("startTime", delegateOwner.document.timestamp(from))
                    .put("endTime",
                        delegateOwner.document.timestamp(
                            delegateOwner.document.tempo().seconds(delegateOwner.document.tempo().beatAt(from) + 1)));
                next.put(clone);
                delegateOwner.document.value(param.name, next);
                delegateOwner.eventIndex = next.length() - 1;
                delegateOwner.changed(true);
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex);
            }
        });
        delegateOwner.action(navigation, R.string.ui_delete_event, () -> {
            JSONArray next = ShaderEditorView.copy(events);
            next.remove(delegateOwner.eventIndex);
            delegateOwner.document.value(param.name, next.length() == 0 ? event.opt("end") : next);
            delegateOwner.eventIndex = Math.max(0, delegateOwner.eventIndex - 1);
            delegateOwner.changed(true);
        });
        delegateOwner.action(navigation, R.string.ui_constant, () -> {
            delegateOwner.document.value(param.name, event.opt("start"));
            delegateOwner.changed(true);
        });
        LinearLayout times = delegateOwner.row();
        delegateOwner.form.addView(times);
        EditText start = delegateOwner.shaderEffectForms.field(
                     times, R.string.ui_start, ShaderEditorView.fmt(delegateOwner.document.axis(delegateOwner.shaderAnimationForms.eventTime(event, "startTime"), delegateOwner.beats))),
                 end = delegateOwner.shaderEffectForms.field(times, R.string.ui_end, ShaderEditorView.fmt(delegateOwner.document.axis(delegateOwner.shaderAnimationForms.eventTime(event, "endTime"), delegateOwner.beats)));
        LinearLayout from = delegateOwner.row(), to = param.size == 1 ? from : delegateOwner.row();
        delegateOwner.form.addView(from);
        if (to != from)
            delegateOwner.form.addView(to);
        ColorPickerButton startColor = param.size == 4
                ? ColorPickerButton.addTo(from, R.string.ui_from, delegateOwner.shaderEffectForms.rgbaColor(event.opt("start")), true) : null;
        ColorPickerButton endColor = param.size == 4
                ? ColorPickerButton.addTo(to, R.string.ui_to, delegateOwner.shaderEffectForms.rgbaColor(event.opt("end")), true) : null;
        EditText[] startValue = param.size == 4 ? null : delegateOwner.shaderEffectForms.values(from, event.opt("start"), param.size, R.string.shader_from),
                   endValue = param.size == 4 ? null : delegateOwner.shaderEffectForms.values(to, event.opt("end"), param.size, R.string.shader_to);
        LinearLayout easing = delegateOwner.row();
        delegateOwner.form.addView(easing);
        Spinner ease = new Spinner(delegateOwner.getContext());
        UiText.spinner(ease, R.array.event_easing_entries);
        ease.setSelection(Math.max(0, Math.min(28, event.optInt("easingType", 1) - 1)));
        easing.addView(ease, new LinearLayout.LayoutParams(0, delegateOwner.dp(36), 2));
        EditText left = delegateOwner.shaderEffectForms.field(easing, R.string.ui_left, ShaderEditorView.fmt(event.optDouble("easingLeft", 0))),
                 right = delegateOwner.shaderEffectForms.field(easing, R.string.ui_right, ShaderEditorView.fmt(event.optDouble("easingRight", 1)));
        delegateOwner.action(easing, R.string.action_apply, () -> {
            double a = delegateOwner.time(start), b = delegateOwner.time(end), l = delegateOwner.shaderEffectForms.number(left), r = delegateOwner.shaderEffectForms.number(right);
            if (b <= a || l < 0 || r > 1 || r <= l
                || !Float.isFinite(ShaderTween.apply(
                    ease.getSelectedItemPosition() + 1, .5f, (float) l, (float) r)))
                throw new com.xpe.mobile.i18n.UiErrors.ValidationException(R.string.ui_check_time_range_and_easing_window_0_left_right_1);
            try {
                JSONObject edited = new JSONObject(event.toString());
                edited.put("startTime", delegateOwner.document.timestamp(a))
                    .put("endTime", delegateOwner.document.timestamp(b))
                    .put("start", startColor == null ? delegateOwner.shaderEffectForms.value(startValue, param.size) : delegateOwner.shaderEffectForms.colorValue(startColor.getColor()))
                    .put("end", endColor == null ? delegateOwner.shaderEffectForms.value(endValue, param.size) : delegateOwner.shaderEffectForms.colorValue(endColor.getColor()))
                    .put("easingType", ease.getSelectedItemPosition() + 1)
                    .put("easingLeft", l)
                    .put("easingRight", r);
                JSONArray next = ShaderEditorView.copy(events);
                next.put(delegateOwner.eventIndex, edited);
                delegateOwner.document.value(param.name, next);
                delegateOwner.changed(true);
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex);
            }
        });
        delegateOwner.form.addView(delegateOwner.text(R.string.ui_tap_an_event_in_the_right_timeline_to_edit_it_drag_its_start_end_edge_times_use_the_effect_bpm_map));
    }

    static JSONArray copy(JSONArray array) {
        try {
            return new JSONArray(array.toString());
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    double eventTime(JSONObject event, String key) {
        try {
            return delegateOwner.document.tempo().time(event.getJSONArray(key));
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    void bpmForm() {
        delegateOwner.form.addView(delegateOwner.text(R.string.ui_independent_effect_tempo_existing_effect_keyframe_beat_positions_stay_fixed_when_this_map_changes));
        Object bpm = delegateOwner.document.bpm();
        JSONArray map;
        try {
            map = bpm instanceof JSONArray
                ? ShaderEditorView.copy((JSONArray) bpm)
                : new JSONArray().put(
                      new JSONObject().put("time", BeatTime.zero().toJson()).put("bpm", bpm));
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
        for (int i = 0; i < map.length(); i++) {
            final int index = i;
            JSONObject item = map.optJSONObject(i);
            LinearLayout row = delegateOwner.row();
            delegateOwner.form.addView(row);
            EditText beat = delegateOwner.shaderEffectForms.field(row, R.string.ui_fx_beat, ShaderEditorView.fmt(delegateOwner.shaderAnimationForms.ShaderDocumentBeat(item))),
                     value = delegateOwner.shaderEffectForms.field(row, R.string.ui_bpm, ShaderEditorView.fmt(item.optDouble("bpm")));
            delegateOwner.action(row, R.string.action_apply, () -> {
                try {
                    JSONArray next = ShaderEditorView.copy(map);
                    next.getJSONObject(index)
                        .put("time", BeatTime.parseFlexible(beat.getText().toString()).toJson())
                        .put("bpm", delegateOwner.shaderEffectForms.number(value));
                    delegateOwner.document.bpm(next);
                    delegateOwner.changed(true);
                } catch (Exception ex) {
                    throw new IllegalArgumentException(ex);
                }
            });
            if (map.length() > 1)
                delegateOwner.action(row, R.string.storyboard_delete, () -> {
                    JSONArray next = ShaderEditorView.copy(map);
                    next.remove(index);
                    delegateOwner.document.bpm(next);
                    delegateOwner.changed(true);
                });
        }
        delegateOwner.action(delegateOwner.form, R.string.shader_add_bpm_change, () -> {
            try {
                JSONArray next = ShaderEditorView.copy(map);
                double current = Math.max(0, delegateOwner.document.tempo().beatAt(delegateOwner.editor.shaderEditorSeconds()));
                double last = delegateOwner.shaderAnimationForms.ShaderDocumentBeat(next.getJSONObject(next.length() - 1));
                next.put(new JSONObject()
                        .put("time",
                            BeatTime.fromDouble(Math.max(last + 1, current), 1000000).toJson())
                        .put("bpm", 120));
                delegateOwner.document.bpm(next);
                delegateOwner.changed(true);
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex);
            }
        });
    }

    double ShaderDocumentBeat(JSONObject item) {
        try {
            return com.xpe.mobile.preview.ShaderTimeline.Tempo.beat(item.getJSONArray("time"));
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }
}
