package com.xpe.mobile;

import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class WorkbenchMenuTest {
    @Test public void menuKeepsProjectAndChartToolsWithoutStandaloneJsonAudioOrSearch() throws Exception {
        // The factory only needs an attached context. Do not start audio/library background tasks.
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(R.style.AppTheme);
        java.lang.reflect.Method factory = MainActivity.class.getDeclaredMethod("createWorkbenchMenu");
        factory.setAccessible(true);
        ScrollView menu = (ScrollView) factory.invoke(activity);
        LinearLayout content = (LinearLayout) menu.getChildAt(0);
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (int index = 0; index < content.getChildCount(); index++) {
            View child = content.getChildAt(index);
            assertFalse(child instanceof EditText);
            if (child instanceof Button) labels.add(((Button) child).getText().toString());
        }
        int[] expected = {R.string.menu_playtest, R.string.menu_projects, R.string.menu_import_package, R.string.menu_export_package, R.string.menu_export_video,
                R.string.menu_metadata, R.string.menu_bpm, R.string.menu_lines,
                R.string.menu_curve_notes, R.string.menu_complex_move, R.string.menu_settings, R.string.menu_diagnostics};
        assertEquals(expected.length, labels.size());
        for (int index = 0; index < expected.length; index++)
            assertEquals(activity.getString(expected[index]), labels.get(index));
        assertFalse(labels.contains("Import chart JSON"));
        assertFalse(labels.contains("Export chart JSON"));
        assertFalse(labels.contains("Load audio"));
        assertFalse(labels.contains(activity.getString(R.string.menu_storyboard)));
    }
}
