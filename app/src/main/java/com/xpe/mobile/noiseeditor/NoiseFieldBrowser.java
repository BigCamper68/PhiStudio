package com.xpe.mobile.noiseeditor;

import android.app.AlertDialog;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.xpe.mobile.R;
import com.xpe.mobile.i18n.UiDialogs;
import com.xpe.mobile.i18n.UiText;
import com.xpe.mobile.model.NoiseField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Search and time filtering never reorder the native blockAreaList or change raw indices. */
final class NoiseFieldBrowser {
    private final NoiseFieldEditorView owner;
    private final double seconds;
    private final List<NoiseField> fields;
    final LinearLayout body, filters;
    final EditText search;
    final ListView list;
    final TextView count;
    final List<Integer> indices = new ArrayList<>();
    private final HorizontalScrollView filterBar;
    private final BaseAdapter adapter;
    private int filter;

    NoiseFieldBrowser(NoiseFieldEditorView owner) {
        this.owner = owner; seconds = owner.time();
        fields = new ArrayList<>(owner.document.chart.noiseFields);
        body = new LinearLayout(owner.getContext()) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int screen = owner.getRootView().getHeight();
                if (screen <= 0) screen = getResources().getDisplayMetrics().heightPixels;
                int height = Math.max(owner.dp(172), Math.min(owner.dp(400), screen - owner.dp(150)));
                if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) height = Math.min(height, MeasureSpec.getSize(heightSpec));
                // Keep the search results usable with the landscape keyboard open.
                filterBar.setVisibility(height < owner.dp(160) ? GONE : VISIBLE);
                count.setVisibility(height < owner.dp(160) ? GONE : VISIBLE);
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
        };
        body.setOrientation(LinearLayout.VERTICAL); body.setPadding(owner.dp(8), 0, owner.dp(8), 0);
        search = new EditText(owner.getContext());
        search.setSingleLine(true); search.setTextSize(12);
        search.setImeOptions(EditorInfo.IME_ACTION_DONE);
        search.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            InputMethodManager keyboard = (InputMethodManager) owner.getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
            search.clearFocus(); return true;
        });
        UiText.hint(search, R.string.noise_search); UiText.description(search, R.string.noise_search);
        body.addView(search, new LinearLayout.LayoutParams(-1, owner.dp(40)));
        filters = owner.row();
        filterBar = new HorizontalScrollView(owner.getContext());
        filterBar.setHorizontalScrollBarEnabled(false); filterBar.addView(filters);
        body.addView(filterBar, new LinearLayout.LayoutParams(-1, owner.dp(36)));
        int[] names = {R.string.noise_filter_all, R.string.noise_filter_here, R.string.noise_normal, R.string.noise_subtract_short};
        for (int i = 0; i < names.length; i++) {
            final int value = i;
            owner.action(filters, names[i], () -> { filter = value; refresh(); });
        }
        count = owner.text(""); count.setTextColor(0xff9fbbcf); count.setSingleLine(true);
        body.addView(count, new LinearLayout.LayoutParams(-1, owner.dp(24)));
        list = new ListView(owner.getContext()); list.setDividerHeight(owner.dp(1));
        TextView empty = owner.text(R.string.noise_no_matches);
        body.addView(empty); list.setEmptyView(empty);
        body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        adapter = new BaseAdapter() {
            @Override public int getCount() { return indices.size(); }
            @Override public Object getItem(int position) { return fields.get(indices.get(position)); }
            @Override public long getItemId(int position) { return indices.get(position); }
            @Override public boolean hasStableIds() { return true; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                LinearLayout row = recycled instanceof LinearLayout ? (LinearLayout) recycled : owner.column();
                if (row.getChildCount() == 0) {
                    row.setPadding(owner.dp(10), owner.dp(6), owner.dp(10), owner.dp(6));
                    TextView title = owner.text(""); title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
                    row.addView(title, new LinearLayout.LayoutParams(-1, -2));
                    TextView detail = owner.text(""); detail.setTextSize(11); detail.setTextColor(0xffadc2d1);
                    row.addView(detail, new LinearLayout.LayoutParams(-1, -2));
                }
                int index = indices.get(position); NoiseField field = fields.get(index);
                ((TextView) row.getChildAt(0)).setText(label(index));
                ((TextView) row.getChildAt(1)).setText(detail(field));
                row.setSelected(index == owner.document.selected);
                row.setBackgroundColor(row.isSelected() ? 0xff204e4c : Color.TRANSPARENT);
                return row;
            }
        };
        list.setAdapter(adapter);
        search.addTextChangedListener(NoiseFieldEditorView.watcher(this::refresh));
        UiText.onRelocalize(body, this::refresh); refresh();
    }
    private String label(int index) {
        String name = fields.get(index).toJson().optString(NoiseFieldEdits.LABEL, "").trim();
        return name.isEmpty() ? UiText.resources(owner.getContext()).getString(R.string.noise_field, index + 1) : name;
    }
    private String detail(NoiseField field) {
        int keys = field.move.size() + field.scale.size() + field.rotate.size();
        String type = owner.tr(field.subtract ? R.string.noise_subtract_short : R.string.noise_normal);
        String state = owner.tr(NoiseFieldSelectionOverlay.stateLabel(NoiseFieldPresentation.state(field, seconds)));
        return UiText.resources(owner.getContext()).getString(R.string.noise_field_detail, type, state, keys)
                + "\n" + owner.timeLabel(field.appear) + " — " + owner.timeLabel(field.disappear);
    }
    private void refresh() {
        indices.clear();
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < fields.size(); i++) {
            NoiseField field = fields.get(i);
            if (filter == 1 && NoiseFieldPresentation.state(field, seconds) == NoiseFieldPresentation.State.OUTSIDE
                    || filter == 2 && field.subtract || filter == 3 && !field.subtract) continue;
            if (query.isEmpty() || (label(i) + " " + (i + 1) + " " + detail(field)).toLowerCase(Locale.ROOT).contains(query)) indices.add(i);
        }
        indices.sort(Comparator.comparingDouble(i -> fields.get(i).appear));
        for (int i = 0; i < filters.getChildCount(); i++) {
            View button = filters.getChildAt(i); button.setSelected(i == filter);
            button.setBackgroundColor(i == filter ? 0xff2d8474 : 0xff353f4a);
        }
        count.setText(UiText.resources(owner.getContext()).getString(R.string.noise_field_count,
                indices.size(), fields.size(), owner.timeLabel(seconds)));
        adapter.notifyDataSetChanged();
        int selected = indices.indexOf(owner.document.selected);
        if (selected >= 0) list.setSelection(selected);
    }
    AlertDialog show() {
        AlertDialog dialog = owner.show(new UiDialogs.Builder(owner.getContext()).setTitle(R.string.noise_fields)
                .setView(body).setNegativeButton(android.R.string.cancel, null));
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = indices.get(position);
            if (owner.document.valid() && index < owner.document.count()
                    && owner.document.chart.noiseFields.get(index) == fields.get(index)) owner.selectField(index);
            dialog.dismiss();
        });
        return dialog;
    }
}
