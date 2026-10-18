package com.xpe.mobile.i18n;

import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.view.View;
import java.util.function.Supplier;

/** Resource identities for existing platform dialogs. The owning Activity controls their lifetime. */
public final class UiDialogs {
    private UiDialogs() { }
    public interface Owner { void registerLocalizedDialog(AlertDialog dialog); }

    public static final class Builder extends AlertDialog.Builder {
        private Supplier<? extends CharSequence> title, message;
        private int positive, negative, neutral;
        private android.widget.ArrayAdapter<CharSequence> items;
        private Supplier<CharSequence[]> itemSource;
        public Builder(Context context) { super(context); }
        public Builder(Context context, int theme) { super(context, theme); }
        @Override public Builder setTitle(int id) { return setTitle(() -> UiText.resources(getContext()).getText(id)); }
        public Builder setTitle(Supplier<? extends CharSequence> source) {
            title = source; super.setTitle(source.get()); return this;
        }
        @Override public Builder setTitle(CharSequence value) { title = null; super.setTitle(value); return this; }
        @Override public Builder setMessage(int id) { return setMessage(() -> UiText.resources(getContext()).getText(id)); }
        public Builder setMessage(Supplier<? extends CharSequence> source) {
            message = source; super.setMessage(source.get()); return this;
        }
        @Override public Builder setMessage(CharSequence value) { message = null; super.setMessage(value); return this; }
        @Override public Builder setView(View view) { super.setView(view); return this; }
        public Builder setItems(Supplier<CharSequence[]> source, DialogInterface.OnClickListener listener) {
            itemSource = source;
            items = new android.widget.ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1,
                    new java.util.ArrayList<>(java.util.Arrays.asList(source.get())));
            super.setAdapter(items, listener); return this;
        }
        @Override public Builder setPositiveButton(int id, DialogInterface.OnClickListener listener) {
            positive = id; super.setPositiveButton(UiText.resources(getContext()).getText(id), listener); return this;
        }
        @Override public Builder setNegativeButton(int id, DialogInterface.OnClickListener listener) {
            negative = id; super.setNegativeButton(UiText.resources(getContext()).getText(id), listener); return this;
        }
        @Override public Builder setNeutralButton(int id, DialogInterface.OnClickListener listener) {
            neutral = id; super.setNeutralButton(UiText.resources(getContext()).getText(id), listener); return this;
        }
        @Override public AlertDialog create() {
            AlertDialog dialog = super.create();
            UiText.onRelocalize(dialog.getWindow().getDecorView(), () -> {
                if (title != null) dialog.setTitle(title.get());
                if (message != null) dialog.setMessage(message.get());
                if (itemSource != null) {
                    items.setNotifyOnChange(false); items.clear(); items.addAll(itemSource.get()); items.notifyDataSetChanged();
                }
                for (int[] entry : new int[][]{{DialogInterface.BUTTON_POSITIVE, positive},
                        {DialogInterface.BUTTON_NEGATIVE, negative}, {DialogInterface.BUTTON_NEUTRAL, neutral}})
                    if (entry[1] != 0 && dialog.getButton(entry[0]) != null)
                        dialog.getButton(entry[0]).setText(UiText.resources(getContext()).getText(entry[1]));
            });
            Context context = getContext();
            while (context != null) {
                if (context instanceof Owner) { ((Owner) context).registerLocalizedDialog(dialog); break; }
                if (!(context instanceof ContextWrapper)) break;
                Context next = ((ContextWrapper) context).getBaseContext();
                if (next == context) break;
                context = next;
            }
            return dialog;
        }
    }
}
