package com.xpe.mobile.editor;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.xpe.mobile.R;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.model.ChartDocument;
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

import static org.junit.Assert.*;

/** Actions stay usable when display settings change the landscape viewport in dp. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33, 34}, qualifiers = "w1117dp-h524dp-land-220dpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class LineListDisplayScaleTest {
    @Test public void tallViewportHasAnActionRowWithoutAnyPriorConfigurationChanges() throws Exception {
        inspect(160, 1f, 1536, 720);
    }

    @Test public void actionsRemainReachableAcrossDisplayAndFontScales() throws Exception {
        List<String> problems = new ArrayList<>();
        for (int dpi : new int[]{160, 220, 320}) {
            for (float font : new float[]{1f, 1.3f, 2f}) {
                for (int height : new int[]{480, 720}) {
                    try {
                        inspect(dpi, font, 1536, height);
                    } catch (AssertionError error) {
                        String problem = "api=" + RuntimeEnvironment.getApiLevel() + " dpi=" + dpi
                                + " font=" + font + " height=" + height + ": " + error.getMessage();
                        problems.add(problem);
                        System.out.println("LINE_LIST_FAILURE " + problem);
                    }
                }
            }
        }
        assertTrue(String.join("\n", problems), problems.isEmpty());
    }

    private void inspect(int dpi, float font, int width, int height) throws Exception {
        float density = dpi / 160f;
        RuntimeEnvironment.setQualifiers("w" + Math.round(width / density) + "dp-h"
                + Math.round(height / density) + "dp-land-" + dpi + "dpi");
        RuntimeEnvironment.setFontScale(font);
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();
        try {
            EditorView editor = new EditorView(activity);
            editor.setChart(ChartDocument.fromJson("{}"));
            WorkbenchView workbench = new WorkbenchView(activity, editor, () -> new TextView(activity));
            activity.setContentView(workbench);
            EditorDialogController.Host host = (EditorDialogController.Host) java.lang.reflect.Proxy.newProxyInstance(
                    EditorDialogController.Host.class.getClassLoader(), new Class<?>[]{EditorDialogController.Host.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("workbench")) return workbench;
                        if (method.getName().equals("editorSettings")) return new EditorSettings();
                        if (method.getReturnType() == long.class) return 0L;
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == List.class) return java.util.Collections.emptyList();
                        return null;
                    });
            EditorDialogController dialogs = new EditorDialogController(activity, editor, host);
            dialogs.showLineManager();
            layout(activity, width, height);
            LinearLayout pane = (LinearLayout) workbench.getChildAt(1);
            ScrollView page = (ScrollView) pane.getChildAt(1);
            List<Button> buttons = descendants(page, Button.class);
            assertEquals("One line and four actions", 5, buttons.size());
            assertTrue("Action row collapsed" + geometry(buttons.get(1)),
                    ((View) buttons.get(1).getParent()).getHeight() > 0);
            int initiallyVisible = 0;
            for (Button button : buttons.subList(1, 5))
                if (button.getGlobalVisibleRect(new Rect())) initiallyVisible++;
            for (Button button : buttons.subList(1, 5)) {
                assertTrue("Action measured to zero", button.getWidth() > 0 && button.getHeight() > 0);
                assertEquals(View.VISIBLE, button.getVisibility());
                button.requestRectangleOnScreen(new Rect(0, 0, button.getWidth(), button.getHeight()), true);
                Rect visible = new Rect();
                boolean shown = button.getGlobalVisibleRect(visible);
                assertTrue("Action unreachable: " + button.getText() + geometry(button), shown);
                assertEquals("Action clipped vertically: " + button.getText(), button.getHeight(), visible.height());
                // Weighted widths can round the final child one pixel beyond the row.
                assertTrue("Action clipped horizontally: " + button.getText(),
                        visible.width() >= button.getWidth() - 1);
            }
            assertFalse("Line zero remains protected", buttons.get(3).isEnabled());
            buttons.get(1).performClick();
            layout(activity, width, height);
            assertEquals("Add line works", 2, editor.getChart().judgeLines.size());
            assertTrue("New line can be deleted", buttons.get(3).isEnabled());
            buttons.get(2).performClick();
            layout(activity, width, height);
            assertNotSame("Properties opens a page", page, pane.getChildAt(1));
            assertFalse("Properties contains editable fields",
                    descendants(pane.getChildAt(1), android.widget.EditText.class).isEmpty());
            assertTrue(workbench.back());
            layout(activity, width, height);
            assertSame("Back returns to Line List", page, pane.getChildAt(1));
            buttons.get(3).performClick();
            android.app.AlertDialog confirmation = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("Delete asks for confirmation", confirmation);
            assertTrue(confirmation.isShowing());
            confirmation.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
            layout(activity, width, height);
            assertEquals("Delete removes the added line", 1, editor.getChart().judgeLines.size());
            assertFalse("Line zero stays protected after deletion", buttons.get(3).isEnabled());
            buttons.get(4).performClick();
            layout(activity, width, height);
            assertFalse("Close leaves Line List", pane.getChildAt(1) instanceof ScrollView);
            System.out.println("LINE_LIST_CASE api=" + RuntimeEnvironment.getApiLevel() + " dpi=" + dpi
                    + " font=" + font + " width=" + width + " height=" + height
                    + " initiallyVisibleActions=" + initiallyVisible
                    + " allReachable=true add=true properties=true delete=true close=true");
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private String geometry(View target) {
        StringBuilder result = new StringBuilder();
        for (View v = target; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            result.append("\n").append(v.getClass().getSimpleName()).append(' ')
                    .append(v.getWidth()).append('x').append(v.getHeight())
                    .append(" at ").append(v.getLeft()).append(',').append(v.getTop())
                    .append(" scroll=").append(v.getScrollY());
        }
        return result.toString();
    }

    private void layout(Activity activity, int width, int height) {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, width, height);
    }

    private <T extends View> List<T> descendants(View root, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (type.isInstance(root)) found.add(type.cast(root));
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) found.addAll(descendants(group.getChildAt(i), type));
        }
        return found;
    }
}
