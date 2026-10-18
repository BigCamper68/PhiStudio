package com.xpe.mobile.i18n;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import com.xpe.mobile.R;
import java.util.function.Supplier;

/** Explicit bindings owned by each view tree; no global listeners or translated-text lookup. */
public final class UiText {
    private UiText() { }
    public interface ResourceOwner { Resources uiResources(); }

    public static Resources resources(Context context) {
        Context current = context;
        while (true) {
            if (current instanceof ResourceOwner) return ((ResourceOwner) current).uiResources();
            if (!(current instanceof ContextWrapper)) return context.getResources();
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == null || next == current) return context.getResources();
            current = next;
        }
    }
    public static String string(Context context, int id, Object... args) {
        return resources(context).getString(id, args);
    }
    public static void text(TextView view, int id) { text(view, () -> resources(view.getContext()).getText(id)); }
    public static void text(TextView view, Supplier<? extends CharSequence> value) {
        Runnable binding = () -> view.setText(value.get());
        view.setTag(R.id.i18n_text, binding);
        binding.run();
    }
    public static void description(View view, int id) {
        description(view, () -> resources(view.getContext()).getText(id));
    }
    public static void description(View view, Supplier<? extends CharSequence> value) {
        Runnable binding = () -> view.setContentDescription(value.get());
        view.setTag(R.id.i18n_description, binding);
        binding.run();
    }
    public static void hint(TextView view, int id) {
        Runnable binding = () -> view.setHint(resources(view.getContext()).getText(id));
        view.setTag(R.id.i18n_hint, binding);
        binding.run();
    }
    public static void onRelocalize(View view, Runnable binding) { view.setTag(R.id.i18n_refresh, binding); }

    public static void spinner(Spinner spinner, int array) {
        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<>(spinner.getContext(),
                android.R.layout.simple_spinner_item, new java.util.ArrayList<>(
                java.util.Arrays.asList(resources(spinner.getContext()).getTextArray(array))));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        onRelocalize(spinner, () -> {
            // Mutate entries in the same adapter. Do not reselect or invoke selection listeners.
            adapter.setNotifyOnChange(false);
            adapter.clear();
            adapter.addAll(resources(spinner.getContext()).getTextArray(array));
            adapter.notifyDataSetChanged();
        });
    }

    public static void entries(Spinner spinner, Supplier<java.util.List<String>> entries) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(spinner.getContext(),
                android.R.layout.simple_spinner_item, new java.util.ArrayList<>(entries.get()));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        onRelocalize(spinner, () -> {
            adapter.setNotifyOnChange(false);
            adapter.clear(); adapter.addAll(entries.get()); adapter.notifyDataSetChanged();
        });
    }

    public static void relocalize(View view) {
        for (int tag : new int[]{R.id.i18n_text, R.id.i18n_hint, R.id.i18n_description, R.id.i18n_refresh}) {
            Object binding = view.getTag(tag);
            if (binding instanceof Runnable) ((Runnable) binding).run();
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) relocalize(group.getChildAt(i));
        }
        view.invalidate();
    }
}
