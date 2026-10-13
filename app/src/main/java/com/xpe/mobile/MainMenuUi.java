package com.xpe.mobile;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** MainMenuUi responsibilities extracted from MainActivity. */
final class MainMenuUi {
    private final MainActivity delegateOwner;

    MainMenuUi(MainActivity delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void requestMainMenu() { delegateOwner.workbench.showMenu(); }

    View createWorkbenchMenu() {
        ScrollView scroll = new ScrollView(delegateOwner);
        LinearLayout actions = new LinearLayout(delegateOwner);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(delegateOwner.dp(10), delegateOwner.dp(4), delegateOwner.dp(10), delegateOwner.dp(12));
        scroll.addView(actions);
        delegateOwner.mainMenuUi.addMenuSection(actions, R.string.menu_section_projects);
        delegateOwner.mainMenuUi.addWorkbenchTool(actions, delegateOwner.getString(R.string.menu_projects), () -> delegateOwner.mainProjectActions.showProjectLibrary(false));
        delegateOwner.mainMenuUi.addWorkbenchTool(actions, delegateOwner.getString(R.string.menu_import_package), delegateOwner.mainFileActions::requestPackageOpen);
        delegateOwner.mainMenuUi.addWorkbenchTool(actions, delegateOwner.getString(R.string.menu_export_package), delegateOwner.mainFileActions::requestPackageSave);
        delegateOwner.mainMenuUi.addWorkbenchTool(actions, delegateOwner.getString(R.string.menu_export_video), delegateOwner.mainVideoExport::show);
        delegateOwner.mainMenuUi.addMenuSection(actions, R.string.menu_section_chart);
        delegateOwner.mainMenuUi.addWorkbenchTool(actions, delegateOwner.getString(R.string.menu_metadata), delegateOwner::requestEditMetadata);
        delegateOwner.mainMenuUi.addWorkbenchTool(actions, delegateOwner.getString(R.string.menu_bpm), delegateOwner::requestEditBpmList);
        delegateOwner.mainMenuUi.addWorkbenchTool(actions, delegateOwner.getString(R.string.menu_lines), delegateOwner::requestManageLines);
        delegateOwner.mainMenuUi.addWorkbenchTool(actions, delegateOwner.getString(R.string.menu_curve_notes), delegateOwner::showCurveNotes);
        delegateOwner.mainMenuUi.addWorkbenchTool(actions, delegateOwner.getString(R.string.menu_complex_move), delegateOwner::showComplexMove);
        delegateOwner.mainMenuUi.addWorkbenchTool(actions, delegateOwner.getString(R.string.menu_settings), delegateOwner::showSettings);
        delegateOwner.mainMenuUi.addWorkbenchTool(actions, delegateOwner.getString(R.string.menu_diagnostics), delegateOwner::showChartDiagnostics);
        return scroll;
    }

    void addWorkbenchTool(LinearLayout parent, String label, Runnable action) {
        Button button = WorkbenchView.button(delegateOwner, label);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(delegateOwner.dp(12), 0, delegateOwner.dp(8), 0);
        button.setBackgroundResource(R.drawable.phistudio_menu_button);
        parent.addView(button, new LinearLayout.LayoutParams(-1, delegateOwner.dp(44)));
        button.setOnClickListener(view -> action.run());
    }

    void addMenuSection(LinearLayout parent, int textResource) {
        TextView title = new TextView(delegateOwner);
        title.setText(textResource);
        title.setTextSize(16f);
        title.setPadding(delegateOwner.dp(4), delegateOwner.dp(4), delegateOwner.dp(4), delegateOwner.dp(6));
        parent.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    Button addMenuButton(LinearLayout parent, int textResource) {
        Button button = new Button(delegateOwner);
        button.setText(textResource);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinHeight(delegateOwner.dp(48));
        button.setBackgroundResource(R.drawable.phistudio_menu_button);
        parent.addView(button, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return button;
    }

    void runMenuAction(AlertDialog menu, Runnable action) {
        menu.dismiss();
        action.run();
    }

    void showEditorWindow(AlertDialog dialog, Runnable afterShow) {
        delegateOwner.mainMenuUi.showEditorWindow(dialog, afterShow, 0.72f, 0.78f);
    }

    void showEditorWindow(AlertDialog dialog, Runnable afterShow,
                                  float widthFraction, float heightFraction) {
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                int width = delegateOwner.getResources().getDisplayMetrics().widthPixels;
                int height = delegateOwner.getResources().getDisplayMetrics().heightPixels;
                window.setLayout((int) (width * widthFraction),
                        (int) (height * heightFraction));
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            if (afterShow != null) afterShow.run();
        });
        dialog.show();
    }

    LinearLayout createDialogContent(ScrollView scrollView) {
        LinearLayout content = new LinearLayout(delegateOwner);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = delegateOwner.dp(18);
        content.setPadding(pad, pad, pad, pad);
        scrollView.addView(content);
        return content;
    }

    EditText addField(LinearLayout parent, String label, String value, boolean numeric) {
        TextView title = new TextView(delegateOwner);
        title.setText(label);
        title.setTextSize(14f);
        title.setPadding(0, delegateOwner.dp(8), 0, delegateOwner.dp(3));
        parent.addView(title);

        EditText field = new EditText(delegateOwner);
        field.setText(value);
        field.setSingleLine(true);
        field.setSelectAllOnFocus(true);
        field.setGravity(Gravity.CENTER_VERTICAL);
        if (numeric) {
            field.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | InputType.TYPE_NUMBER_FLAG_SIGNED);
        }
        parent.addView(field, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return field;
    }
}
