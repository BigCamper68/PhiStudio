package com.xpe.mobile;

import android.app.Dialog;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.os.Looper;
import android.os.Bundle;
import com.xpe.mobile.io.ChartIo;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.BeatTime;
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
import org.json.JSONObject;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 33})
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
public class EditorSessionLifecycleTest {
    @Test public void backgroundRecreationKeepsUnsavedChartPositionLineZoomAndSelectionWithAutosaveOff() throws Exception {
        ActivityController<MainActivity> first=withProject().start().resume();
        MainActivity activity=first.get(); settle(activity); activity.projectBrowser.dismiss();
        activity.editorSettings.autosaveEnabled=false;
        ChartDocument chart=activity.editorView.getChart(); chart.name="In progress";
        chart.judgeLines.add(new JudgeLine()); Note note=new Note();
        while (chart.judgeLines.get(1).eventLayers.size()<3) chart.judgeLines.get(1).eventLayers.add(new com.xpe.mobile.model.EventLayer());
        note.startTime=new BeatTime(421,3,4); note.endTime=note.startTime; chart.judgeLines.get(1).notes.add(note);
        activity.editorView.markChartDirty();
        activity.editorView.restoreEditorSession(new JSONObject("{\"line\":1,\"layer\":2,\"beat\":421.747,\"zoom\":167.5,\"division\":12,\"xGrid\":33,\"tab\":2,\"panel\":\"EDIT\",\"note\":0,\"notes\":[0],\"tool\":\"FLICK\"}"));
        Bundle state=new Bundle(); first.pause().stop().saveInstanceState(state).destroy();
        android.os.Parcel parcel=android.os.Parcel.obtain();
        try { parcel.writeBundle(state); assertTrue("Chart data must not enter Android's saved-state transaction",parcel.dataSize()<8192); }
        finally { parcel.recycle(); }
        ActivityController<MainActivity> second=Robolectric.buildActivity(MainActivity.class).create(state);
        try {
            settle(second.get());
            assertEquals("In progress",second.get().editorView.getChart().name);
            assertEquals(421.747,second.get().editorView.getCurrentBeat(),0);
            assertEquals(1,second.get().editorView.getLineIndex()); assertEquals(2,second.get().editorView.getEventLayerIndex());
            assertEquals(12,second.get().editorView.getSubdivision()); assertEquals(2,second.get().editorView.getWorkbenchTab());
            assertEquals(167.5,second.get().editorView.saveEditorSession().getDouble("zoom"),.001);
            assertSame(second.get().editorView.getCurrentJudgeLine().notes.get(0),second.get().editorView.getSelectedPropertyTarget());
            assertFalse(browserShowing(second.get()));
        } finally { second.destroy(); }
    }

    @Test public void standaloneDraftSurvivesBackgroundWithPeriodicAutosaveDisabled() throws Exception {
        ActivityController<MainActivity> first=Robolectric.buildActivity(MainActivity.class).create().start().resume();
        MainActivity activity=first.get(); settle(activity); activity.projectBrowser.dismiss();
        activity.editorSettings.autosaveEnabled=false;
        ChartDocument chart=ChartDocument.fromJson("{}"); chart.id="background-draft"; chart.name="Unexported draft";
        activity.editorView.setChart(chart); activity.editorView.markChartDirty(); activity.editorView.setProjectName("draft.json");
        activity.editorView.restoreEditorSession(new JSONObject().put("beat",65.125).put("projectName","draft.json"));
        Bundle state=new Bundle(); first.pause().stop().saveInstanceState(state).destroy();
        ActivityController<MainActivity> second=Robolectric.buildActivity(MainActivity.class).create(state);
        try {
            settle(second.get()); assertEquals("Unexported draft",second.get().editorView.getChart().name);
            assertEquals(65.125,second.get().editorView.getCurrentBeat(),0);
            assertEquals("draft.json",second.get().editorView.saveEditorSession().getString("projectName"));
            assertFalse(browserShowing(second.get()));
        } finally { second.destroy(); }
    }

    @Test public void backgroundStopsTheEditorClockEvenWhileMusicIsUnavailable() throws Exception {
        ActivityController<MainActivity> controller=withProject().start().resume();
        try {
            MainActivity activity=controller.get(); settle(activity); activity.projectBrowser.dismiss();
            activity.editorView.restoreEditorSession(new JSONObject().put("beat",123.625));
            activity.editorView.shaderEditorPlay(); assertTrue(activity.editorView.isPlaying());
            controller.pause().stop();
            org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
            controller.restart().start().resume(); settle(activity);
            assertFalse(activity.editorView.isPlaying()); assertEquals(123.625,activity.editorView.getCurrentBeat(),0);
        } finally { controller.pause().stop().destroy(); }
    }

    @Test public void anInterruptedRestoreKeepsTheOriginalCheckpointForTheNextActivity() throws Exception {
        ActivityController<MainActivity> first=withProject(); settle(first.get()); first.get().projectBrowser.dismiss();
        first.get().editorView.restoreEditorSession(new JSONObject().put("beat",88.75));
        Bundle state=new Bundle(); first.saveInstanceState(state).destroy();
        ActivityController<MainActivity> interrupted=Robolectric.buildActivity(MainActivity.class).create(state);
        Bundle again=new Bundle(); interrupted.saveInstanceState(again).destroy();
        ActivityController<MainActivity> restored=Robolectric.buildActivity(MainActivity.class).create(again);
        try { settle(restored.get()); assertEquals(88.75,restored.get().editorView.getCurrentBeat(),0); }
        finally { restored.destroy(); }
    }

    @Test public void taskHistoryRestoresTheEditorEvenWithoutASavedInstanceBundle() throws Exception {
        ActivityController<MainActivity> first=withProject().start().resume();
        settle(first.get()); first.get().projectBrowser.dismiss();
        first.get().editorView.restoreEditorSession(new JSONObject().put("beat",333.125));
        int taskId=first.get().getTaskId(); first.pause().stop().destroy();
        Intent history=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
        ActivityController<MainActivity> second=Robolectric.buildActivity(MainActivity.class,history).create();
        try {
            assertEquals(taskId,second.get().getTaskId()); settle(second.get());
            assertEquals(333.125,second.get().editorView.getCurrentBeat(),0);
            assertFalse(browserShowing(second.get()));
        } finally { second.destroy(); }
    }

    @Test public void historyForAnotherTaskDoesNotApplyAnOldEditorCheckpoint() throws Exception {
        ActivityController<MainActivity> first=withProject().start().resume();
        settle(first.get()); first.get().projectBrowser.dismiss();
        first.get().editorView.restoreEditorSession(new JSONObject().put("beat",333.125));
        first.pause().stop().destroy();
        File checkpoint=new File(first.get().getNoBackupFilesDir(),"editor-session.json");
        JSONObject saved=new JSONObject(new String(java.nio.file.Files.readAllBytes(checkpoint.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        saved.put("task",first.get().getTaskId()+1);
        java.nio.file.Files.write(checkpoint.toPath(),saved.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Intent history=new Intent(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
        ActivityController<MainActivity> second=Robolectric.buildActivity(MainActivity.class,history).create();
        try {
            settle(second.get()); assertEquals(0,second.get().editorView.getCurrentBeat(),0);
            assertTrue(browserShowing(second.get()));
        } finally { second.destroy(); }
    }

    @Test public void recreationKeepsMusicSelectedSeparatelyFromTheChartPackage() throws Exception {
        ActivityController<MainActivity> first=withProject().start().resume();
        settle(first.get()); first.get().projectBrowser.dismiss();
        File music=new File(first.get().getFilesDir(),"alternate-music.ogg");
        java.nio.file.Files.write(music.toPath(),new byte[]{8,9,10});
        android.net.Uri selected=android.net.Uri.fromFile(music);
        first.get().mainPreviewResources.loadAudio(selected,true);
        first.get().editorView.restoreEditorSession(new JSONObject().put("beat",88.75));
        Bundle state=new Bundle(); first.pause().stop().saveInstanceState(state).destroy();
        ActivityController<MainActivity> second=Robolectric.buildActivity(MainActivity.class).create(state);
        try {
            settle(second.get()); assertEquals(selected,second.get().audioController.sourceUri());
            assertEquals(88.75,second.get().editorView.getCurrentBeat(),0);
            assertFalse(browserShowing(second.get()));
        } finally { second.destroy(); }
    }

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
        ActivityController<MainActivity> first = withProject().start().resume();
        settle(first.get()); first.get().projectBrowser.dismiss(); first.pause().stop().destroy();
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
