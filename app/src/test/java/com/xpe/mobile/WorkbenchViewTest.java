package com.xpe.mobile;

import android.app.Activity;
import android.content.DialogInterface;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Button;
import android.view.Gravity;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.editor.WorkbenchLayout;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class WorkbenchViewTest {
    private Activity activity;
    private EditorView editor;
    private WorkbenchView workbench;
    @Before public void setup() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        editor = new EditorView(activity);
        workbench = new WorkbenchView(activity, editor, () -> new TextView(activity));
        activity.setContentView(workbench);
        editor.setChart(ChartDocument.fromJson("{}"));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        layout(1920, 1080);
    }
    private void layout(int width, int height) {
        workbench.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        workbench.layout(0, 0, width, height);
    }
    @Test public void nativePanelAndCanvasShareTheExactRightHandBoundary() {
        workbench.showMenu();
        layout(1920, 1080);
        View pane = workbench.getChildAt(1), footer = workbench.getChildAt(2);
        float d = activity.getResources().getDisplayMetrics().density;
        assertEquals(Math.round(WorkbenchLayout.split(1920, d)), pane.getLeft());
        assertEquals(pane.getLeft(), footer.getLeft());
        assertEquals(1920, pane.getRight());
        assertEquals(footer.getTop(), pane.getBottom());
        assertEquals(1920, editor.getWidth());
    }
    @Test public void rightFooterContainsOnlyWorkbenchTabsNotArrangeControls() {
        LinearLayout footer = (LinearLayout) workbench.getChildAt(2);
        assertEquals(1, footer.getChildCount());
        LinearLayout row = (LinearLayout) footer.getChildAt(0);
        assertEquals(4, row.getChildCount());
        String[] labels = {"Events", "Storyboard", "Shaders", "Menu"};
        for (int index = 0; index < labels.length; index++)
            assertEquals(labels[index], ((Button) row.getChildAt(index)).getText().toString());
    }
    @Test public void menuAndPropertyTitlesAreCenteredInTheWholePanelWithoutACloseButton() {
        workbench.showMenu();
        for (int width : new int[]{800, 1200, 1920}) {
            for (String label : new String[]{null, "Line list", "Chart metadata", "Settings", "Note properties"}) {
                EditorPanelWindow page = label == null ? null : new EditorPanelWindow.Builder(activity, workbench)
                        .setTitle(label).setView(new TextView(activity)).create();
                if (page != null) page.show();
                layout(width, 700);
                LinearLayout pane = (LinearLayout) workbench.getChildAt(1);
                LinearLayout header = (LinearLayout) pane.getChildAt(0);
                TextView title = (TextView) header.getChildAt(1);
                assertEquals(label == null ? activity.getString(R.string.menu_title) : label,
                        title.getText().toString());
                assertEquals(Gravity.CENTER, title.getGravity());
                assertEquals(pane.getWidth() / 2f, (title.getLeft() + title.getRight()) / 2f, .5f);
                assertTrue(header.getChildAt(0) instanceof Button);
                assertFalse(header.getChildAt(2) instanceof Button);
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, header.getChildAt(2).getImportantForAccessibility());
                if (page != null) page.dismiss();
            }
        }
    }
    @Test public void switchingTabsKeepsDraftsButDoesNotCoverTheOtherTimeline() {
        workbench.switchTab(1);
        EditText draft = new EditText(activity); draft.setText("keep this draft");
        EditorPanelWindow page = new EditorPanelWindow.Builder(activity, workbench)
                .setTitle("Text").setView(draft).setPositiveButton("Apply", null).create();
        page.show();
        workbench.switchTab(0);
        assertEquals(View.GONE, workbench.getChildAt(1).getVisibility());
        workbench.switchTab(1);
        assertEquals(View.VISIBLE, workbench.getChildAt(1).getVisibility());
        assertEquals("keep this draft", draft.getText().toString());
        page.dismiss();
        assertEquals(View.GONE, workbench.getChildAt(1).getVisibility());
    }
    @Test public void backRestoresPreviousPageAndDoesNotDestroyItsInputs() {
        workbench.showMenu();
        EditText first = new EditText(activity); first.setText("first");
        EditorPanelWindow parent = new EditorPanelWindow.Builder(activity, workbench)
                .setTitle("Parent").setView(first).create(); parent.show();
        EditorPanelWindow child = new EditorPanelWindow.Builder(activity, workbench)
                .setTitle("Child").setView(new TextView(activity)).create(); child.show();
        assertTrue(workbench.back());
        assertNotNull(first.getParent());
        assertEquals("first", first.getText().toString());
    }
    @Test public void staleChartCannotBeModifiedEvenBeforeThePostedRefresh() throws Exception {
        boolean[] applied = {false};
        EditorPanelWindow page = new EditorPanelWindow.Builder(activity, workbench)
                .setTitle("Scoped").setView(new TextView(activity))
                .setPositiveButton("Apply", (dialog, which) -> applied[0] = true).create(); page.show();
        editor.setChart(ChartDocument.fromJson("{}"));
        page.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        assertFalse(applied[0]);
    }
    @Test public void lineManagerSurvivesNavigationWhileLinePropertiesAreInvalidated() {
        editor.getChart().judgeLines.add(new JudgeLine());
        EditorPanelWindow manager = new EditorPanelWindow.Builder(activity, workbench)
                .setTitle("Lines").retainOnLineChange().setView(new TextView(activity)).create(); manager.show();
        EditorPanelWindow form = new EditorPanelWindow.Builder(activity, workbench)
                .setTitle("Properties").setView(new TextView(activity)).create(); form.show();
        editor.arrangeLine(1);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNotNull(manager.content.getParent());
        assertNull(form.content.getParent());
    }
    @Test public void validationHandlerCanKeepAnInvalidFormOpen() {
        EditorPanelWindow page = new EditorPanelWindow.Builder(activity, workbench)
                .setTitle("Validate").setView(new TextView(activity)).setPositiveButton("Apply", null).create();
        page.setOnShowListener(dialog -> page.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(view -> { /* Invalid form: deliberately do not dismiss. */ }));
        page.show();
        page.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        assertNotNull(page.content.getParent());
    }
    @Test public void tabsRemainAvailableOnShortDisplaysWhenNoKeyboardIsOpen() {
        workbench.showMenu();
        layout(800, 240);
        assertEquals(View.VISIBLE, workbench.getChildAt(2).getVisibility());
        assertTrue(workbench.getChildAt(2).getHeight() > 0);
    }
    @Test @Config(sdk = {33, 34})
    public void keyboardCanCollapseAndRestorePanelsWithoutInvertedBoundsOrLosingText() {
        EditText draft = new EditText(activity);
        draft.setText("keyboard draft");
        EditorPanelWindow page = new EditorPanelWindow.Builder(activity, workbench)
                .setTitle("Text").setView(draft).create();
        page.show();
        assertTrue(draft.requestFocus());
        for (int height : new int[]{1080, 180, 48, 35, 0, 1080}) {
            layout(1920, height);
            for (int childIndex = 0; childIndex < workbench.getChildCount(); childIndex++) {
                View child = workbench.getChildAt(childIndex);
                assertTrue("child " + childIndex + " height " + height, child.getHeight() >= 0);
                assertTrue(child.getTop() >= 0);
                assertTrue(child.getBottom() <= height);
            }
            assertEquals("keyboard draft", draft.getText().toString());
        }
        assertTrue(draft.isShown());
        assertTrue(draft.requestFocus());
    }
    @Test public void replacingALineAtTheSameIndexStillInvalidatesItsForm() {
        boolean[] applied = {false};
        EditorPanelWindow page = new EditorPanelWindow.Builder(activity, workbench)
                .setTitle("Scoped").setView(new TextView(activity))
                .setPositiveButton("Apply", (dialog, which) -> applied[0] = true).create(); page.show();
        editor.getChart().judgeLines.set(0, new JudgeLine());
        page.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        assertFalse(applied[0]);
    }
}
