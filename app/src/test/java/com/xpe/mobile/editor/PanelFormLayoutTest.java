package com.xpe.mobile.editor;

import android.app.Activity;
import android.content.DialogInterface;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.xpe.mobile.R;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.model.*;
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
@Config(sdk = 34, qualifiers = "w800dp-h500dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class PanelFormLayoutTest {
    private Activity activity;
    private EditorView editor;
    private WorkbenchView workbench;
    private EditorDialogController dialogs;
    @Before public void setup() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        editor = new EditorView(activity);
        editor.setChart(ChartDocument.fromJson("{}"));
        workbench = new WorkbenchView(activity, editor, () -> new TextView(activity));
        activity.setContentView(workbench);
        EditorDialogController.Host host = (EditorDialogController.Host) java.lang.reflect.Proxy.newProxyInstance(
                EditorDialogController.Host.class.getClassLoader(), new Class<?>[]{EditorDialogController.Host.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("workbench")) return workbench;
                    if (method.getName().equals("editorSettings")) return new EditorSettings();
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == java.util.List.class) return java.util.Collections.emptyList();
                    return null;
                });
        dialogs = new EditorDialogController(activity, editor, host);
        layout(800, 300);
    }
    private void layout(int width, int height) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, width, height);
    }
    private ScrollView page() {
        LinearLayout pane = (LinearLayout) workbench.getChildAt(1);
        assertEquals(2, pane.getChildCount());
        assertTrue(pane.getChildAt(1) instanceof ScrollView);
        return (ScrollView) pane.getChildAt(1);
    }
    private float[] notePoint(Note note) {
        assertEquals(0, note.startTime.toDouble(), 0);
        float d = activity.getResources().getDisplayMetrics().density;
        float split = com.xpe.mobile.editor.WorkbenchLayout.split(editor.getWidth(), d);
        return new float[]{(float) ((note.positionX + 675) / 1350 * split),
                com.xpe.mobile.editor.EditorTimelineMetrics.baselineY(editor.getHeight()
                        - com.xpe.mobile.editor.WorkbenchLayout.footer(d), d)};
    }
    private void tap(float x, float y) {
        long time = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(time, time, 0, x, y, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(time, time + 10, 1, x, y, 0);
        workbench.dispatchTouchEvent(down); workbench.dispatchTouchEvent(up);
        down.recycle(); up.recycle();
        layout(800, 500);
    }
    @Test public void realNoteAndEditPropertiesTouchesRefreshTheInspectorAndNeverStackIt() {
        layout(800, 500);
        Note first = new Note(); first.positionX = -300;
        Note second = new Note(); second.positionX = 300;
        editor.getCurrentJudgeLine().notes.add(first); editor.getCurrentJudgeLine().notes.add(second);
        editor.setCallback((EditorView.Callback) java.lang.reflect.Proxy.newProxyInstance(
                EditorView.Callback.class.getClassLoader(), new Class<?>[]{EditorView.Callback.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("requestEditNoteProperties")) dialogs.showNoteProperties((Note) arguments[0]);
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                }));
        float[] point = notePoint(first); tap(point[0], point[1]);
        assertSame(first, editor.getSelectedPropertyTarget());
        for (int index = 0; index < 10; index++) {
            float[] properties = {Float.NaN, Float.NaN};
            editor.draw(new android.graphics.Canvas() {
                @Override public void drawText(String text, float x, float y, android.graphics.Paint paint) {
                    if (text.equals(activity.getString(R.string.properties_button))) {
                        properties[0] = x; properties[1] = y;
                    }
                }
            });
            assertTrue(Float.isFinite(properties[0])); tap(properties[0], properties[1]);
        }
        ScrollView original = page();
        point = notePoint(second); tap(point[0], point[1]);
        assertSame(second, editor.getSelectedPropertyTarget());
        assertNotSame(original, page());
        assertEquals(300, Double.parseDouble(children(page(), EditText.class).get(2).getText().toString()), 0);
        // Header Back, rather than an abstract controller call, returns to the event timeline once.
        LinearLayout pane = (LinearLayout) workbench.getChildAt(1);
        ((Button) ((LinearLayout) pane.getChildAt(0)).getChildAt(0)).performClick();
        assertEquals(0, editor.getWorkbenchTab());
        assertEquals(View.GONE, pane.getVisibility()); assertFalse(workbench.back());
    }
    private <T extends View> java.util.List<T> children(View root, Class<T> type) {
        java.util.List<T> found = new java.util.ArrayList<>();
        if (type.isInstance(root)) found.add(type.cast(root));
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) found.addAll(children(group.getChildAt(index), type));
        }
        return found;
    }
    private void assertReachable(ScrollView scroll, View target) {
        assertTrue(target.getMeasuredHeight() > 0);
        target.requestRectangleOnScreen(new Rect(0, 0, target.getWidth(), target.getHeight()), true);
        Rect visible = new Rect();
        StringBuilder geometry = new StringBuilder();
        for (View ancestor = target; ancestor != null;
                ancestor = ancestor.getParent() instanceof View ? (View) ancestor.getParent() : null)
            geometry.append("\n").append(ancestor.getClass().getSimpleName()).append(" ")
                    .append(ancestor.getWidth()).append("x").append(ancestor.getHeight())
                    .append(" at ").append(ancestor.getLeft()).append(",").append(ancestor.getTop())
                    .append(" scroll=").append(ancestor.getScrollY()).append(" visible=").append(ancestor.getVisibility());
        assertTrue("Offscreen " + target + geometry, target.getGlobalVisibleRect(visible));
        assertTrue(visible.height() >= Math.min(target.getHeight(), 24));
    }
    @Test public void actualBpmFieldsAndActionsAreReachableOnShortLandscapeScreens() {
        dialogs.showBpmList();
        for (int height : new int[]{180, 240, 300, 420}) {
            layout(800, height);
            ScrollView scroll = page();
            assertTrue(scroll.getHeight() > 0);
            java.util.List<EditText> fields = children(scroll, EditText.class);
            assertEquals(2, fields.size());
            for (EditText field : fields) assertReachable(scroll, field);
            for (Button button : children(scroll, Button.class)) assertReachable(scroll, button);
        }
    }
    @Test public void bpmAndLineListsShowFourWholeRowsWithOneEqualHeightStretchedActionRow() {
        for (int index = 1; index < 8; index++) {
            BpmChange change = new BpmChange(); change.startTime = new BeatTime(index, 0, 1);
            editor.getChart().bpmChanges.add(change);
            editor.getChart().judgeLines.add(new JudgeLine());
        }
        for (Runnable open : new Runnable[]{dialogs::showBpmList, dialogs::showLineManager}) {
            workbench.showMenu(); open.run(); layout(800, 500);
            ScrollView scroll = page();
            ScrollView list = children(scroll, ScrollView.class).get(1);
            assertEquals(192, list.getHeight());
            java.util.List<Button> rows = children(list, Button.class);
            assertEquals(8, rows.size());
            for (int index = 0; index < 4; index++) {
                assertEquals(48, rows.get(index).getHeight());
                assertTrue(rows.get(index).getBottom() <= list.getHeight());
                assertEquals(android.view.Gravity.CENTER, rows.get(index).getGravity());
            }
            java.util.List<Button> actions = children(scroll, Button.class);
            actions.removeAll(rows);
            assertEquals(4, actions.size());
            View parent = (View) actions.get(0).getParent();
            assertSame(scroll.getChildAt(0), parent.getParent());
            assertEquals(scroll.getWidth(), parent.getWidth());
            assertEquals(0, parent.getLeft());
            int[] rowLocation = new int[2], pageLocation = new int[2];
            parent.getLocationOnScreen(rowLocation); scroll.getLocationOnScreen(pageLocation);
            assertEquals(pageLocation[0], rowLocation[0]);
            for (Button button : actions) {
                assertSame(parent, button.getParent());
                assertEquals(40, button.getHeight());
                assertEquals(android.view.Gravity.CENTER, button.getGravity());
                assertEquals(actions.get(0).getWidth(), button.getWidth());
                assertReachable(scroll, button);
            }
            assertEquals(parent.getWidth(), actions.get(3).getRight());
            assertTrue(workbench.back());
            // Compare against actual Apply/Cancel, not an independently chosen manager height.
            EditorPanelWindow reference = new EditorPanelWindow.Builder(activity, workbench)
                    .setTitle("Two-button reference").setView(new TextView(activity))
                    .setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create();
            reference.show(); layout(800, 500);
            assertEquals(parent.getWidth(), reference.actions.getWidth());
            assertEquals(actions.get(0).getHeight(), reference.getButton(DialogInterface.BUTTON_POSITIVE).getHeight());
            assertEquals(parent.getHeight(), reference.actions.getHeight());
            android.graphics.Rect listPadding = new android.graphics.Rect(), referencePadding = new android.graphics.Rect();
            actions.get(0).getBackground().getPadding(listPadding);
            reference.getButton(DialogInterface.BUTTON_POSITIVE).getBackground().getPadding(referencePadding);
            assertEquals(referencePadding.top, listPadding.top);
            assertEquals(referencePadding.bottom, listPadding.bottom);
            reference.dismiss();
        }
    }
    private void select(Object target) throws Exception {
        Class<?> surface = Class.forName("com.xpe.mobile.editor.EditorSurfaceView");
        java.lang.reflect.Field note = surface.getDeclaredField("selectedNote"); note.setAccessible(true);
        java.lang.reflect.Field event = surface.getDeclaredField("selectedEvent"); event.setAccessible(true);
        note.set(editor, target instanceof Note ? target : null);
        event.set(editor, target instanceof LineEvent ? target : null);
        editor.invalidate();
        layout(800, 500);
    }
    @Test public void repeatedPropertiesDoesNotStackAndKeepsUnsavedDrafts() throws Exception {
        Note note = new Note(); editor.getChart().judgeLines.get(0).notes.add(note);
        select(note); dialogs.showNoteProperties(note); layout(800, 500);
        ScrollView original = page();
        children(original, EditText.class).get(2).setText("123.45");
        for (int index = 0; index < 12; index++) dialogs.showNoteProperties(note);
        layout(800, 500);
        assertSame(original, page());
        assertEquals("123.45", children(page(), EditText.class).get(2).getText().toString());
        assertTrue(workbench.back());
        assertEquals(0, editor.getWorkbenchTab());
        assertEquals(View.GONE, workbench.getChildAt(1).getVisibility());
        assertFalse(workbench.back());
    }
    @Test public void selectedNoteRefreshesOpenPropertiesAndApplyTargetsOnlyTheNewNote() throws Exception {
        Note first = new Note(); first.positionX = -100;
        Note second = new Note(); second.positionX = 200;
        editor.getChart().judgeLines.get(0).notes.add(first);
        editor.getChart().judgeLines.get(0).notes.add(second);
        select(first); dialogs.showNoteProperties(first); layout(800, 500);
        ScrollView previous = page();
        select(second);
        ScrollView current = page(); assertNotSame(previous, current);
        EditText position = children(current, EditText.class).get(2);
        assertEquals(200, Double.parseDouble(position.getText().toString()), 0);
        position.setText("300");
        for (Button button : children(current, Button.class))
            if (button.getText().toString().equals(activity.getString(R.string.action_apply))) button.performClick();
        assertEquals(-100, first.positionX, 0); assertEquals(300, second.positionX, 0);
        layout(800, 500);
        assertEquals(View.GONE, workbench.getChildAt(1).getVisibility());
    }
    @Test public void eventsAndStoryboardPropertiesRefreshWithoutAddingBackStackEntries() throws Exception {
        LineEvent first = new LineEvent(); first.endTime = new BeatTime(1, 0, 1);
        LineEvent second = first.copy(); second.startTime = new BeatTime(2, 0, 1);
        second.endTime = new BeatTime(3, 0, 1); second.start = 123;
        editor.getCurrentJudgeLine().layer(0).events(first.type).add(first);
        editor.getCurrentJudgeLine().layer(0).events(second.type).add(second);
        select(first); dialogs.showEventProperties(first); layout(800, 500);
        for (int index = 0; index < 12; index++) dialogs.showEventProperties(first);
        select(second);
        assertEquals(123, Double.parseDouble(children(page(), EditText.class).get(2).getText().toString()), 0);
        assertTrue(workbench.back());
        assertEquals(0, editor.getWorkbenchTab()); assertFalse(workbench.back());
        select(null); workbench.switchTab(1);
        ExtendedLineEvents.NumericEvent scale = new ExtendedLineEvents.NumericEvent();
        scale.endTime = new BeatTime(1, 0, 1);
        ExtendedLineEvents.TextEvent text = new ExtendedLineEvents.TextEvent();
        text.endTime = new BeatTime(1, 0, 1); text.start = "new text";
        editor.getCurrentJudgeLine().extended.add(StoryboardEventType.SCALE_X, scale);
        editor.getCurrentJudgeLine().extended.add(StoryboardEventType.TEXT, text);
        editor.selectStoryboardEvent(StoryboardEventType.SCALE_X, scale);
        dialogs.showStoryboardProperties(StoryboardEventType.SCALE_X, scale, false); layout(800, 500);
        ScrollView previous = page();
        editor.selectStoryboardEvent(StoryboardEventType.TEXT, text); layout(800, 500);
        assertNotSame(previous, page());
        assertEquals("new text", children(page(), EditText.class).get(2).getText().toString());
        for (int index = 0; index < 12; index++) dialogs.showStoryboardProperties(StoryboardEventType.TEXT, text, false);
        assertTrue(workbench.back());
        assertEquals(1, editor.getWorkbenchTab());
        assertEquals(View.GONE, workbench.getChildAt(1).getVisibility());
    }
    @Test public void realLongPropertyAndSettingsPagesKeepFieldsAndActionsInOneScroll() {
        Note note = new Note(); editor.getChart().judgeLines.get(0).notes.add(note);
        Note end = new Note(); end.startTime = new BeatTime(4, 0, 1); end.endTime = end.startTime;
        editor.getChart().judgeLines.get(0).notes.add(end);
        LineEvent event = new LineEvent(); event.endTime = new BeatTime(1, 0, 1);
        editor.getChart().judgeLines.get(0).layer(0).events(event.type).add(event);
        Runnable[] pages = {dialogs::showMetadata, dialogs::showSettings, dialogs::showLineManager,
                dialogs::showChartDiagnostics, () -> dialogs.showNoteProperties(note),
                () -> dialogs.showEventProperties(event), dialogs::showComplexMove, dialogs::showCurveNotes,
                () -> dialogs.showStoryboardProperties(StoryboardEventType.TEXT, new ExtendedLineEvents.TextEvent(), true)};
        for (Runnable open : pages) {
            open.run();
            for (int height : new int[]{180, 300}) {
                layout(800, height);
                ScrollView scroll = page();
                for (EditText field : children(scroll, EditText.class))
                    if (field.isShown()) assertReachable(scroll, field);
                for (Button button : children(scroll, Button.class)) assertReachable(scroll, button);
            }
            assertTrue(workbench.back());
        }
    }
    @Test public void actionButtonsCannotCoverFieldsEvenWhenTheyNeedMoreHeightThanTheViewport() {
        LinearLayout form = new LinearLayout(activity); form.setOrientation(LinearLayout.VERTICAL);
        for (int index = 0; index < 12; index++) form.addView(new EditText(activity));
        EditorPanelWindow window = new EditorPanelWindow.Builder(activity, workbench).setTitle("Long form")
                .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        window.show(); layout(800, 180);
        ScrollView scroll = page();
        for (EditText field : children(scroll, EditText.class)) assertReachable(scroll, field);
        assertReachable(scroll, window.getButton(DialogInterface.BUTTON_POSITIVE));
        assertEquals(scroll.getChildAt(0), window.actions.getParent());
    }
    @Test public void keyboardResizeKeepsTheWholeBpmFormScrollableAndDoesNotLoseDrafts() {
        dialogs.showBpmList(); layout(800, 500);
        ScrollView scroll = page();
        EditText bpm = children(scroll, EditText.class).get(1);
        bpm.setText("197.25"); bpm.requestFocus(); layout(800, 180);
        assertEquals(View.GONE, workbench.getChildAt(2).getVisibility());
        assertReachable(scroll, bpm);
        workbench.switchTab(0); workbench.switchTab(2); layout(800, 300);
        assertEquals("197.25", bpm.getText().toString());
        assertReachable(page(), bpm);
    }
    @Test public void bpmApplyStillEditsTheRealDocumentAfterScrollingToItsButton() {
        dialogs.showBpmList(); layout(800, 240);
        ScrollView scroll = page();
        children(scroll, EditText.class).get(1).setText("180");
        Button apply = null;
        for (Button button : children(scroll, Button.class))
            if (button.getText().toString().equals(activity.getString(R.string.bpm_list_apply))) apply = button;
        assertNotNull(apply); assertReachable(scroll, apply); apply.performClick();
        assertEquals(180, editor.getChart().bpmChanges.get(0).bpm, 0);
    }
    @Test public void longBpmListsKeepNestedScrollingAndTheFormCanStillBeReached() {
        for (int index = 1; index < 30; index++) {
            BpmChange change = new BpmChange(); change.startTime = new BeatTime(index, 0, 1);
            editor.getChart().bpmChanges.add(change);
        }
        dialogs.showBpmList(); layout(800, 240);
        ScrollView scroll = page();
        java.util.List<ScrollView> scrollers = children(scroll, ScrollView.class);
        assertEquals(2, scrollers.size());
        ScrollView list = scrollers.get(1);
        assertTrue(scroll.isNestedScrollingEnabled()); assertTrue(list.isNestedScrollingEnabled());
        assertEquals(192, list.getHeight());
        Button last = children(list, Button.class).get(29);
        assertReachable(scroll, last); last.performClick();
        layout(800, 240);
        assertEquals(29, BeatTime.parse(children(scroll, EditText.class).get(0).getText().toString()).toDouble(), 0);
        for (EditText field : children(scroll, EditText.class)) assertReachable(scroll, field);
    }
    @Test public void complexMoveHasTheSame29NamedEasingChoicesAsEventsWithoutKeyboardTypeFields() {
        dialogs.showComplexMove(); layout(800, 500);
        java.util.List<android.widget.Spinner> spinners = children(page(), android.widget.Spinner.class);
        assertEquals(2, spinners.size());
        String[] expected = activity.getResources().getStringArray(R.array.event_easing_entries);
        assertEquals(Easing.MAX_TYPE, expected.length);
        for (android.widget.Spinner spinner : spinners) {
            assertEquals(29, spinner.getCount());
            for (int index = 0; index < expected.length; index++) assertEquals(expected[index], spinner.getItemAtPosition(index));
            assertReachable(page(), spinner);
        }
        int visibleFields = 0;
        for (EditText field : children(page(), EditText.class)) if (field.isShown()) visibleFields++;
        assertEquals(5, visibleFields); // Times, equations, density; easing IDs are not EditTexts.
        assertTrue(activity.getString(R.string.complex_move_time_easing_help).contains("29"));
        assertFalse(activity.getString(R.string.complex_move_error_easing).contains("28"));
    }
    @Test public void complexMoveGenerateReadsBothDropdownsIncludingEasing29() {
        assertComplexMoveGeneration(false);
    }
    @Test public void complexMoveDropdownsRetainOptionalBoundedWindowsAndRejectInvalidBounds() {
        assertComplexMoveGeneration(true);
    }
    private void assertComplexMoveGeneration(boolean bounded) {
        editor.getCurrentJudgeLine().layer(0).events(EventType.MOVE_X).clear();
        editor.getCurrentJudgeLine().layer(0).events(EventType.MOVE_Y).clear();
        dialogs.showComplexMove(); layout(800, 500);
        ScrollView scroll = page();
        java.util.List<EditText> fields = children(scroll, EditText.class);
        assertEquals(9, fields.size());
        fields.get(0).setText("1"); fields.get(1).setText("3");
        fields.get(2).setText("100*t"); fields.get(3).setText("100*t"); fields.get(8).setText("2");
        java.util.List<android.widget.Spinner> types = children(scroll, android.widget.Spinner.class);
        types.get(0).setSelection(28); types.get(1).setSelection(2);
        Button generate = null;
        for (Button button : children(scroll, Button.class))
            if (button.getText().toString().equals(activity.getString(R.string.action_generate))) generate = button;
        assertNotNull(generate);
        if (bounded) {
            android.widget.CheckBox enabled = children(scroll, android.widget.CheckBox.class).get(0);
            enabled.setChecked(true); layout(800, 500);
            for (int index = 4; index < 8; index++) assertReachable(scroll, fields.get(index));
            fields.get(4).setText("0.8"); fields.get(5).setText("0.2");
            generate.performClick();
            assertTrue(editor.getCurrentJudgeLine().layer(0).events(EventType.MOVE_X).isEmpty());
            assertSame(scroll, page());
            fields.get(4).setText("0.25"); fields.get(5).setText("0.75");
        }
        layout(800, 500); assertReachable(scroll, generate); generate.performClick();
        com.xpe.mobile.editor.ComplexMoveGenerator.Spec spec = new com.xpe.mobile.editor.ComplexMoveGenerator.Spec();
        spec.startTime = new BeatTime(1, 0, 1); spec.endTime = new BeatTime(3, 0, 1);
        spec.xExpression = "100*t"; spec.yExpression = "100*t"; spec.density = 2;
        spec.xTimeEasing = new com.xpe.mobile.editor.ComplexMoveGenerator.TimeEasing(29, bounded ? .25 : 0, bounded ? .75 : 1);
        spec.yTimeEasing = new com.xpe.mobile.editor.ComplexMoveGenerator.TimeEasing(3, 0, 1);
        com.xpe.mobile.editor.ComplexMoveGenerator.Result expected = com.xpe.mobile.editor.ComplexMoveGenerator.generate(new EventLayer(), 0, spec);
        assertEquals(com.xpe.mobile.editor.ComplexMoveGenerator.Error.NONE, expected.error);
        for (EventType type : new EventType[]{EventType.MOVE_X, EventType.MOVE_Y}) {
            java.util.List<LineEvent> actual = editor.getCurrentJudgeLine().layer(0).events(type);
            java.util.List<LineEvent> target = type == EventType.MOVE_X ? expected.moveXEvents : expected.moveYEvents;
            assertEquals(target.size(), actual.size());
            for (int index = 0; index < target.size(); index++) {
                assertEquals(target.get(index).start, actual.get(index).start, 1e-9);
                assertEquals(target.get(index).end, actual.get(index).end, 1e-9);
            }
        }
    }
    @Test public void realListDragsContinueAfterClicksTabSwitchesAndCancellationsInBothManagers() {
        for (int index = 1; index < 30; index++) {
            BpmChange change = new BpmChange(); change.startTime = new BeatTime(index, 0, 1);
            editor.getChart().bpmChanges.add(change); editor.getChart().judgeLines.add(new JudgeLine());
        }
        for (Runnable open : new Runnable[]{dialogs::showBpmList, dialogs::showLineManager}) {
            workbench.showMenu(); open.run(); layout(800, 500);
            ScrollView outer = page();
            ScrollView list = children(outer, ScrollView.class).get(1);
            assertTrue(list instanceof EditorListScrollView);
            list.scrollTo(0, 240);
            for (int cycle = 0; cycle < 6; cycle++) {
                int previous = list.getScrollY();
                dragList(list, true); assertTrue("Up drag " + cycle, list.getScrollY() > previous);
                previous = list.getScrollY();
                dragList(list, false); assertTrue("Down drag " + cycle, list.getScrollY() < previous);
                children(list, Button.class).get(6).performClick(); layout(800, 500);
                workbench.switchTab(0); workbench.switchTab(2); layout(800, 500);
                assertSame(outer, page());
                outer.scrollTo(0, 0); list.scrollTo(0, 240);
            }
            assertTrue(workbench.back());
        }
    }
    private void dragList(ScrollView list, boolean up) {
        Rect visible = new Rect(); assertTrue(list.getGlobalVisibleRect(visible));
        long time = android.os.SystemClock.uptimeMillis();
        float x = visible.centerX(), start = up ? visible.bottom - 20 : visible.top + 20;
        float distance = Math.min(90, visible.height() - 40) * (up ? -1 : 1);
        for (int step = 0; step <= 5; step++) {
            android.view.MotionEvent event = android.view.MotionEvent.obtain(time, time + step * 16,
                    step == 0 ? android.view.MotionEvent.ACTION_DOWN : android.view.MotionEvent.ACTION_MOVE,
                    x, start + distance * step / 5f, 0);
            workbench.dispatchTouchEvent(event); event.recycle();
        }
        android.view.MotionEvent cancel = android.view.MotionEvent.obtain(time, time + 100,
                android.view.MotionEvent.ACTION_CANCEL, x, start + distance, 0);
        workbench.dispatchTouchEvent(cancel); cancel.recycle();
        layout(800, activity.getWindow().getDecorView().getHeight());
    }
    @Test public void excessListDragReachesTheFormAndCancelReleasesTheNestedScrollOwner() {
        for (int index = 1; index < 30; index++) {
            BpmChange change = new BpmChange(); change.startTime = new BeatTime(index, 0, 1);
            editor.getChart().bpmChanges.add(change); editor.getChart().judgeLines.add(new JudgeLine());
        }
        for (Runnable open : new Runnable[]{dialogs::showBpmList, dialogs::showLineManager}) {
            workbench.showMenu(); open.run(); layout(800, 300);
            ScrollView outer = page(), list = children(outer, ScrollView.class).get(1);
            int maximum = list.getChildAt(0).getHeight() - list.getHeight();
            list.scrollTo(0, maximum); outer.scrollTo(0, 0);
            dragList(list, true);
            assertTrue("Unconsumed list distance must scroll the outer form", outer.getScrollY() > 0);
            assertEquals(0, outer.getNestedScrollAxes());
            // A new gesture must regain ownership even after reaching an edge and cancelling.
            outer.scrollTo(0, 0); list.scrollTo(0, 240);
            dragList(list, true); assertTrue(list.getScrollY() > 240);
            assertEquals(0, outer.getNestedScrollAxes());
            assertTrue(workbench.back());
        }
    }
}
