package com.xpe.mobile.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import com.xpe.mobile.io.ChartIo;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.packageio.ProjectWorkspaceCreator;
import com.xpe.mobile.project.ProjectLibrary;
import com.xpe.mobile.shadereditor.ShaderWorkspace;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
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

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,33})
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
public class FilePickerStateTest {
    @Before public void pauseAnimationFrames() {
        // These tests drive picker callbacks, not continuously animated editor frames.
        ShadowChoreographer.setPaused(true);
    }

    private void loaded(MainActivity activity) throws Exception {
        activity.projectExecutor.submit(()->{}).get(30,TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
    }

    @Test public void standaloneJsonExportKeepsUnsavedEditsAcrossRecreationWithAutosaveOff() throws Exception {
        ActivityController<MainActivity> first=Robolectric.buildActivity(MainActivity.class).create();
        MainActivity activity=first.get(); loaded(activity);
        ChartDocument chart=ChartDocument.fromJson("{}"); chart.name="Unsaved chart";
        JudgeLine child=new JudgeLine(); child.father=0; child.rotateWithFather=true; chart.judgeLines.add(child);
        activity.editorView.setChart(chart); activity.editorView.markChartDirty();
        activity.editorSettings.autosaveEnabled=false;
        activity.mainFileActions.requestSave();
        Bundle state=new Bundle(); first.saveInstanceState(state).destroy();
        ActivityController<MainActivity> second=Robolectric.buildActivity(MainActivity.class).create(state);
        try {
            File destination=new File(second.get().getCacheDir(),"standalone.json");
            second.get().onActivityResult(MainActivity.SAVE_CHART,MainActivity.RESULT_OK,new Intent().setData(Uri.fromFile(destination)));
            loaded(second.get());
            ChartDocument exported=ChartIo.readChart(second.get().getContentResolver(),Uri.fromFile(destination));
            assertEquals("Unsaved chart",exported.name);
            assertEquals(0,exported.judgeLines.get(1).father);
            assertTrue(exported.judgeLines.get(1).rotateWithFather);
        } finally { second.destroy(); }
    }

    @Test public void newProjectDraftAndSelectedAssetsSurviveBothPickers() throws Exception {
        ActivityController<MainActivity> first=Robolectric.buildActivity(MainActivity.class).create();
        MainActivity activity=first.get(); loaded(activity);
        activity.newProjectDialog.showCreateProjectDialog();
        activity.newProjectDraft.name.setText("Draft project"); activity.newProjectDraft.bpm.setText("197.5");
        File music=new File(activity.getCacheDir(),"song.ogg"); Files.write(music.toPath(),new byte[]{1,3,5});
        activity.newProjectDialog.acceptNewProjectAsset(MainActivity.SELECT_NEW_PROJECT_AUDIO,Uri.fromFile(music),new Intent());
        activity.newProjectDialog.requestNewProjectAsset(false);
        File image=new File(activity.getCacheDir(),"picture.png"); Files.write(image.toPath(),new byte[]{2,4,6});
        Bundle state=new Bundle(); first.saveInstanceState(state).destroy();
        ActivityController<MainActivity> second=Robolectric.buildActivity(MainActivity.class).create(state);
        try {
            second.get().onActivityResult(MainActivity.SELECT_NEW_PROJECT_ILLUSTRATION,MainActivity.RESULT_OK,new Intent().setData(Uri.fromFile(image)));
            loaded(second.get());
            NewProjectDraft draft=second.get().newProjectDraft;
            assertNotNull(draft); assertTrue(draft.dialog.isShowing());
            assertEquals("Draft project",draft.name.getText().toString());
            assertEquals("197.5",draft.bpm.getText().toString());
            assertEquals(Uri.fromFile(music),draft.audioUri);
            assertEquals(Uri.fromFile(image),draft.illustrationUri);
            second.get().newProjectDialog.createProjectFromDraft(draft); loaded(second.get());
            assertEquals("Draft project",second.get().currentPackage.getChart().name);
            assertEquals(197.5,second.get().currentPackage.getChart().bpmChanges.get(0).bpm,0);
        } finally { second.destroy(); }
    }

    @Test public void shaderImportRestoresTheEditorAndPriorDraftAfterRecreation() throws Exception {
        ActivityController<MainActivity> first=Robolectric.buildActivity(MainActivity.class);
        MainActivity activity=first.get();
        ProjectLibrary library=new ProjectLibrary(new File(activity.getFilesDir(),"project-library"));
        ChartPackage pack=new ProjectWorkspaceCreator().create(library.workspaceForNewProject("shader-test"),
                new ProjectWorkspaceCreator.Spec("shader-test","Shader project","","","",120,"ogg","png"),
                new ByteArrayInputStream(new byte[]{1,3,5}),new ByteArrayInputStream(new byte[]{2,4,6}));
        library.addImportedProject("shader-test",pack,System.currentTimeMillis());
        first.create(); loaded(activity);
        String source="precision mediump float; void main(){gl_FragColor=vec4(1.0);}";
        activity.mainShaderSession.openShaderEditor();
        ShaderWorkspace.write(activity.currentPackage,"shaders/prior.glsl",source);
        activity.currentPackage.registerShaderResource("shaders/prior.glsl");
        activity.shaderDocument.registerSource("/shaders/prior.glsl",source);
        activity.shaderDocument.add("/shaders/prior.glsl",0);
        activity.mainShaderSession.requestImport();
        File incoming=new File(activity.getCacheDir(),"incoming.glsl"); Files.write(incoming.toPath(),source.getBytes(StandardCharsets.UTF_8));
        Bundle state=new Bundle(); first.saveInstanceState(state).destroy();
        ActivityController<MainActivity> second=Robolectric.buildActivity(MainActivity.class).create(state);
        try {
            second.get().onActivityResult(MainActivity.OPEN_SHADER,MainActivity.RESULT_OK,new Intent().setData(Uri.fromFile(incoming)));
            loaded(second.get());
            second.get().previewExecutor.submit(()->{}).get(30,TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
            assertNotNull(second.get().shaderDocument);
            assertEquals(2,new JSONObject(second.get().shaderDocument.json()).getJSONArray("effects").length());
            assertTrue(second.get().shaderDocument.json().contains("prior.glsl"));
            assertTrue(second.get().shaderDocument.json().contains("incoming-"));
        } finally { second.destroy(); }
    }
}
