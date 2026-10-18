package com.xpe.mobile;

import com.xpe.mobile.i18n.UiText;
import com.xpe.mobile.i18n.UiDialogs;

import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.model.AttachedUiElement;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;

/** JudgeLineDialogs responsibilities extracted from EditorDialogController. */
final class JudgeLineDialogs {
    private final EditorDialogController delegateOwner;

    JudgeLineDialogs(EditorDialogController delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void showLineManager() {
        ChartDocument chart = delegateOwner.editorView.getChart();
        if (chart == null) return;

        int pad = delegateOwner.dp(12);
        LinearLayout root = new LinearLayout(delegateOwner);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        ScrollView listScroll = new EditorListScrollView(delegateOwner);
        listScroll.setFillViewport(true);
        listScroll.setNestedScrollingEnabled(true);
        LinearLayout list = new LinearLayout(delegateOwner);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(list);
        root.addView(listScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, delegateOwner.dp(192)));

        LinearLayout details = new LinearLayout(delegateOwner);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(0, delegateOwner.dp(6), 0, 0);
        root.addView(details, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView selectedTitle = new TextView(delegateOwner);
        selectedTitle.setTextSize(18f);
        selectedTitle.setPadding(0, 0, 0, delegateOwner.dp(6));
        details.addView(selectedTitle);
        TextView summary = new TextView(delegateOwner);
        summary.setTextSize(15f);
        summary.setPadding(0, 0, 0, delegateOwner.dp(8));
        details.addView(summary);
        TextView propertiesSummary = new TextView(delegateOwner);
        propertiesSummary.setTextSize(13f);
        details.addView(propertiesSummary);

        LinearLayout firstActions = new LinearLayout(delegateOwner);
        firstActions.setOrientation(LinearLayout.HORIZONTAL);
        Button add = delegateOwner.addActionButton(firstActions, R.string.line_manager_add);
        Button properties = delegateOwner.addActionButton(firstActions, R.string.properties_button);
        Button delete = delegateOwner.addActionButton(firstActions, R.string.line_manager_delete);
        Button close = delegateOwner.addActionButton(firstActions, R.string.bpm_list_close);
        delegateOwner.judgeLineDialogs.setListButtons(firstActions, add, properties, delete, close);

        int[] selectedIndex = new int[]{delegateOwner.editorView.getLineIndex()};
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            selectedIndex[0] = Math.max(0,
                    Math.min(selectedIndex[0], chart.judgeLines.size() - 1));
            list.removeAllViews();
            for (int index = 0; index < chart.judgeLines.size(); index++) {
                JudgeLine line = chart.judgeLines.get(index);
                boolean active = index == selectedIndex[0];
                Button row = WorkbenchView.button(delegateOwner, "");
                row.setAllCaps(false);
                row.setGravity(Gravity.CENTER);
                row.setAlpha(active ? 1.0f : 0.78f);
                int nextIndex = index;
                UiText.text(row, () -> delegateOwner.getString(R.string.line_manager_row, nextIndex, line.name,
                        active ? delegateOwner.getString(R.string.line_manager_active_suffix) : ""));
                row.setOnClickListener(view -> {
                    selectedIndex[0] = nextIndex;
                    delegateOwner.editorView.selectLine(nextIndex);
                    refresh[0].run();
                });
                list.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        delegateOwner.dp(48)));
            }
            JudgeLine selected = chart.judgeLines.get(selectedIndex[0]);
            UiText.text(selectedTitle, () -> delegateOwner.getString(R.string.line_manager_row, selectedIndex[0],
                    selected.name, delegateOwner.getString(R.string.line_manager_active_suffix)));
            UiText.text(summary, () -> delegateOwner.getString(R.string.line_manager_summary,
                    selected.notes.size(), selected.countEvents()));
            UiText.text(propertiesSummary, () -> delegateOwner.getString(R.string.line_manager_details,
                    selected.texture, selected.group, EditorDialogController.formatNumber(selected.bpmFactor),
                    selected.zOrder, delegateOwner.getString(selected.cover
                            ? R.string.line_manager_cover_on : R.string.line_manager_cover_off)));
            delete.setEnabled(selectedIndex[0] != 0);
        };

        EditorPanelWindow dialog = new EditorPanelWindow.Builder(delegateOwner, delegateOwner.host.workbench())
                .setTitle(R.string.line_manager_title)
                .retainOnLineChange()
                .setView(root)
                .setActionsView(firstActions)
                .create();
        add.setOnClickListener(view -> {
            delegateOwner.editorView.addJudgeLine();
            selectedIndex[0] = delegateOwner.editorView.getLineIndex();
            refresh[0].run();
        });
        properties.setOnClickListener(view -> {
            JudgeLine target = chart.judgeLines.get(selectedIndex[0]);
            delegateOwner.judgeLineDialogs.showLineProperties(target, refresh[0]);
        });
        delete.setOnClickListener(view -> new UiDialogs.Builder(delegateOwner)
                .setTitle(R.string.line_delete_title)
                .setMessage(delegateOwner.getString(R.string.line_delete_message, selectedIndex[0],
                        chart.judgeLines.get(selectedIndex[0]).name))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.line_manager_delete, (ignored, which) -> {
                    EditorView.LineApplyResult result = delegateOwner.editorView.deleteActiveLine();
                    if (result != EditorView.LineApplyResult.APPLIED) {
                        delegateOwner.judgeLineDialogs.showLineApplyError(result);
                        return;
                    }
                    selectedIndex[0] = delegateOwner.editorView.getLineIndex();
                    refresh[0].run();
                })
                .show());
        close.setOnClickListener(view -> dialog.dismiss());
        refresh[0].run();
        delegateOwner.showEditorWindow(dialog, null);
    }

    void showLineProperties(JudgeLine line, Runnable afterSave) {
        ScrollView scrollView = new ScrollView(delegateOwner);
        LinearLayout content = delegateOwner.createDialogContent(scrollView);
        EditText name = delegateOwner.addField(content, R.string.field_line_name, line.name, false);
        EditText texture = delegateOwner.addField(content, R.string.field_line_texture, line.texture, false);
        EditText group = delegateOwner.addField(content, R.string.field_line_group, Integer.toString(line.group), true);
        EditText bpmFactor = delegateOwner.addField(content, R.string.field_line_bpm_factor, EditorDialogController.formatNumber(line.bpmFactor), true);
        EditText zOrder = delegateOwner.addField(content, R.string.field_line_z_order, Integer.toString(line.zOrder), true);
        CheckBox cover = delegateOwner.addCheckBox(content, R.string.field_line_cover, line.cover);
        Spinner attachedUi = delegateOwner.addSpinner(content, R.string.field_line_hud,
                R.array.line_hud_elements, line.attachUi == null ? 0 : line.attachUi.ordinal() + 1);
        TextView hudHelp = new TextView(delegateOwner);
        UiText.text(hudHelp, R.string.field_line_hud_help);
        content.addView(hudHelp);

        EditorPanelWindow dialog = new EditorPanelWindow.Builder(delegateOwner, delegateOwner.host.workbench())
                .setTitle(R.string.line_properties_title)
                .setView(scrollView)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_apply, null)
                .create();
        delegateOwner.showEditorWindow(dialog, () -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        JudgeLine edited = line.copyProperties();
                        edited.name = name.getText().toString().trim();
                        edited.texture = texture.getText().toString().trim();
                        edited.group = EditorDialogController.parseInteger(group);
                        edited.bpmFactor = EditorDialogController.parseDouble(bpmFactor);
                        edited.zOrder = EditorDialogController.parseInteger(zOrder);
                        edited.cover = cover.isChecked();
                        int hudIndex = attachedUi.getSelectedItemPosition();
                        edited.attachUi = hudIndex == 0 ? null : AttachedUiElement.values()[hudIndex - 1];
                        EditorView.LineApplyResult result = delegateOwner.editorView.applyJudgeLineProperties(line, edited);
                        if (result != EditorView.LineApplyResult.APPLIED) {
                            delegateOwner.judgeLineDialogs.showLineApplyError(result);
                            return;
                        }
                        delegateOwner.host.onLineAppearanceChanged();
                        dialog.dismiss();
                        delegateOwner.showMessage(delegateOwner.getString(R.string.line_properties_applied));
                        afterSave.run();
                    } catch (IllegalArgumentException exception) {
                        delegateOwner.showMessage(delegateOwner.getString(R.string.line_validation_invalid));
                    }
                }));
    }

    void showLineApplyError(EditorView.LineApplyResult result) {
        int message;
        switch (result) {
            case LINE_ZERO_PROTECTED: message = R.string.line_validation_zero_protected; break;
            case LAST_LINE_REQUIRED: message = R.string.line_validation_last_required; break;
            case TARGET_NOT_FOUND: message = R.string.validation_target_changed; break;
            default: message = R.string.line_validation_invalid; break;
        }
        delegateOwner.showMessage(delegateOwner.getString(message));
    }

    void setListButtons(LinearLayout row, Button... buttons) {
        delegateOwner.setCompactButtons(row, buttons);
        row.setPadding(0, 0, 0, 0);
        row.setGravity(Gravity.CENTER);
        for (Button button : buttons) {
            button.setGravity(Gravity.CENTER);
            button.setSingleLine(true);
            button.setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1,
                    android.util.TypedValue.COMPLEX_UNIT_SP);
            android.view.ViewGroup.LayoutParams params = button.getLayoutParams();
            params.height = delegateOwner.dp(EditorPanelWindow.ACTION_HEIGHT_DP);
            button.setLayoutParams(params);
        }
    }
}
