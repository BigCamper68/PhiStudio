package com.xpe.mobile;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import java.io.File;
import java.lang.reflect.Field;
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
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class VideoExportLifecycleTest {
    @Test public void cancellingFilePickerRetainsRenderedVideoForSavingAgain() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(R.style.AppTheme);
        File ready = File.createTempFile("export-test-", ".mp4", activity.getCacheDir());
        Field file = MainVideoExport.class.getDeclaredField("readyFile"); file.setAccessible(true);
        Field name = MainVideoExport.class.getDeclaredField("readyName"); name.setAccessible(true);
        file.set(activity.mainVideoExport, ready); name.set(activity.mainVideoExport, "Chart.mp4");
        try {
            activity.onActivityResult(MainActivity.SAVE_VIDEO, MainActivity.RESULT_CANCELED, null);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(ready.isFile());
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(activity.getString(R.string.video_save), dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            org.robolectric.shadows.ShadowActivity.IntentForResult request = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            assertEquals(MainActivity.SAVE_VIDEO, request.requestCode);
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.getAction());
            assertEquals("video/mp4", request.intent.getType());
            assertEquals("Chart.mp4", request.intent.getStringExtra(Intent.EXTRA_TITLE));
            assertTrue(ready.isFile());
        } finally { activity.mainVideoExport.close(); }
        assertFalse(ready.exists());
    }
}
