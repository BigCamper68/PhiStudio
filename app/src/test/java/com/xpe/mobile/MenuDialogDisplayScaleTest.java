package com.xpe.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.project.ProjectBrowserDialog;
import com.xpe.mobile.project.ProjectLibrary;
import com.xpe.mobile.project.ProjectThumbnailLoader;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

/** Separate Menu dialogs keep their action buttons inside the configured window. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33, 34}, qualifiers = "w1536dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MenuDialogDisplayScaleTest {
    private interface Probe { void run(int dpi, float font, int width, int height) throws Exception; }

    @Test public void projectLibraryFixedHeaderActionsFitTheConfiguredWindow() {
        matrix("library", this::inspectLibrary);
    }

    @Test public void videoExportDialogActionsFitTheConfiguredWindow() {
        matrix("video", this::inspectVideo);
    }

    private void matrix(String route, Probe probe) {
        List<String> errors = new ArrayList<>();
        for (int dpi : new int[]{160, 320}) {
            for (float font : new float[]{1f, 2f}) {
                for (int width : new int[]{1536, 1024}) {
                    String label = label(route, dpi, font, width, 720);
                    try {
                        probe.run(dpi, font, width, 720);
                    } catch (AssertionError | Exception error) {
                        String problem = label + ": " + error;
                        errors.add(problem);
                        System.out.println("MENU_SEPARATE_FAILURE " + problem);
                    }
                }
            }
        }
        assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    private void configure(int dpi, float font, int width, int height) {
        float density = dpi / 160f;
        RuntimeEnvironment.setQualifiers("w" + Math.round(width / density) + "dp-h"
                + Math.round(height / density) + "dp-land-" + dpi + "dpi");
        RuntimeEnvironment.setFontScale(font);
    }

    private void inspectLibrary(int dpi, float font, int width, int height) throws Exception {
        configure(dpi, font, width, height);
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();
        layout(activity.getWindow().getDecorView(), width, height);
        File sandbox = new File(activity.getCacheDir(), "menu-library-scale-" + System.nanoTime());
        ProjectThumbnailLoader thumbnails = new ProjectThumbnailLoader(new File(sandbox, "thumbnails"));
        ProjectLibrary library = new ProjectLibrary(new File(sandbox, "projects"));
        ProjectBrowserDialog browser = new ProjectBrowserDialog(activity, library, thumbnails);
        List<String> errors = new ArrayList<>();
        try {
            ProjectBrowserDialog.Callback callback = (ProjectBrowserDialog.Callback)
                    java.lang.reflect.Proxy.newProxyInstance(ProjectBrowserDialog.Callback.class.getClassLoader(),
                            new Class<?>[]{ProjectBrowserDialog.Callback.class}, (proxy, method, args) -> null);
            // Empty discovery is sufficient: only the fixed header actions belong to this probe.
            // Project card actions are intentionally excluded because their body scrolls separately.
            for (boolean startup : new boolean[]{false, true}) {
                String label = label("library startup=" + startup, dpi, font, width, height);
                browser.show(library.discoverWorkspaces(), startup, callback);
                Dialog dialog = ShadowDialog.getLatestDialog();
                assertNotNull(label + " dialog", dialog);
                View decor = measureConfiguredDialog(dialog, activity, width, height, .72f, .78f, label);
                ViewGroup content = decor.findViewById(android.R.id.content);
                assertNotNull(label + " content", content);
                LinearLayout root = (LinearLayout) content.getChildAt(0);
                LinearLayout header = (LinearLayout) root.getChildAt(0);
                List<Button> actions = new ArrayList<>();
                for (int index = 0; index < header.getChildCount(); index++) {
                    if (header.getChildAt(index) instanceof Button) actions.add((Button) header.getChildAt(index));
                }
                assertEquals(label + " fixed header actions", 3, actions.size());
                collectVisibility(label, actions, errors);
                browser.dismiss();
            }
        } finally {
            browser.dismiss();
            thumbnails.shutdown();
            controller.pause().stop().destroy();
        }
        assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    private void inspectVideo(int dpi, float font, int width, int height) {
        configure(dpi, font, width, height);
        // Match VideoExportDialogTest: avoid MainActivity.onCreate, which starts unrelated sessions.
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(R.style.AppTheme);
        activity.editorSettings = new EditorSettings();
        layout(activity.getWindow().getDecorView(), width, height);
        AlertDialog dialog = null;
        try {
            activity.getSharedPreferences("video_export", 0).edit().clear().commit();
            new VideoExportDialog(activity, false, 2500).show(options -> { });
            dialog = ShadowAlertDialog.getLatestAlertDialog();
            String label = label("video", dpi, font, width, height);
            assertNotNull(label + " dialog", dialog);
            measureConfiguredDialog(dialog, activity, width, height, .84f, .90f, label);
            List<Button> actions = new ArrayList<>();
            actions.add(dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
            actions.add(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
            List<String> errors = new ArrayList<>();
            collectVisibility(label, actions, errors);
            assertTrue(String.join("\n", errors), errors.isEmpty());
        } finally {
            if (dialog != null) dialog.dismiss();
            activity.finish();
        }
    }

    private View measureConfiguredDialog(Dialog dialog, Activity activity, int screenWidth, int screenHeight,
            float widthFraction, float heightFraction, String label) {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(label + " simulated screen width", screenWidth,
                activity.getResources().getDisplayMetrics().widthPixels);
        assertEquals(label + " simulated screen height", screenHeight,
                activity.getResources().getDisplayMetrics().heightPixels);
        int expectedWidth = (int) (screenWidth * widthFraction);
        int expectedHeight = (int) (screenHeight * heightFraction);
        WindowManager.LayoutParams attributes = dialog.getWindow().getAttributes();
        assertEquals(label + " configured window width", expectedWidth, attributes.width);
        assertEquals(label + " configured window height", expectedHeight, attributes.height);
        View decor = dialog.getWindow().getDecorView();
        layout(decor, attributes.width, attributes.height);
        assertEquals(label + " measured window width", expectedWidth, decor.getWidth());
        assertEquals(label + " measured window height", expectedHeight, decor.getHeight());
        System.out.println("MENU_SEPARATE_WINDOW " + label + " configured=" + attributes.width
                + "x" + attributes.height + " measured=" + decor.getWidth() + "x" + decor.getHeight());
        return decor;
    }

    private void collectVisibility(String label, List<Button> actions, List<String> errors) {
        for (Button action : actions) {
            if (action == null) {
                errors.add(label + ": missing action view");
                continue;
            }
            View parent = action.getParent() instanceof View ? (View) action.getParent() : null;
            Rect visible = new Rect();
            boolean shown = action.getGlobalVisibleRect(visible);
            boolean valid = action.getWidth() > 0 && action.getHeight() > 0
                    && parent != null && parent.getWidth() > 0 && parent.getHeight() > 0
                    && action.getVisibility() == View.VISIBLE && shown
                    && visible.width() >= action.getWidth() - 1
                    && visible.height() >= action.getHeight() - 1;
            String observation = label + " action=" + action.getText() + " measured="
                    + action.getWidth() + "x" + action.getHeight() + " visible=" + visible
                    + " shown=" + shown + " full=" + valid;
            System.out.println("MENU_SEPARATE_ACTION " + observation);
            if (!valid) {
                String problem = observation + geometry(action);
                errors.add(problem);
                System.out.println("MENU_SEPARATE_GEOMETRY " + problem);
            }
        }
    }

    private void layout(View decor, int width, int height) {
        for (int pass = 0; pass < 3; pass++) {
            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, width, height);
        }
    }

    private String label(String route, int dpi, float font, int width, int height) {
        return route + " api=" + RuntimeEnvironment.getApiLevel() + " dpi=" + dpi
                + " font=" + font + " screen=" + width + "x" + height;
    }

    private String geometry(View target) {
        StringBuilder result = new StringBuilder();
        for (View view = target; view != null;
                view = view.getParent() instanceof View ? (View) view.getParent() : null) {
            result.append("\n").append(view.getClass().getSimpleName()).append(' ')
                    .append(view.getWidth()).append('x').append(view.getHeight()).append(" at ")
                    .append(view.getLeft()).append(',').append(view.getTop()).append(" scroll=")
                    .append(view.getScrollX()).append(',').append(view.getScrollY());
        }
        return result.toString();
    }
}
