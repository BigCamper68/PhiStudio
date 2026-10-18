package com.xpe.mobile;

import android.app.Dialog;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.os.Looper;
import android.os.Bundle;
import com.xpe.mobile.io.ChartIo;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.packageio.ProjectWorkspaceCreator;
import com.xpe.mobile.project.ProjectLibrary;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 33})
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
public class EditorSessionLifecycleTest {
    @Before public void pauseFrames() { ShadowChoreographer.setPaused(true); }

    private void settle(MainActivity activity) throws Exception {
        // A startup browser discovery may be queued by the initial load callback.
        for (int pass = 0; pass < 3; pass++) {
            activity.projectExecutor.submit(() -> { }).get(30, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
        }
    }

    private boolean browserShowing(MainActivity activity) {
        Dialog dialog = ReflectionHelpers.getField(activity.projectBrowser, "activeDialog");
        return dialog != null && dialog.isShowing();
    }

    private ActivityController<MainActivity> withProject() throws Exception {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        MainActivity activity = controller.get();
        ProjectLibrary library = new ProjectLibrary(new File(activity.getFilesDir(), "project-library"));
        ChartPackage pack = new ProjectWorkspaceCreator().create(library.workspaceForNewProject("resume-test"),
                new ProjectWorkspaceCreator.Spec("resume-test", "Resume chart", "", "", "", 120, "ogg", "png"),
                new ByteArrayInputStream(new byte[]{1, 3, 5}), new ByteArrayInputStream(new byte[]{2, 4, 6}));
        library.addImportedProject("resume-test", pack, System.currentTimeMillis());
        return controller.create();
    }

    @Test public void coldLaunchShowsProjectsWithTheLastChartAvailable() throws Exception {
        ActivityController<MainActivity> controller = withProject();
        try {
            MainActivity activity = controller.get(); settle(activity);
            assertEquals("resume-test", activity.currentProjectId);
            assertTrue("A fresh launch must open Projects even when a last chart exists", browserShowing(activity));
            activity.projectBrowser.dismiss(); assertFalse(browserShowing(activity));
            activity.mainProjectActions.showProjectLibrary(false); settle(activity);
            assertTrue(browserShowing(activity));
        } finally { controller.destroy(); }
    }

    @Test public void coldLaunchShowsProjectsWithAStandaloneAutosave() throws Exception {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        ChartDocument chart = ChartDocument.fromJson("{}"); chart.name = "Standalone session";
        ChartIo.writeAutosave(controller.get(), chart);
        controller.create();
        try {
            settle(controller.get());
            assertEquals("Standalone session", controller.get().editorView.getChart().name);
            assertTrue(browserShowing(controller.get()));
        } finally { controller.destroy(); }
    }

    @Test public void fullRestartReopensProjectsAfterAnEditorSession() throws Exception {
        ActivityController<MainActivity> first = withProject();
        settle(first.get()); first.get().projectBrowser.dismiss(); first.destroy();
        ActivityController<MainActivity> second = Robolectric.buildActivity(MainActivity.class).create();
        try {
            settle(second.get());
            assertEquals("resume-test", second.get().currentProjectId);
            assertTrue("A complete restart must return to Projects", browserShowing(second.get()));
        } finally { second.destroy(); }
    }

    @Test public void recreatedEditorDoesNotOpenProjectsOverTheRestoredChart() throws Exception {
        ActivityController<MainActivity> first = withProject();
        settle(first.get()); first.get().projectBrowser.dismiss();
        Bundle state = new Bundle(); first.saveInstanceState(state).destroy();
        ActivityController<MainActivity> second = Robolectric.buildActivity(MainActivity.class).create(state);
        try {
            settle(second.get());
            assertEquals("resume-test", second.get().currentProjectId);
            assertFalse(browserShowing(second.get()));
        } finally { second.destroy(); }
    }

    @Test public void recreatedStandaloneSessionDoesNotOpenProjects() throws Exception {
        ActivityController<MainActivity> first = Robolectric.buildActivity(MainActivity.class);
        ChartDocument chart = ChartDocument.fromJson("{}"); chart.name = "Standalone session";
        ChartIo.writeAutosave(first.get(), chart);
        first.create(); settle(first.get()); first.get().projectBrowser.dismiss();
        Bundle state = new Bundle(); first.saveInstanceState(state).destroy();
        ActivityController<MainActivity> second = Robolectric.buildActivity(MainActivity.class).create(state);
        try {
            settle(second.get());
            assertEquals("Standalone session", second.get().editorView.getChart().name);
            assertFalse(browserShowing(second.get()));
        } finally { second.destroy(); }
    }

    @Test public void firstLaunchStillOffersProjects() throws Exception {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create();
        try { settle(controller.get()); assertTrue(browserShowing(controller.get())); }
        finally { controller.destroy(); }
    }

    @Test public void backgroundAndReturnKeepsTheChartAndAudioController() throws Exception {
        ActivityController<MainActivity> controller = withProject().start().resume();
        try {
            MainActivity activity = controller.get(); settle(activity); activity.projectBrowser.dismiss();
            ChartDocument chart = activity.editorView.getChart();
            Object audio = activity.audioController;
            int generation = ReflectionHelpers.getField(audio, "audioLoadGeneration");
            controller.pause().stop().restart().start().resume(); settle(activity);
            assertSame(chart, activity.editorView.getChart());
            assertSame(audio, activity.audioController);
            assertEquals(generation, (int) ReflectionHelpers.getField(audio, "audioLoadGeneration"));
            assertFalse(browserShowing(activity));
        } finally { controller.pause().stop().destroy(); }
    }

    @Test public void launcherReentryKeepsTheCurrentSession() throws Exception {
        ActivityController<MainActivity> controller = withProject().start().resume();
        try {
            MainActivity activity = controller.get(); settle(activity); activity.projectBrowser.dismiss();
            ActivityInfo info = activity.getPackageManager().getActivityInfo(
                    new ComponentName(activity, MainActivity.class), 0);
            assertEquals("Launcher reentry must reuse the existing editor task", ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode);
            ChartDocument chart = activity.editorView.getChart();
            Object audio = activity.audioController;
            int generation = ReflectionHelpers.getField(audio, "audioLoadGeneration");
            controller.pause().stop();
            controller.newIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(activity, MainActivity.class))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            controller.restart().start().resume(); settle(activity);
            assertSame(chart, activity.editorView.getChart());
            assertSame(audio, activity.audioController);
            assertEquals(generation, (int) ReflectionHelpers.getField(audio, "audioLoadGeneration"));
            assertFalse(browserShowing(activity));
        } finally { controller.pause().stop().destroy(); }
    }

    @Test public void backFromTheRootEditorKeepsTheActivityForReturning() throws Exception {
        ActivityController<MainActivity> controller = withProject();
        try {
            MainActivity activity = controller.get(); settle(activity); activity.projectBrowser.dismiss();
            Shadows.shadowOf(activity).setIsTaskRoot(true);
            activity.editorView.setWorkbenchTab(0);
            activity.onBackPressed();
            assertFalse("Root Back should background the editor instead of finishing it", activity.isFinishing());
        } finally { controller.destroy(); }
    }
}
