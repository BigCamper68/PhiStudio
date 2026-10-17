package com.xpe.mobile;

import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.xpe.mobile.editor.ChartDiagnostic;
import com.xpe.mobile.editor.ChartDiagnostics;
import com.xpe.mobile.model.ChartDocument;
import java.util.ArrayList;
import java.util.List;

/** ChartDiagnosticsDialog responsibilities extracted from EditorDialogController. */
final class ChartDiagnosticsDialog {
    private final EditorDialogController delegateOwner;

    ChartDiagnosticsDialog(EditorDialogController delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void showChartDiagnostics() {
        ChartDocument chart = delegateOwner.editorView.getChart();
        if (chart == null) return;
        Double maximumBeat = null;
        long duration = delegateOwner.host.audioDurationMs();
        if (duration > 0L) {
            maximumBeat = chart.audioMillisToBeat(duration, delegateOwner.editorView.getPackageOffsetMs());
        }
        ChartDiagnostics.Report report = ChartDiagnostics.analyze(chart, maximumBeat);

        LinearLayout root = new LinearLayout(delegateOwner);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = delegateOwner.dp(12);
        root.setPadding(pad, pad, pad, pad);

        TextView summary = new TextView(delegateOwner);
        summary.setText(delegateOwner.getString(R.string.diagnostics_summary, report.errorCount,
                report.warningCount, report.cautionCount, report.items.size(),
                report.totalCount));
        summary.setTextSize(14f);
        summary.setPadding(0, 0, 0, delegateOwner.dp(8));
        root.addView(summary);

        List<ChartDiagnostic> diagnostics = new ArrayList<>(report.items);
        List<String> labels = new ArrayList<>(diagnostics.size());
        for (ChartDiagnostic diagnostic : diagnostics) {
            labels.add(delegateOwner.chartDiagnosticsDialog.diagnosticLabel(diagnostic));
        }
        ListView list = new ListView(delegateOwner);
        list.setNestedScrollingEnabled(true);
        list.setAdapter(new ArrayAdapter<>(delegateOwner, android.R.layout.simple_list_item_1, labels));
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, delegateOwner.dp(240)));

        EditorPanelWindow dialog = new EditorPanelWindow.Builder(delegateOwner, delegateOwner.host.workbench())
                .setTitle(R.string.diagnostics_title)
                .retainOnLineChange()
                .setView(root)
                .setNegativeButton(R.string.bpm_list_close, null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            ChartDiagnostic diagnostic = diagnostics.get(position);
            if (delegateOwner.editorView.navigateToDiagnostic(diagnostic)) {
                dialog.dismiss();
                delegateOwner.host.workbench().switchTab(0);
                delegateOwner.showMessage(delegateOwner.getString(R.string.diagnostics_navigated));
            }
        });
        delegateOwner.showEditorWindow(dialog, null);
    }

    String diagnosticLabel(ChartDiagnostic diagnostic) {
        String severity;
        switch (diagnostic.severity) {
            case ERROR: severity = delegateOwner.getString(R.string.diagnostics_severity_error); break;
            case WARNING: severity = delegateOwner.getString(R.string.diagnostics_severity_warning); break;
            default: severity = delegateOwner.getString(R.string.diagnostics_severity_caution); break;
        }
        String location;
        if (diagnostic.lineIndex < 0) {
            location = delegateOwner.getString(R.string.diagnostics_location_global, diagnostic.beat);
        } else if (diagnostic.layerIndex < 0) {
            location = delegateOwner.getString(R.string.diagnostics_location_note,
                    diagnostic.lineIndex, diagnostic.beat);
        } else {
            location = delegateOwner.getString(R.string.diagnostics_location_event,
                    diagnostic.lineIndex, diagnostic.layerIndex, diagnostic.beat);
        }
        return severity + " · " + location + "\n" + delegateOwner.chartDiagnosticsDialog.diagnosticMessage(diagnostic);
    }

    String diagnosticMessage(ChartDiagnostic diagnostic) {
        switch (diagnostic.code) {
            case INVALID_BPM:
                return delegateOwner.getString(R.string.diagnostic_invalid_bpm);
            case NEGATIVE_BPM_START:
                return delegateOwner.getString(R.string.diagnostic_negative_bpm_start);
            case DUPLICATE_BPM_START:
                return delegateOwner.getString(R.string.diagnostic_duplicate_bpm_start);
            case NOTE_TIME_OUT_OF_RANGE:
                return delegateOwner.getString(R.string.diagnostic_note_time_out_of_range);
            case HOLD_INTERVAL_INVALID:
                return delegateOwner.getString(R.string.diagnostic_hold_interval_invalid);
            case NOTE_X_TOO_LARGE:
                return delegateOwner.getString(R.string.diagnostic_note_x_too_large,
                        diagnostic.note == null ? 0.0 : diagnostic.note.positionX);
            case FAKE_NOTE:
                return delegateOwner.getString(R.string.diagnostic_fake_note);
            case CUSTOM_NOTE_SIZE:
                return delegateOwner.getString(R.string.diagnostic_custom_note_size,
                        diagnostic.note == null ? 1.0 : diagnostic.note.size);
            case CUSTOM_VISIBLE_TIME:
                return delegateOwner.getString(R.string.diagnostic_custom_visible_time,
                        diagnostic.note == null ? 999999.0 : diagnostic.note.visibleTime);
            case EVENT_TIME_OUT_OF_RANGE:
                return delegateOwner.getString(R.string.diagnostic_event_time_out_of_range);
            case EVENT_INTERVAL_INVALID:
                return delegateOwner.getString(R.string.diagnostic_event_interval_invalid);
            case EVENT_OVERLAP:
                return delegateOwner.getString(R.string.diagnostic_event_overlap,
                        diagnostic.event == null ? "Event"
                                : delegateOwner.eventTypeLabel(diagnostic.event.type));
            case ALPHA_OUT_OF_RANGE:
                return delegateOwner.getString(R.string.diagnostic_alpha_out_of_range);
            case RESERVED_LAYER_NORMAL_EVENT:
                return delegateOwner.getString(R.string.diagnostic_reserved_layer_event);
            default:
                return diagnostic.code.name();
        }
    }
}
