package com.xpe.mobile;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.LinkedHashMap;
import java.util.Map;

/** A real embedded editor page, not a floating Dialog/Window. */
final class EditorPanelWindow implements DialogInterface {
    static final int ACTION_HEIGHT_DP = 40;
    final CharSequence title;
    final View content;
    final LinearLayout actions;
    int tab;
    final Object selectionTarget;
    final boolean retainOnLineChange;
    private final com.xpe.mobile.model.ChartDocument chart;
    private final com.xpe.mobile.model.JudgeLine line;
    private final WorkbenchView workbench;
    private final Map<Integer, Button> buttons = new LinkedHashMap<>();
    private OnShowListener onShow;
    private boolean showing;

    private EditorPanelWindow(Builder builder) {
        title = builder.title;
        workbench = builder.workbench;
        selectionTarget = builder.selectionTarget;
        tab = builder.tab;
        retainOnLineChange = builder.retainOnLineChange;
        chart = workbench.editor().getChart();
        line = workbench.editor().getCurrentJudgeLine();
        actions = builder.actionsView == null ? new LinearLayout(builder.context) : builder.actionsView;
        for (Map.Entry<Integer, Action> entry : builder.actions.entrySet()) {
            int which = entry.getKey();
            Action action = entry.getValue();
            Button button = new Button(builder.context) {
                @Override public boolean performClick() {
                    if (which != BUTTON_NEGATIVE && !scopeValid()) {
                        android.widget.Toast.makeText(builder.context, R.string.validation_target_changed,
                                android.widget.Toast.LENGTH_SHORT).show();
                        dismiss();
                        return true;
                    }
                    return super.performClick();
                }
            };
            button.setText(action.label);
            button.setAllCaps(false);
            button.setTextSize(12f);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setMinHeight(0);
            button.setMinimumHeight(0);
            button.setGravity(android.view.Gravity.CENTER);
            button.setPadding(WorkbenchView.dp(builder.context, 3), 0,
                    WorkbenchView.dp(builder.context, 3), 0);
            buttons.put(which, button);
            actions.addView(button, new LinearLayout.LayoutParams(0,
                    WorkbenchView.dp(builder.context, ACTION_HEIGHT_DP), 1f));
            button.setOnClickListener(view -> {
                if (action.listener != null) action.listener.onClick(this, which);
                dismiss();
            });
        }
        // Fields and actions share one scrollable page; fixed bottom rows cannot starve the form.
        ScrollView scroll = builder.content instanceof ScrollView
                ? (ScrollView) builder.content : new ScrollView(builder.context);
        View fields = builder.content;
        if (fields == scroll) {
            fields = scroll.getChildAt(0);
            scroll.removeView(fields);
        }
        LinearLayout body = new LinearLayout(builder.context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(fields, new LinearLayout.LayoutParams(-1, -2));
        if (actions.getChildCount() > 0)
            body.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        scroll.setFillViewport(true);
        scroll.setNestedScrollingEnabled(true);
        scroll.setClipToPadding(false);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        content = scroll;
    }

    Button getButton(int which) { return buttons.get(which); }
    boolean scopeValid() {
        return chart == workbench.editor().getChart()
                && (retainOnLineChange || line == workbench.editor().getCurrentJudgeLine())
                && (selectionTarget == null || workbench.editor().getSelectedPropertyTarget() == null
                    || selectionTarget == workbench.editor().getSelectedPropertyTarget());
    }
    void setOnShowListener(OnShowListener listener) { onShow = listener; }
    void show() {
        if (showing) return;
        showing = true;
        if (!workbench.push(this)) { showing = false; return; }
        if (onShow != null) onShow.onShow(this);
    }
    @Override public void dismiss() {
        if (!showing) return;
        showing = false;
        workbench.remove(this);
    }
    @Override public void cancel() { dismiss(); }
    void abandoned() { showing = false; }

    private static final class Action {
        final CharSequence label;
        final OnClickListener listener;
        Action(CharSequence label, OnClickListener listener) {
            this.label = label;
            this.listener = listener;
        }
    }

    static final class Builder {
        final Context context;
        final WorkbenchView workbench;
        CharSequence title = "";
        View content;
        LinearLayout actionsView;
        boolean retainOnLineChange;
        Object selectionTarget;
        int tab = -1;
        final Map<Integer, Action> actions = new LinkedHashMap<>();
        Builder(Context context, WorkbenchView workbench) {
            this.context = context;
            this.workbench = workbench;
        }
        Builder setTitle(int resource) { return setTitle(context.getText(resource)); }
        Builder setTitle(CharSequence value) { title = value; return this; }
        Builder setView(View view) { content = view; return this; }
        // Custom tool actions share the same full-width row as the standard Apply/Cancel buttons.
        Builder setActionsView(LinearLayout view) { actionsView = view; return this; }
        Builder selectionProperties(Object target, int originTab) {
            selectionTarget = target;
            tab = originTab;
            return this;
        }
        Builder retainOnLineChange() { retainOnLineChange = true; return this; }
        Builder setNegativeButton(int resource, OnClickListener listener) {
            actions.put(BUTTON_NEGATIVE, new Action(context.getText(resource), listener));
            return this;
        }
        Builder setPositiveButton(int resource, OnClickListener listener) {
            actions.put(BUTTON_POSITIVE, new Action(context.getText(resource), listener));
            return this;
        }
        Builder setPositiveButton(String label, OnClickListener listener) {
            actions.put(BUTTON_POSITIVE, new Action(label, listener));
            return this;
        }
        Builder setNegativeButton(String label, OnClickListener listener) {
            actions.put(BUTTON_NEGATIVE, new Action(label, listener));
            return this;
        }
        Builder setNeutralButton(int resource, OnClickListener listener) {
            actions.put(BUTTON_NEUTRAL, new Action(context.getText(resource), listener));
            return this;
        }
        EditorPanelWindow create() {
            if (content == null || workbench == null) throw new IllegalStateException("Panel host required");
            return new EditorPanelWindow(this);
        }
    }
}
