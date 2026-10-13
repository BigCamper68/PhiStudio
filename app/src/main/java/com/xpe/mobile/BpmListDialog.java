package com.xpe.mobile;

import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;

/** BpmListDialog responsibilities extracted from EditorDialogController. */
final class BpmListDialog {
    private final EditorDialogController delegateOwner;

    BpmListDialog(EditorDialogController delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void showBpmList() {
        ChartDocument chart = delegateOwner.editorView.getChart();
        if (chart == null) return;
        if (chart.bpmChanges.isEmpty()) chart.bpmChanges.add(new BpmChange());
        chart.sortBpm();

        int pad = delegateOwner.dp(12);

        LinearLayout root = new LinearLayout(delegateOwner);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout columns = new LinearLayout(delegateOwner);
        columns.setOrientation(LinearLayout.VERTICAL);
        root.addView(columns, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView listScroll = new EditorListScrollView(delegateOwner);
        listScroll.setFillViewport(true);
        listScroll.setNestedScrollingEnabled(true);
        LinearLayout list = new LinearLayout(delegateOwner);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        columns.addView(listScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, delegateOwner.dp(192)));

        LinearLayout form = new LinearLayout(delegateOwner);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(0, delegateOwner.dp(6), 0, delegateOwner.dp(8));
        columns.addView(form, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView selectionTitle = new TextView(delegateOwner);
        selectionTitle.setTextSize(18f);
        selectionTitle.setPadding(0, 0, 0, delegateOwner.dp(6));
        form.addView(selectionTitle);
        TextView baseHint = new TextView(delegateOwner);
        baseHint.setText(R.string.bpm_list_base_hint);
        baseHint.setTextSize(13f);
        baseHint.setPadding(0, 0, 0, delegateOwner.dp(6));
        form.addView(baseHint);

        EditText startTime = delegateOwner.addField(form, delegateOwner.getString(R.string.field_bpm_start),
                chart.bpmChanges.get(0).startTime.toString(), false);
        EditText bpmValue = delegateOwner.addField(form, delegateOwner.getString(R.string.field_bpm_value),
                EditorDialogController.formatNumber(chart.bpmChanges.get(0).bpm), true);
        bpmValue.setEnabled(true);

        LinearLayout actions = new LinearLayout(delegateOwner);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button newButton = delegateOwner.addActionButton(actions, R.string.bpm_list_new);
        Button applyButton = delegateOwner.addActionButton(actions, R.string.bpm_list_apply);
        Button deleteButton = delegateOwner.addActionButton(actions, R.string.bpm_list_delete);
        Button closeButton = delegateOwner.addActionButton(actions, R.string.bpm_list_close);
        delegateOwner.judgeLineDialogs.setListButtons(actions, newButton, applyButton, deleteButton, closeButton);

        BpmChange[] selected = new BpmChange[]{chart.bpmChanges.get(0)};
        boolean[] creating = new boolean[]{false};
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            chart.sortBpm();
            list.removeAllViews();
            for (int index = 0; index < chart.bpmChanges.size(); index++) {
                BpmChange change = chart.bpmChanges.get(index);
                boolean selectedRow = !creating[0] && change == selected[0];
                Button row = WorkbenchView.button(delegateOwner, "");
                String suffix = index == 0 ? delegateOwner.getString(R.string.bpm_list_base_suffix) : "";
                String rowText = delegateOwner.getString(R.string.bpm_list_row, index + 1,
                        change.startTime.toString(), EditorDialogController.compactBpm(change.bpm), suffix);
                row.setText(selectedRow
                        ? delegateOwner.getString(R.string.bpm_list_selected_prefix, rowText)
                        : rowText);
                row.setAllCaps(false);
                row.setGravity(Gravity.CENTER);
                row.setAlpha(selectedRow ? 1.0f : 0.78f);
                row.setEnabled(true);
                row.setOnClickListener(view -> {
                    selected[0] = change;
                    creating[0] = false;
                    refresh[0].run();
                    delegateOwner.reveal(selectionTitle);
                });
                list.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        delegateOwner.dp(48)));
            }

            if (creating[0]) {
                selectionTitle.setText(R.string.bpm_list_new_title);
                startTime.setEnabled(true);
                bpmValue.setEnabled(true);
                deleteButton.setEnabled(false);
            } else {
                int index = chart.bpmChanges.indexOf(selected[0]);
                if (index < 0) {
                    selected[0] = chart.bpmChanges.get(0);
                    index = 0;
                }
                BpmChange change = selected[0];
                selectionTitle.setText(delegateOwner.getString(R.string.bpm_list_edit_title, index + 1));
                startTime.setText(change.startTime.toString());
                bpmValue.setText(EditorDialogController.formatNumber(change.bpm));
                startTime.setEnabled(index != 0);
                bpmValue.setEnabled(true);
                deleteButton.setEnabled(index > 0 && chart.bpmChanges.size() > 1);
            }
        };

        newButton.setOnClickListener(view -> {
            chart.sortBpm();
            BpmChange last = chart.bpmChanges.get(chart.bpmChanges.size() - 1);
            selected[0] = null;
            creating[0] = true;
            startTime.setText(BeatTime.fromDouble(
                    last.startTime.toDouble() + 1.0, 1).toString());
            bpmValue.setText(EditorDialogController.formatNumber(last.bpm));
            refresh[0].run();
            delegateOwner.reveal(selectionTitle);
            startTime.requestFocus();
        });
        applyButton.setOnClickListener(view -> {
            try {
                BpmChange edited = creating[0] ? new BpmChange() : selected[0].copy();
                edited.startTime = BeatTime.parse(startTime.getText().toString());
                edited.bpm = EditorDialogController.parseDouble(bpmValue);
                EditorView.BpmApplyResult result = creating[0]
                        ? delegateOwner.editorView.addBpmChange(edited)
                        : delegateOwner.editorView.applyBpmProperties(selected[0], edited);
                if (result != EditorView.BpmApplyResult.APPLIED) {
                    delegateOwner.showBpmApplyError(result);
                    return;
                }
                selected[0] = creating[0] ? edited : selected[0];
                creating[0] = false;
                refresh[0].run();
                delegateOwner.showMessage(delegateOwner.getString(R.string.bpm_list_saved));
            } catch (IllegalArgumentException exception) {
                delegateOwner.showMessage(delegateOwner.getString(R.string.bpm_validation_invalid));
            }
        });
        deleteButton.setOnClickListener(view -> {
            BpmChange target = selected[0];
            int index = chart.bpmChanges.indexOf(target);
            EditorView.BpmApplyResult result = delegateOwner.editorView.deleteBpmChange(target);
            if (result != EditorView.BpmApplyResult.APPLIED) {
                delegateOwner.showBpmApplyError(result);
                return;
            }
            selected[0] = chart.bpmChanges.get(
                    Math.max(0, Math.min(index - 1, chart.bpmChanges.size() - 1)));
            creating[0] = false;
            refresh[0].run();
            delegateOwner.showMessage(delegateOwner.getString(R.string.bpm_list_deleted));
        });

        refresh[0].run();
        EditorPanelWindow dialog = new EditorPanelWindow.Builder(delegateOwner, delegateOwner.host.workbench())
                .setTitle(R.string.bpm_list_title)
                .retainOnLineChange()
                .setView(root)
                .setActionsView(actions)
                .create();
        closeButton.setOnClickListener(view -> dialog.dismiss());
        delegateOwner.showEditorWindow(dialog, null);
    }
}
