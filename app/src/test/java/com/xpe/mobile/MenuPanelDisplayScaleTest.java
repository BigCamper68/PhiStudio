package com.xpe.mobile;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
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

/** Menu actions stay reachable across landscape sizes and display settings. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33, 34}, qualifiers = "w1117dp-h524dp-land-220dpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MenuPanelDisplayScaleTest {
    @Test public void menuAndEveryEmbeddedMenuFormKeepTheirActionsReachable() throws Exception {
        List<String> problems = new ArrayList<>();
        for (int dpi : new int[]{160, 220, 320}) {
            for (float font : new float[]{1f, 2f}) {
                for (int height : new int[]{360, 720, 1080}) {
                    try {
                        inspect(dpi, font, 1536, height, problems);
                    } catch (AssertionError error) {
                        problems.add(configuration(dpi, font, height) + ": " + error.getMessage());
                    }
                }
            }
        }
        for (String problem : problems) System.out.println("MENU_FAILURE " + problem);
        assertTrue(String.join("\n", problems), problems.isEmpty());
    }

    private void inspect(int dpi, float font, int width, int height, List<String> problems) throws Exception {
        float density = dpi / 160f;
        RuntimeEnvironment.setQualifiers("w" + Math.round(width / density) + "dp-h"
                + Math.round(height / density) + "dp-land-" + dpi + "dpi");
        RuntimeEnvironment.setFontScale(font);
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();
        try {
            MainActivity menuContext = Robolectric.buildActivity(MainActivity.class).get();
            menuContext.setTheme(R.style.AppTheme);
            EditorView editor = new EditorView(activity);
            ChartDocument chart = ChartDocument.fromJson("{}");
            Note first = new Note(); first.positionX = -300; first.fake = true;
            Note last = new Note(); last.startTime = new BeatTime(4, 0, 1);
            last.endTime = last.startTime; last.positionX = 300;
            chart.judgeLines.get(0).notes.add(first);
            chart.judgeLines.get(0).notes.add(last);
            for (int index = 1; index < 8; index++) {
                chart.judgeLines.add(new JudgeLine());
                BpmChange change = new BpmChange(); change.startTime = new BeatTime(index * 4, 0, 1);
                chart.bpmChanges.add(change);
            }
            editor.setChart(chart);
            WorkbenchView workbench = new WorkbenchView(activity, editor,
                    menuContext.mainMenuUi::createWorkbenchMenu);
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
            workbench.showMenu(); layout(activity, width, height);
            LinearLayout pane = (LinearLayout) workbench.getChildAt(1);
            ScrollView menu = (ScrollView) pane.getChildAt(1);
            List<Button> tools = descendants(menu, Button.class);
            assertEquals("Actual Menu tools", 12, tools.size());
            for (Button button : tools) assertReachable(menu, button);
            System.out.println("MENU_CASE " + configuration(dpi, font, height) + " page=Menu tools=12 reachable=true");

            String[] names = {"Metadata", "BPM", "Line List", "Curve Notes", "Complex Move",
                    "Settings", "Diagnostics", "Line Properties"};
            int[] actionCounts = {2, 4, 4, 2, 2, 2, 1, 2};
            Runnable[] openers = {dialogs::showMetadata, dialogs::showBpmList, dialogs::showLineManager,
                    dialogs::showCurveNotes, dialogs::showComplexMove, dialogs::showSettings,
                    dialogs::showChartDiagnostics,
                    () -> dialogs.judgeLineDialogs.showLineProperties(chart.judgeLines.get(0), () -> {})};
            for (int index = 0; index < names.length; index++) {
                try {
                    openers[index].run(); layout(activity, width, height);
                    ScrollView page = (ScrollView) pane.getChildAt(1);
                    LinearLayout body = (LinearLayout) page.getChildAt(0);
                    assertEquals("Form and actions share the page", 2, body.getChildCount());
                    View actions = body.getChildAt(1);
                    assertTrue("Action row collapsed" + geometry(actions), actions.getHeight() > 0);
                    List<Button> buttons = descendants(actions, Button.class);
                    assertEquals("Expected actions in " + names[index], actionCounts[index], buttons.size());
                    for (Button button : buttons) assertReachable(page, button);
                    for (EditText field : descendants(page, EditText.class))
                        if (field.isShown()) assertReachable(page, field);
                    for (Spinner field : descendants(page, Spinner.class))
                        if (field.isShown()) assertReachable(page, field);
                    System.out.println("MENU_CASE " + configuration(dpi, font, height) + " page="
                            + names[index] + " actions=" + buttons.size() + " reachable=true");
                } catch (AssertionError error) {
                    problems.add(configuration(dpi, font, height) + " page=" + names[index]
                            + ": " + error.getMessage());
                } finally {
                    assertTrue("Close page " + names[index], workbench.back());
                    layout(activity, width, height);
                }
            }
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private static String configuration(int dpi, float font, int height) {
        return "api=" + RuntimeEnvironment.getApiLevel() + " dpi=" + dpi + " font=" + font
                + " width=1536 height=" + height;
    }

    private static void assertReachable(ScrollView page, View target) {
        assertTrue("Zero-size target" + geometry(target), target.getWidth() > 0 && target.getHeight() > 0);
        target.requestRectangleOnScreen(new Rect(0, 0, target.getWidth(), target.getHeight()), true);
        Rect visible = new Rect();
        assertTrue("Unreachable target" + geometry(target), target.getGlobalVisibleRect(visible));
        assertTrue("Vertical clipping" + geometry(target),
                visible.height() >= Math.min(target.getHeight(), page.getHeight()) - 1);
        assertTrue("Horizontal clipping" + geometry(target), visible.width() >= target.getWidth() - 1);
    }

    private static void layout(Activity activity, int width, int height) {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, width, height);
    }

    private static String geometry(View target) {
        StringBuilder result = new StringBuilder();
        for (View v = target; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            result.append("\n").append(v.getClass().getSimpleName()).append(' ')
                    .append(v.getWidth()).append('x').append(v.getHeight())
                    .append(" at ").append(v.getLeft()).append(',').append(v.getTop())
                    .append(" scroll=").append(v.getScrollY());
        }
        return result.toString();
    }

    private static <T extends View> List<T> descendants(View root, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (type.isInstance(root)) found.add(type.cast(root));
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) found.addAll(descendants(group.getChildAt(i), type));
        }
        return found;
    }
}
