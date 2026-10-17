package com.xpe.mobile;

import android.app.AlertDialog;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;
import java.util.Locale;

/** ChartMetadataDialog responsibilities extracted from EditorDialogController. */
final class ChartMetadataDialog {
    private final EditorDialogController delegateOwner;

    ChartMetadataDialog(EditorDialogController delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void showMetadata() {
        ChartDocument chart = delegateOwner.editorView.getChart();
        if (chart == null) return;

        ScrollView scrollView = new ScrollView(delegateOwner);
        LinearLayout content = new LinearLayout(delegateOwner);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = delegateOwner.dp(18);
        content.setPadding(pad, pad, pad, pad);
        scrollView.addView(content);

        EditText name = delegateOwner.addField(content, "Name", chart.name, false);
        EditText composer = delegateOwner.addField(content, "Composer", chart.composer, false);
        EditText charter = delegateOwner.addField(content, "Charter", chart.charter, false);
        EditText level = delegateOwner.addField(content, "Level", chart.level, false);
        EditText id = delegateOwner.addField(content, "ID / project folder", chart.id, false);
        EditText offset = delegateOwner.addField(content, delegateOwner.getString(R.string.field_chart_offset),
                Integer.toString(chart.offsetMs), false);
        offset.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        offset.setKeyListener(DigitsKeyListener.getInstance("0123456789-"));
        TextView offsetHint = new TextView(delegateOwner);
        long packageOffsetMs = delegateOwner.host.packageOffsetMs();
        offsetHint.setText(delegateOwner.getString(R.string.chart_offset_hint, packageOffsetMs,
                (long) chart.offsetMs));
        offsetHint.setTextSize(12f);
        content.addView(offsetHint);
        double currentBpm = chart.bpmChanges.isEmpty() ? 120.0 : chart.bpmChanges.get(0).bpm;
        EditText bpm = delegateOwner.addField(content, "Base BPM", String.format(Locale.US, "%.6f", currentBpm), true);
        EditText backgroundDim = delegateOwner.addField(content, delegateOwner.getString(R.string.field_background_dim),
                EditorDialogController.formatNumber(chart.getBackgroundDim()), true);

        EditorPanelWindow dialog = new EditorPanelWindow.Builder(delegateOwner, delegateOwner.host.workbench())
                .setTitle("Chart metadata")
                .retainOnLineChange()
                .setView(scrollView)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        delegateOwner.showEditorWindow(dialog, () -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        String parsedName = EditorDialogController.nonEmpty(name.getText().toString(), "Untitled");
                        String parsedComposer = composer.getText().toString().trim();
                        String parsedCharter = charter.getText().toString().trim();
                        String parsedLevel = level.getText().toString().trim();
                        String parsedId = id.getText().toString().trim();
                        int parsedOffset = Integer.parseInt(offset.getText().toString().trim());
                        double parsedBpm = Double.parseDouble(bpm.getText().toString().trim());
                        double parsedDim = EditorDialogController.parseDouble(backgroundDim);
                        if (!Double.isFinite(parsedDim) || parsedDim < 0 || parsedDim > 1) {
                            throw new NumberFormatException("Background dim must be between 0 and 1");
                        }
                        if (!Double.isFinite(parsedBpm) || parsedBpm <= 0.0) {
                            throw new NumberFormatException("BPM must be positive and finite");
                        }
                        if (chart.bpmChanges.isEmpty()) chart.bpmChanges.add(new BpmChange());
                        BpmChange baseChange = chart.bpmChanges.get(0);
                        if (Double.compare(baseChange.bpm, parsedBpm) != 0) {
                            BpmChange edited = baseChange.copy();
                            edited.bpm = parsedBpm;
                            EditorView.BpmApplyResult result = delegateOwner.editorView.applyBpmProperties(baseChange, edited);
                            if (result != EditorView.BpmApplyResult.APPLIED) {
                                delegateOwner.showBpmApplyError(result);
                                return;
                            }
                        }
                        chart.name = parsedName;
                        chart.composer = parsedComposer;
                        chart.charter = parsedCharter;
                        chart.level = parsedLevel;
                        chart.id = parsedId;
                        chart.offsetMs = parsedOffset;
                        chart.backgroundDim = parsedDim;
                        delegateOwner.editorView.chartMetadataChanged();
                        dialog.dismiss();
                        delegateOwner.showMessage("Metadata saved");
                    } catch (NumberFormatException exception) {
                        delegateOwner.showMessage("Invalid offset, BPM, or background dim (0 to 1)");
                    }
                }));
    }
}
