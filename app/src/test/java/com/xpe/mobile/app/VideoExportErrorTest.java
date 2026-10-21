package com.xpe.mobile.app;

import android.system.ErrnoException;
import android.system.OsConstants;
import com.xpe.mobile.R;
import com.xpe.mobile.i18n.UiErrors;
import com.xpe.mobile.video.VideoExportStorage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,33})
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class VideoExportErrorTest {
    @Test public void screenshotPcmErrorExplainsStorageAndKeepsTechnicalDetailsSeparate() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        Exception error = new FileNotFoundException("/data/user/0/com.xpe.mobile/cache/"
                + "phistudio-export-4687721757276221772/music.pcm: open failed: ENOENT (No such file or directory)");
        activity.languageController.select("en");
        assertEquals(activity.getString(R.string.video_temporary_missing), UiErrors.video(activity,error));
        assertTrue(UiErrors.video(activity,error).contains("Free up internal storage"));
        assertFalse(UiErrors.video(activity,error).contains("/data/user"));
        assertTrue(UiErrors.describe(activity,error).contains("music.pcm"));
        activity.languageController.select("zh-CN");
        assertEquals(activity.getString(R.string.video_temporary_missing), UiErrors.video(activity,error));
    }

    @Test public void noSpaceAndQuotaFailuresUseStorageGuidanceEvenWhenWrapped() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        for (Throwable error : new Throwable[]{new VideoExportStorage.LowSpaceException(),
                new IOException("write failed", new ErrnoException("write",OsConstants.ENOSPC)),
                new IOException("EDQUOT (Disk quota exceeded)"),
                new IOException("music.pcm: ENOENT phistudio-export-1", new ErrnoException("write",OsConstants.ENOSPC))}) {
            assertEquals(activity.getString(R.string.video_storage_full),UiErrors.video(activity,error));
        }
    }

    @Test public void missingSourceMusicAndPermissionErrorsAreNotReportedAsLowStorage() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        for (Throwable error : new Throwable[]{new FileNotFoundException("/sdcard/song.ogg: ENOENT"),
                new IOException("phistudio-export-1/music.pcm: EACCES (Permission denied)"),
                new IOException("Encoder stopped responding")}) {
            assertEquals(error.getMessage(), UiErrors.video(activity,error));
        }
        Exception temp = new NoSuchFileException("/data/video-exports/phistudio-export-1/video.mp4");
        assertEquals(activity.getString(R.string.video_temporary_missing),UiErrors.video(activity,temp));
    }

    @Test public void cyclicExceptionCausesCannotHangTheErrorDialog() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        IOException first = new IOException("First"), second = new IOException("Second");
        first.initCause(second); second.initCause(first);
        assertEquals("First",UiErrors.video(activity,first));
    }
}
