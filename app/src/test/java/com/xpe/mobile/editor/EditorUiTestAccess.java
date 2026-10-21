package com.xpe.mobile.editor;

import android.content.Context;
import android.view.View;
import com.xpe.mobile.model.JudgeLine;

/** Package-local panel setup for integration tests in other feature packages. */
public final class EditorUiTestAccess {
    private EditorUiTestAccess() { }
    public static void showDraft(Context context, WorkbenchView workbench, CharSequence title, View content) {
        new EditorPanelWindow.Builder(context, workbench).setTitle(title).setView(content).create().show();
    }
    public static void showLineProperties(EditorDialogController dialogs, JudgeLine line) {
        dialogs.judgeLineDialogs.showLineProperties(line, () -> { });
    }
}
