package com.xpe.mobile;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import com.xpe.mobile.editor.EasingPreviewView;
import com.xpe.mobile.model.Easing;
import com.xpe.mobile.preview.ShaderTween;
import java.util.function.BooleanSupplier;

/** Shared named curve picker, with a live graph and graphs in the dropdown. */
@SuppressLint("ViewConstructor") // Constructed programmatically with its label and initial curve.
final class EasingSelector extends LinearLayout {
    final Spinner spinner;
    final EasingPreviewView preview;
    private Runnable changed;
    private double left, right = 1;
    private boolean shader;

    EasingSelector(Context context, String label, int type) {
        super(context);
        setOrientation(VERTICAL);
        TextView title = new TextView(context);
        title.setText(label);
        title.setTextSize(14);
        title.setPadding(0, dp(8), 0, dp(3));
        addView(title);
        String[] names = getResources().getStringArray(R.array.event_easing_entries);
        spinner = new Spinner(context);
        spinner.setContentDescription(label);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context,
                android.R.layout.simple_spinner_item, names) {
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                LinearLayout row;
                if (convertView instanceof LinearLayout && convertView.getTag() instanceof TextView) {
                    row = (LinearLayout) convertView;
                } else {
                    row = new LinearLayout(context);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(12), dp(2), dp(8), dp(2));
                    TextView name = new TextView(context);
                    name.setTextSize(15);
                    row.addView(name, new LayoutParams(0, -2, 1));
                    EasingPreviewView graph = new EasingPreviewView(context);
                    graph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    row.addView(graph, new LayoutParams(dp(64), dp(64)));
                    row.setTag(name);
                }
                ((TextView) row.getTag()).setText(getItem(position));
                EasingPreviewView graph = (EasingPreviewView) row.getChildAt(1);
                if (shader) graph.setEvaluator(t -> ShaderTween.apply(position + 1, (float) t, 0, 1));
                else graph.setCurve(position + 1, 0, 1, false, 0, 0, 1, 1);
                row.setContentDescription(getItem(position));
                return row;
            }
        };
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(names.length - 1, type - Easing.MIN_TYPE)));
        addView(spinner, new LayoutParams(-1, -2));
        preview = new EasingPreviewView(context);
        preview.setContentDescription(context.getString(R.string.field_event_easing_preview_description));
        addView(preview, new LayoutParams(-1, dp(148)));
        EditorDialogSection.setSpinnerChangeListener(spinner, () -> {
            refresh();
            if (changed != null) changed.run();
        });
        refresh();
    }

    static EasingSelector addTo(LinearLayout parent, String label, int type) {
        EasingSelector picker = new EasingSelector(parent.getContext(), label, type);
        parent.addView(picker, new LayoutParams(-1, -2));
        return picker;
    }

    int type() { return spinner.getSelectedItemPosition() + Easing.MIN_TYPE; }
    void setOnChanged(Runnable action) { changed = action; }
    void setShader(boolean value) { shader = value; refresh(); }
    void setWindow(double from, double to) { left = from; right = to; refresh(); }

    void bindWindow(EditText from, EditText to, BooleanSupplier enabled) {
        Runnable update = () -> setWindow(enabled.getAsBoolean() ? number(from, 0) : 0,
                enabled.getAsBoolean() ? number(to, 1) : 1);
        EditorDialogSection.watch(update, from, to);
        update.run();
    }

    private void refresh() {
        if (shader) {
            int type = type(); double a = left, b = right;
            preview.setEvaluator(t -> ShaderTween.apply(type, (float) t, (float) a, (float) b));
        } else preview.setCurve(type(), left, right, false, 0, 0, 1, 1);
    }

    private static double number(EditText input, double fallback) {
        try { return EditorDialogSection.parseDouble(input); }
        catch (IllegalArgumentException error) { return fallback; }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
