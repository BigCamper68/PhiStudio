package com.xpe.mobile.project;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.xpe.mobile.R;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;

public final class ProjectBrowserDialog {
    public interface Callback {
        void onCreateRequested();

        void onImportRequested();

        void onOpen(ProjectRecord project);

        void onRename(ProjectRecord project);

        void onDuplicate(ProjectRecord project);

        void onExport(ProjectRecord project);

        void onRemove(ProjectRecord project);

        void onIssueAction(ProjectLibrary.WorkspaceIssue issue);
    }

    private static final int BACKGROUND = Color.rgb(20, 25, 31);
    private static final int CARD = Color.rgb(34, 42, 51);
    private static final int CARD_STROKE = Color.rgb(62, 76, 90);
    private static final int ACCENT = Color.rgb(91, 211, 172);
    private static final int PLACEHOLDER = Color.rgb(47, 58, 69);

    private final Activity activity;
    private final ProjectLibrary library;
    private final ProjectThumbnailLoader thumbnails;
    private Dialog activeDialog;

    public ProjectBrowserDialog(Activity activity, ProjectLibrary library,
                                ProjectThumbnailLoader thumbnails) {
        this.activity = activity;
        this.library = library;
        this.thumbnails = thumbnails;
    }

    public void dismiss() {
        if (activeDialog != null) {
            activeDialog.dismiss();
            activeDialog = null;
        }
    }

    public void show(ProjectLibrary.Discovery discovery, boolean startup, Callback callback) {
        if (activeDialog != null) activeDialog.dismiss();
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setBackgroundColor(BACKGROUND);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = text(activity.getString(R.string.project_library_title), 24f, Color.WHITE);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button createButton = button(R.string.project_library_create);
        header.addView(createButton);
        Button importButton = button(R.string.project_library_import);
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        importParams.setMarginStart(dp(8));
        header.addView(importButton, importParams);
        Button closeButton = button(startup
                ? R.string.project_library_continue : R.string.project_library_close);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        closeParams.setMarginStart(dp(8));
        header.addView(closeButton, closeParams);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(10), 0, dp(8));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        if (discovery.getState().getProjects().isEmpty()) {
            TextView empty = text(activity.getString(R.string.project_library_empty), 16f, Color.LTGRAY);
            empty.setPadding(dp(6), dp(18), dp(6), dp(18));
            content.addView(empty);
        } else {
            GridLayout grid = new GridLayout(activity);
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int availableWidth = (int) (screenWidth * 0.72f) - dp(32);
            int columns = Math.max(1, Math.min(3,
                    Math.max(1, availableWidth / dp(270))));
            grid.setColumnCount(columns);
            grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
            content.addView(grid, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            for (ProjectRecord project : discovery.getState().getProjects()) {
                grid.addView(projectCard(project, callback), cardLayoutParams());
            }
        }

        if (!discovery.getIssues().isEmpty()) {
            TextView issueTitle = text(activity.getString(R.string.project_recovery_title), 20f, Color.WHITE);
            issueTitle.setPadding(dp(4), dp(18), dp(4), dp(8));
            content.addView(issueTitle);
            for (ProjectLibrary.WorkspaceIssue issue : discovery.getIssues()) {
                content.addView(issueRow(issue, callback));
            }
        }

        activeDialog = new Dialog(
                activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        activeDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        activeDialog.setContentView(root);
        activeDialog.setCanceledOnTouchOutside(false);
        createButton.setOnClickListener(view -> dispatch(callback::onCreateRequested));
        importButton.setOnClickListener(view -> dispatch(callback::onImportRequested));
        closeButton.setOnClickListener(view -> dispatch(() -> { }));
        activeDialog.setOnShowListener(ignored -> {
            Window window = activeDialog.getWindow();
            if (window != null) {
                GradientDrawable background = new GradientDrawable();
                background.setColor(BACKGROUND);
                background.setCornerRadius(dp(12));
                background.setStroke(dp(1), CARD_STROKE);
                window.setBackgroundDrawable(background);
                window.setDimAmount(0.55f);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                int width = activity.getResources().getDisplayMetrics().widthPixels;
                int height = activity.getResources().getDisplayMetrics().heightPixels;
                window.setLayout((int) (width * 0.72f), (int) (height * 0.78f));
            }
        });
        activeDialog.show();
    }

    private View projectCard(ProjectRecord project, Callback callback) {
        LinearLayout card = cardContainer();
        ImageView illustration = new ImageView(activity);
        illustration.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(illustration, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(132)));
        File illustrationFile = null;
        if (project.getIllustrationPath() != null) {
            try {
                illustrationFile = library.getProjectResource(
                        project.getId(), project.getIllustrationPath());
            } catch (IOException ignored) {
                // The placeholder remains visible and workspace recovery reports structural failures.
            }
        }
        thumbnails.load(project.getId(), illustrationFile, illustration, PLACEHOLDER);

        TextView name = text(project.getName(), 18f, Color.WHITE);
        name.setPadding(0, dp(9), 0, dp(3));
        name.setMaxLines(2);
        card.addView(name);
        TextView source = text(project.getSourceDisplayName(), 12f, Color.LTGRAY);
        source.setMaxLines(1);
        card.addView(source);
        String details = activity.getString(R.string.project_library_card_details,
                mediaSummary(project), formatLastOpened(project.getLastOpenedAtMillis()));
        TextView detailView = text(details, 12f, Color.LTGRAY);
        detailView.setPadding(0, dp(3), 0, dp(7));
        card.addView(detailView);

        LinearLayout firstRow = actionRow();
        addAction(firstRow, R.string.project_action_open,
                view -> dispatch(() -> callback.onOpen(project)));
        addAction(firstRow, R.string.project_action_export,
                view -> dispatch(() -> callback.onExport(project)));
        card.addView(firstRow);
        LinearLayout secondRow = actionRow();
        addAction(secondRow, R.string.project_action_rename,
                view -> dispatch(() -> callback.onRename(project)));
        addAction(secondRow, R.string.project_action_duplicate,
                view -> dispatch(() -> callback.onDuplicate(project)));
        card.addView(secondRow);
        Button remove = button(R.string.project_action_remove);
        remove.setTextColor(Color.rgb(255, 170, 170));
        remove.setOnClickListener(view -> dispatch(() -> callback.onRemove(project)));
        card.addView(remove, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private View issueRow(ProjectLibrary.WorkspaceIssue issue, Callback callback) {
        LinearLayout row = cardContainer();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        row.setLayoutParams(params);
        String status = issueStatus(issue.getStatus());
        TextView title = text(activity.getString(R.string.project_recovery_row,
                issue.getDisplayName(), status), 16f, Color.WHITE);
        row.addView(title);
        TextView message = text(issue.getMessage(), 12f, Color.LTGRAY);
        message.setPadding(0, dp(3), 0, dp(7));
        row.addView(message);
        Button action = button(issue.getStatus() == ProjectLibrary.WorkspaceStatus.RECOVERABLE
                ? R.string.project_recovery_recover : R.string.project_recovery_remove);
        action.setOnClickListener(view -> dispatch(() -> callback.onIssueAction(issue)));
        row.addView(action, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private LinearLayout cardContainer() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), CARD_STROKE);
        card.setBackground(background);
        return card;
    }

    private GridLayout.LayoutParams cardLayoutParams() {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = dp(260);
        params.height = LinearLayout.LayoutParams.WRAP_CONTENT;
        params.setMargins(dp(5), dp(5), dp(5), dp(5));
        return params;
    }

    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private void addAction(LinearLayout row, int textResource, View.OnClickListener listener) {
        Button button = button(textResource);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(button, params);
    }

    private Button button(int textResource) {
        Button button = new Button(activity);
        button.setText(textResource);
        button.setAllCaps(false);
        button.setMinHeight(dp(44));
        return button;
    }

    private TextView text(String value, float size, int color) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private String mediaSummary(ProjectRecord project) {
        if (project.hasAudio() && project.hasIllustration()) {
            return activity.getString(R.string.project_media_both);
        }
        if (project.hasAudio()) return activity.getString(R.string.project_media_audio);
        if (project.hasIllustration()) return activity.getString(R.string.project_media_illustration);
        return activity.getString(R.string.project_media_none);
    }

    private String formatLastOpened(long millis) {
        if (millis <= 0L) return activity.getString(R.string.project_never_opened);
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(millis));
    }

    private String issueStatus(ProjectLibrary.WorkspaceStatus status) {
        switch (status) {
            case RECOVERABLE: return activity.getString(R.string.project_status_recoverable);
            case MISSING: return activity.getString(R.string.project_status_missing);
            case UNSUPPORTED: return activity.getString(R.string.project_status_unsupported);
            case DAMAGED:
            default: return activity.getString(R.string.project_status_damaged);
        }
    }

    private void dispatch(Runnable action) {
        if (activeDialog != null) {
            activeDialog.dismiss();
            activeDialog = null;
        }
        action.run();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
