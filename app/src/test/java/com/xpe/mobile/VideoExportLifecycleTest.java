package com.xpe.mobile;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import android.os.Bundle;
import android.os.Handler;
import android.net.Uri;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import com.xpe.mobile.video.VideoExportStorage;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class VideoExportLifecycleTest {
    @Test @Config(sdk={26,33}) public void latePickerResultKeepsTheExportConfirmationAboveTheEditor() throws Exception {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        ActivityController<MainActivity> first = Robolectric.buildActivity(MainActivity.class).create();
        File rendered = File.createTempFile("phistudio-video-", ".mp4", first.get().getCacheDir());
        byte[] expected = new byte[]{0,0,0,16,'f','t','y','p','i','s','o','m',0,0,0,0};
        Files.write(rendered.toPath(), expected); ready(first.get(), rendered);
        Bundle saved = new Bundle(); first.saveInstanceState(saved).destroy();
        ActivityController<MainActivity> second = Robolectric.buildActivity(MainActivity.class).create(saved);
        try {
            MainActivity activity = second.get();
            for (int pass = 0; pass < 3; pass++) {
                activity.projectExecutor.submit(() -> { }).get(30, TimeUnit.SECONDS);
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50));
            }
            android.app.Dialog browser = ReflectionHelpers.getField(activity.projectBrowser, "activeDialog");
            assertTrue("Waiting for the save picker must not open Projects", browser == null || !browser.isShowing());
            File destination = new File(activity.getCacheDir(), "late-picker-video.mp4");
            activity.onActivityResult(MainActivity.SAVE_VIDEO, MainActivity.RESULT_OK,
                    new Intent().setData(Uri.fromFile(destination)));
            Field worker = MainVideoExport.class.getDeclaredField("executor"); worker.setAccessible(true);
            ((ExecutorService) worker.get(activity.mainVideoExport)).submit(() -> { }).get(30, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50));
            AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(confirmation.isShowing());
            assertEquals(activity.getString(R.string.video_saved_title), Shadows.shadowOf(confirmation).getTitle());
            activity.projectExecutor.submit(() -> { }).get(30, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));
            assertSame(confirmation, ShadowAlertDialog.getLatestAlertDialog());
            assertTrue(confirmation.isShowing());
            assertArrayEquals(expected, Files.readAllBytes(destination.toPath()));
        } finally { second.destroy(); }
    }

    @Test @Config(sdk={26,33}) public void successfulSaveConfirmationSurvivesRecreationUntilAcknowledged() throws Exception {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        ActivityController<MainActivity> first = Robolectric.buildActivity(MainActivity.class).create();
        MainActivity activity = first.get();
        for (int pass = 0; pass < 3; pass++) {
            activity.projectExecutor.submit(() -> { }).get(30, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50));
        }
        activity.projectBrowser.dismiss();
        File rendered = File.createTempFile("phistudio-video-", ".mp4", activity.getCacheDir());
        byte[] expected = new byte[]{0,0,0,16,'f','t','y','p','i','s','o','m',0,0,0,0};
        Files.write(rendered.toPath(), expected); ready(activity, rendered);
        File destination = new File(activity.getCacheDir(), "confirmed-video.mp4");
        activity.onActivityResult(MainActivity.SAVE_VIDEO, MainActivity.RESULT_OK,
                new Intent().setData(Uri.fromFile(destination)));
        Field worker = MainVideoExport.class.getDeclaredField("executor"); worker.setAccessible(true);
        ((ExecutorService) worker.get(activity.mainVideoExport)).submit(() -> { }).get(30, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50));
        assertArrayEquals(expected, Files.readAllBytes(destination.toPath()));
        AlertDialog success = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(success.isShowing());
        assertEquals(activity.getString(R.string.video_saved_title), Shadows.shadowOf(success).getTitle());
        Bundle state = new Bundle(); first.saveInstanceState(state).destroy();
        ActivityController<MainActivity> second = Robolectric.buildActivity(MainActivity.class).create(state);
        try {
            for (int pass = 0; pass < 3; pass++) {
                second.get().projectExecutor.submit(() -> { }).get(30, TimeUnit.SECONDS);
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50));
            }
            AlertDialog restored = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(second.get().getString(R.string.video_saved_title), Shadows.shadowOf(restored).getTitle());
            assertTrue(restored.isShowing());
            android.app.Dialog browser = ReflectionHelpers.getField(second.get().projectBrowser, "activeDialog");
            assertTrue(browser == null || !browser.isShowing());
            restored.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50));
            Bundle acknowledged = new Bundle(); second.saveInstanceState(acknowledged);
            assertFalse(acknowledged.containsKey("video_saved_destination"));
            assertArrayEquals(expected, Files.readAllBytes(destination.toPath()));
        } finally { second.destroy(); }
    }

    private void ready(MainActivity activity, File source) throws Exception {
        Field file = MainVideoExport.class.getDeclaredField("readyFile"); file.setAccessible(true);
        Field name = MainVideoExport.class.getDeclaredField("readyName"); name.setAccessible(true);
        file.set(activity.mainVideoExport, source); name.set(activity.mainVideoExport, "Chart.mp4");
    }

    private void finishSaving(MainActivity activity) throws Exception {
        Field field = MainVideoExport.class.getDeclaredField("executor"); field.setAccessible(true);
        ((ExecutorService) field.get(activity.mainVideoExport)).submit(() -> { }).get(30, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test @Config(sdk={26,33}) public void renderedVideoSurvivesRecreationWhileTheSavePickerIsOpen() throws Exception {
        ActivityController<MainActivity> first = Robolectric.buildActivity(MainActivity.class).create();
        File rendered = File.createTempFile("phistudio-video-", ".mp4", first.get().getCacheDir());
        byte[] expected = new byte[]{0,0,0,16,'f','t','y','p','i','s','o','m',0,0,0,0};
        Files.write(rendered.toPath(), expected);
        ready(first.get(), rendered);
        Bundle saved = new Bundle();
        first.saveInstanceState(saved).destroy();
        assertTrue("Recreation must retain the rendered video until it is saved", rendered.isFile());
        ActivityController<MainActivity> second = Robolectric.buildActivity(MainActivity.class).create(saved);
        try {
            File destination = new File(second.get().getCacheDir(), "recreated-video.mp4");
            Files.write(destination.toPath(), new byte[0]);
            second.get().onActivityResult(MainActivity.SAVE_VIDEO, MainActivity.RESULT_OK,
                    new Intent().setData(Uri.fromFile(destination)));
            second.get().projectExecutor.submit(() -> { }).get(30, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            finishSaving(second.get());
            assertArrayEquals(expected, Files.readAllBytes(destination.toPath()));
        } finally { second.destroy(); }
    }

    @Test @Config(sdk={26,33}) public void emptyRenderedVideoDoesNotTruncateTheChosenDestination() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(R.style.AppTheme);
        activity.mainHandler = new Handler(Looper.getMainLooper());
        File rendered = File.createTempFile("phistudio-video-", ".mp4", activity.getCacheDir());
        File destination = new File(activity.getCacheDir(), "existing-video.mp4");
        byte[] original = new byte[]{3,1,4,1,5}; Files.write(destination.toPath(), original);
        ready(activity, rendered);
        try {
            activity.onActivityResult(MainActivity.SAVE_VIDEO, MainActivity.RESULT_OK,
                    new Intent().setData(Uri.fromFile(destination)));
            finishSaving(activity);
            assertArrayEquals(original, Files.readAllBytes(destination.toPath()));
            assertFalse(org.robolectric.shadows.ShadowToast.showedToast(activity.getString(R.string.video_saved_title)));
        } finally { activity.mainVideoExport.close(); }
    }

    @Test @Config(sdk={26,33}) public void interruptedSaveResumesFromPrivateStorageAfterRecreation() throws Exception {
        ActivityController<MainActivity> first=Robolectric.buildActivity(MainActivity.class).create();
        MainActivity activity=first.get();
        File rendered=File.createTempFile("phistudio-video-",".mp4",VideoExportStorage.directory(activity));
        byte[] expected=new byte[]{0,0,0,16,'f','t','y','p','i','s','o','m',0,0,0,0};
        Files.write(rendered.toPath(),expected); ready(activity,rendered);
        File destination=new File(activity.getCacheDir(),"interrupted-video.mp4"); Files.write(destination.toPath(),new byte[0]);
        Field field=MainVideoExport.class.getDeclaredField("executor"); field.setAccessible(true);
        CountDownLatch blocked=new CountDownLatch(1);
        ((ExecutorService)field.get(activity.mainVideoExport)).submit(()->{
            blocked.countDown();
            try { new CountDownLatch(1).await(); } catch(InterruptedException expectedInterrupt) { Thread.currentThread().interrupt(); }
        });
        assertTrue(blocked.await(30,TimeUnit.SECONDS));
        activity.mainVideoExport.acceptDestination(MainActivity.RESULT_OK,new Intent().setData(Uri.fromFile(destination)));
        Bundle saved=new Bundle(); first.saveInstanceState(saved).destroy();
        assertTrue(rendered.isFile());
        ActivityController<MainActivity> second=Robolectric.buildActivity(MainActivity.class).create(saved);
        try {
            second.get().projectExecutor.submit(()->{}).get(30,TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            finishSaving(second.get());
            assertArrayEquals(expected,Files.readAllBytes(destination.toPath()));
            assertFalse(rendered.exists());
        } finally { second.destroy(); }
    }

    @Test public void cancellingFilePickerRetainsRenderedVideoForSavingAgain() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(R.style.AppTheme);
        File ready = File.createTempFile("export-test-", ".mp4", activity.getCacheDir());
        Files.write(ready.toPath(), new byte[]{1,2,3});
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
