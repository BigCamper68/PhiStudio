package com.xpe.mobile.app;

import android.content.Intent;
import android.os.Looper;
import com.xpe.mobile.packageio.*;
import java.io.*;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34})
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
public class PackageExportDefaultsTest {
    @Test public void exportGoesStraightToFilePickerWithoutFormatOrColorDialog()throws Exception{
        String archive=System.getenv("PHISTUDIO_MULTIPHEX_CHART");Assume.assumeNotNull(archive);
        Path temp=Files.createTempDirectory("export-ui");
        var controller=Robolectric.buildActivity(MainActivity.class).create();MainActivity activity=controller.get();
        try{
            activity.projectExecutor.submit(()->{}).get(30,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
            ChartPackage pack;try(InputStream input=new FileInputStream(archive)){pack=new PackageImporter().importPackage(input,temp.resolve("workspace").toFile(),"MULTIPHEX.zip");}
            activity.currentPackage=pack;activity.editorView.setChart(pack.getChart());
            activity.mainFileActions.requestPackageSave();
            var started=Shadows.shadowOf(activity).getNextStartedActivityForResult();
            assertNotNull("Export must open the ordinary save picker directly",started);
            assertEquals(MainActivity.SAVE_PACKAGE,started.requestCode);
            assertEquals(Intent.ACTION_CREATE_DOCUMENT,started.intent.getAction());
            assertEquals("application/zip",started.intent.getType());
        }finally{
            controller.destroy();try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}
        }
    }
}
