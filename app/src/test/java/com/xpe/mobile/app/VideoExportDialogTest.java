package com.xpe.mobile.app;

import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import com.xpe.mobile.R;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.video.VideoExportOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w800dp-h400dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class VideoExportDialogTest {
    @Test public void negativeChartOffsetStartsExportAtSignedAudioTime() {
        MainActivity activity = activity(); AtomicReference<VideoExportOptions> chosen = new AtomicReference<>();
        new VideoExportDialog(activity, true, 1000, -21).show(chosen::set);
        AlertDialog dialog = latestDialog();
        assertEquals("-0.021", views(dialog.getWindow().getDecorView(), EditText.class).get(3).getText().toString());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertNotNull(chosen.get()); assertEquals(-21, chosen.get().startMs);
        assertEquals(0, chosen.get().chartSeconds(0, -21, 0), 0);
    }
    private MainActivity activity() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(R.style.AppTheme); activity.editorSettings = new EditorSettings();
        return activity;
    }
    private static <T> List<T> views(View root, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (type.isInstance(root)) found.add(type.cast(root));
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
            found.addAll(views(((ViewGroup) root).getChildAt(i), type));
        return found;
    }
    private static AlertDialog latestDialog() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        return ShadowAlertDialog.getLatestAlertDialog();
    }
    @Test public void missingMusicKeepsHitsAvailableAndExportsSilentMusicSelection() {
        MainActivity activity = activity();
        activity.getSharedPreferences("video_export", 0).edit().clear().commit();
        AtomicReference<VideoExportOptions> chosen = new AtomicReference<>();
        new VideoExportDialog(activity, false, 2500).show(chosen::set);
        AlertDialog dialog = latestDialog();
        List<CheckBox> checks = views(dialog.getWindow().getDecorView(), CheckBox.class);
        assertEquals(4, checks.size());
        assertFalse(checks.get(0).isEnabled()); assertFalse(checks.get(0).isChecked());
        assertTrue(checks.get(1).isEnabled()); assertTrue(checks.get(1).isChecked());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertNotNull(chosen.get()); assertFalse(chosen.get().music); assertTrue(chosen.get().hits);
        assertEquals(2500, chosen.get().endMs);
    }
    @Test public void invalidRangeStaysOpenAndShowsErrorWithoutSubmitting() {
        MainActivity activity = activity();
        AtomicReference<VideoExportOptions> chosen = new AtomicReference<>();
        new VideoExportDialog(activity, true, 2500).show(chosen::set);
        AlertDialog dialog = latestDialog();
        List<EditText> fields = views(dialog.getWindow().getDecorView(), EditText.class);
        assertEquals(5, fields.size()); fields.get(3).setText("3"); fields.get(4).setText("2");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(dialog.isShowing()); assertNull(chosen.get());
        assertTrue(views(dialog.getWindow().getDecorView(), TextView.class).stream()
                .anyMatch(text -> text.getText().toString().startsWith("Check settings:")));
        dialog.dismiss();
    }
    @Test public void customResolutionFrameRateAndGainsPersistAndAcceptDecimalComma() {
        MainActivity activity = activity();
        AtomicReference<VideoExportOptions> chosen = new AtomicReference<>();
        new VideoExportDialog(activity, true, 1000).show(chosen::set);
        AlertDialog dialog = latestDialog();
        List<EditText> fields = views(dialog.getWindow().getDecorView(), EditText.class);
        fields.get(0).setText("1440"); fields.get(1).setText("960"); fields.get(2).setText("8,5");
        fields.get(3).setText("0,125");
        List<Spinner> selectors = views(dialog.getWindow().getDecorView(), Spinner.class);
        selectors.get(1).setSelection(0);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(1440, chosen.get().width); assertEquals(960, chosen.get().height);
        assertEquals(30, chosen.get().fps); assertEquals(8_500_000, chosen.get().bitrate);
        assertEquals(125, chosen.get().startMs);
        new VideoExportDialog(activity, true, 2000).show(chosen::set);
        AlertDialog restored = latestDialog();
        fields = views(restored.getWindow().getDecorView(), EditText.class);
        assertEquals("1440", fields.get(0).getText().toString());
        assertEquals("960", fields.get(1).getText().toString());
        assertEquals("0", fields.get(3).getText().toString());
        restored.dismiss();
    }
}
