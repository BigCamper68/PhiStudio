package com.xpe.mobile.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import com.xpe.mobile.R;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.packageio.PackageWorkspaceWriter;
import com.xpe.mobile.packageio.ProjectWorkspaceCreator;
import com.xpe.mobile.project.ProjectLibrary;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.json.JSONObject;
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
import org.robolectric.shadows.ShadowToast;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 33})
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class PackageExportLifecycleTest {
    private void createProject(MainActivity activity) throws Exception {
        ProjectLibrary library = new ProjectLibrary(new File(activity.getFilesDir(), "project-library"));
        String id = "parent-export-test";
        ChartPackage pack = new ProjectWorkspaceCreator().create(library.workspaceForNewProject(id),
                new ProjectWorkspaceCreator.Spec(id, "Parent export", "", "", "", 120, "ogg", "png"),
                new ByteArrayInputStream(new byte[]{1, 3, 5}),
                new ByteArrayInputStream(new byte[]{2, 4, 6}));
        JudgeLine child = new JudgeLine(); child.name = "Line 1";
        child.father = 0; child.rotateWithFather = true;
        pack.getChart().judgeLines.add(child);
        PackageWorkspaceWriter.writeChart(pack, pack.getChart());
        library.addImportedProject(id, pack, System.currentTimeMillis());
    }

    private void finishProjectLoad(MainActivity activity) throws Exception {
        activity.projectExecutor.submit(() -> { }).get(30, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void assertExportedParent(File destination) throws Exception {
        assertTrue("Export must produce a complete ZIP after project restoration", destination.length() > 0);
        try (ZipFile zip = new ZipFile(destination)) {
            JSONObject root = new JSONObject(new String(zip.getInputStream(zip.getEntry("chart.json")).readAllBytes(),
                    StandardCharsets.UTF_8));
            JSONObject child = root.getJSONArray("judgeLineList").getJSONObject(1);
            assertEquals(0, child.getInt("father"));
            assertEquals(Boolean.TRUE, child.get("rotateWithFather"));
            assertArrayEquals(new byte[]{1, 3, 5}, zip.getInputStream(zip.getEntry("audio.ogg")).readAllBytes());
            assertArrayEquals(new byte[]{2, 4, 6}, zip.getInputStream(zip.getEntry("illustration.png")).readAllBytes());
        }
    }

    @Test public void exportPickerResultWaitsForProjectRestorationAndPreservesParents() throws Exception {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        MainActivity activity = controller.get();
        createProject(activity);
        controller.create();
        try {
            File destination = new File(activity.getCacheDir(), "parents-export.zip");
            Files.write(destination.toPath(), new byte[0]); // Android's picker creates this placeholder.
            assertNull(activity.currentPackage);
            activity.onActivityResult(MainActivity.SAVE_PACKAGE, MainActivity.RESULT_OK,
                    new Intent().setData(Uri.fromFile(destination)));
            assertNotEquals(activity.getString(R.string.package_required), ShadowToast.getTextOfLatestToast());
            finishProjectLoad(activity);
            assertExportedParent(destination);
            assertTrue(ShadowToast.showedToast(activity.getString(R.string.package_exported,
                    activity.currentPackage.getProjectName())));
        } finally {
            controller.destroy();
        }
    }

    @Test public void deferredExportSurvivesAnotherRecreationWithoutASecondPickerResult() throws Exception {
        ActivityController<MainActivity> first = Robolectric.buildActivity(MainActivity.class);
        MainActivity activity = first.get();
        createProject(activity);
        first.create();
        File destination = new File(activity.getCacheDir(), "pending-parents-export.zip");
        Files.write(destination.toPath(), new byte[0]);
        activity.onActivityResult(MainActivity.SAVE_PACKAGE, MainActivity.RESULT_OK,
                new Intent().setData(Uri.fromFile(destination)));
        Bundle saved = new Bundle();
        first.saveInstanceState(saved).destroy();
        ActivityController<MainActivity> second = Robolectric.buildActivity(MainActivity.class).create(saved);
        try {
            finishProjectLoad(second.get());
            assertExportedParent(destination);
        } finally {
            second.destroy();
        }
    }

    @Test public void sourceOverwriteProtectionSurvivesRecreationWhileThePickerIsOpen() throws Exception {
        ActivityController<MainActivity> first = Robolectric.buildActivity(MainActivity.class);
        MainActivity activity = first.get();
        createProject(activity);
        first.create();
        File source = new File(activity.getCacheDir(), "original-package.zip");
        byte[] original = new byte[]{7, 9, 11};
        Files.write(source.toPath(), original);
        activity.currentPackageSourceUri = Uri.fromFile(source);
        Bundle saved = new Bundle();
        first.saveInstanceState(saved).destroy();
        ActivityController<MainActivity> second = Robolectric.buildActivity(MainActivity.class).create(saved);
        try {
            second.get().onActivityResult(MainActivity.SAVE_PACKAGE, MainActivity.RESULT_OK,
                    new Intent().setData(Uri.fromFile(source)));
            finishProjectLoad(second.get());
            assertArrayEquals(original, Files.readAllBytes(source.toPath()));
            assertTrue(ShadowToast.showedToast(second.get().getString(R.string.package_source_overwrite_blocked)));
        } finally {
            second.destroy();
        }
    }
}
