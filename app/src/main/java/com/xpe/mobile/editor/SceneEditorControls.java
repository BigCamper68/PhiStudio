package com.xpe.mobile.editor;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;

/** The existing shader editor's controls, shared verbatim with the noise-field editor. */
public final class SceneEditorControls {
    private SceneEditorControls() { }
    public static Button action(Context context, LinearLayout parent, CharSequence label, Runnable callback) {
        Button view = WorkbenchView.button(context, label);
        view.setTextSize(11);
        view.setSingleLine(true);
        parent.addView(view, new LinearLayout.LayoutParams(-2, dp(context, 32)));
        view.setOnClickListener(v -> callback.run());
        return view;
    }
    public static Button toolbarAction(Context context, LinearLayout parent, CharSequence label, int width, Runnable callback) {
        Button view = action(context, parent, label, callback);
        float density = context.getResources().getDisplayMetrics().density;
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 10.5f * density);
        view.setPadding(0, 0, 0, 0);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setBackground(new ColorDrawable(Color.WHITE));
        view.setBackgroundTintList(new ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {Color.rgb(39, 45, 52), Color.rgb(53, 63, 74)}));
        view.setTextColor(new ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {Color.rgb(125, 134, 142), Color.WHITE}));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(context, width), dp(context, 38));
        lp.rightMargin = dp(context, 4);
        lp.gravity = Gravity.CENTER_VERTICAL;
        view.setLayoutParams(lp);
        return view;
    }
    private static int dp(Context context, int value) { return WorkbenchView.dp(context, value); }
}
