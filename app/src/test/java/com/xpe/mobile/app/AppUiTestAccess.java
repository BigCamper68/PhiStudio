package com.xpe.mobile.app;

import android.widget.TextView;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.editor.WorkbenchView;
import com.xpe.mobile.model.ChartDocument;

/** Minimal Activity fixture; production Activity state stays package-private. */
public final class AppUiTestAccess {
    private AppUiTestAccess() { }
    public static WorkbenchView prepareEmptyWorkbench(MainActivity activity) throws Exception {
        activity.editorView = new EditorView(activity);
        activity.editorView.setChart(ChartDocument.fromJson("{}"));
        activity.editorSettings = new EditorSettings();
        activity.workbench = new WorkbenchView(activity, activity.editorView, () -> new TextView(activity));
        activity.setContentView(activity.workbench);
        return activity.workbench;
    }
}
