package com.xpe.mobile.editor;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.xpe.mobile.R;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.model.*;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.*;
import org.junit.Before;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;

import static org.junit.Assert.*;

abstract class EditorToolsTestSupport {
    Activity activity;
    EditorView editor;
    WorkbenchView workbench;
    EditorDialogController dialogs;
    LineEvent source;
    String lastMessage;

    @Before public void setupTools() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        editor = new EditorView(activity); editor.setChart(ChartDocument.fromJson("{}"));
        editor.getCurrentJudgeLine().eventLayers.set(0, new EventLayer());
        for (int i = 1; i < 4; i++) {
            JudgeLine line = new JudgeLine(); line.name = "Destination " + i;
            line.eventLayers.clear(); line.eventLayers.add(new EventLayer());
            editor.getChart().judgeLines.add(line);
        }
        source = new LineEvent(); source.type = EventType.MOVE_X;
        source.startTime = new BeatTime(1, 0, 1); source.endTime = new BeatTime(2, 0, 1);
        source.start = 10; source.end = 20; source.easingType = 19;
        editor.getCurrentJudgeLine().layer(0).events(source.type).add(source);
        select(source);
        workbench = new WorkbenchView(activity, editor, () -> new TextView(activity));
        activity.setContentView(workbench);
        EditorDialogController.Host host = (EditorDialogController.Host) java.lang.reflect.Proxy.newProxyInstance(
                EditorDialogController.Host.class.getClassLoader(), new Class<?>[]{EditorDialogController.Host.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "workbench": return workbench;
                        case "editorSettings": return new EditorSettings();
                        case "showMessage": lastMessage = (String) args[0]; return null;
                    }
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == List.class) return Collections.emptyList();
                    return null;
                });
        dialogs = new EditorDialogController(activity, editor, host);
        layout(800, 500);
    }
    void select(LineEvent event) throws Exception {
        Class<?> type = Class.forName("com.xpe.mobile.editor.EditorSurfaceView");
        Field single = type.getDeclaredField("selectedEvent"); single.setAccessible(true); single.set(editor, event);
        Field many = type.getDeclaredField("selectedEvents"); many.setAccessible(true);
        @SuppressWarnings("unchecked") Set<LineEvent> selection = (Set<LineEvent>) many.get(editor);
        selection.clear(); if (event != null) selection.add(event);
        editor.invalidate();
    }
    void settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        layout(800, 500);
    }
    void layout(int width, int height) {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, width, height);
    }
    ScrollView page() {
        LinearLayout pane = (LinearLayout) workbench.getChildAt(1);
        assertEquals(2, pane.getChildCount()); return (ScrollView) pane.getChildAt(1);
    }
    <T extends View> List<T> children(View root, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (type.isInstance(root)) found.add(type.cast(root));
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) found.addAll(children(group.getChildAt(i), type));
        }
        return found;
    }
    String text(int resource) { return activity.getString(resource); }
    Button button(int resource) { return button(text(resource)); }
    Button button(String label) {
        for (Button button : children(page(), Button.class)) if (button.getText().toString().equals(label)) return button;
        throw new AssertionError("Missing button: " + label);
    }
    CheckBox check(int resource) {
        for (CheckBox check : children(page(), CheckBox.class)) if (check.getText().toString().equals(text(resource))) return check;
        throw new AssertionError("Missing checkbox: " + text(resource));
    }
    <T extends View> T labeled(String label, Class<T> type) {
        for (TextView title : children(page(), TextView.class)) {
            if (!title.getText().toString().equals(label) || !(title.getParent() instanceof ViewGroup)) continue;
            ViewGroup parent = (ViewGroup) title.getParent();
            int next = parent.indexOfChild(title) + 1;
            if (next < parent.getChildCount() && type.isInstance(parent.getChildAt(next))) return type.cast(parent.getChildAt(next));
        }
        throw new AssertionError("Missing field: " + label);
    }
    EditText field(int resource) { return labeled(text(resource), EditText.class); }
    Spinner spinner(int resource) { return labeled(text(resource), Spinner.class); }
    String pageText() {
        StringBuilder text = new StringBuilder();
        for (TextView view : children(page(), TextView.class)) if (view.isShown()) text.append(view.getText()).append('\n');
        return text.toString();
    }
    void choose(int... indices) {
        button(R.string.clone_choose_lines).performClick(); settle();
        for (int index : indices) {
            String name = activity.getString(R.string.clone_line_label, index, editor.getChart().judgeLines.get(index).name);
            CheckBox row = children(page(), CheckBox.class).stream().filter(c -> c.getText().toString().startsWith(name)).findFirst().orElseThrow();
            row.setChecked(true);
        }
        button(R.string.clone_use_lines).performClick(); settle();
    }
    LineEvent copyOn(int line) {
        List<LineEvent> events = editor.getChart().judgeLines.get(line).layer(0).events(source.type);
        assertEquals(1, events.size()); return events.get(0);
    }
}
