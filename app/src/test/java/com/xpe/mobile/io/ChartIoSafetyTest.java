package com.xpe.mobile.io;

import android.content.Context;
import android.net.Uri;
import com.xpe.mobile.model.ChartDocument;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,33})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ChartIoSafetyTest {
    @Test public void failedAutosaveKeepsTheLastRecoverableVersion() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        ChartDocument chart=ChartDocument.fromJson("{}"); chart.name="Saved chart";
        ChartIo.writeAutosave(context,chart);
        byte[] before=Files.readAllBytes(context.getFileStreamPath("autosave.json").toPath());
        chart.bpmChanges.get(0).bpm=Double.NaN;
        assertThrows(JSONException.class,()->ChartIo.writeAutosave(context,chart));
        assertArrayEquals(before,Files.readAllBytes(context.getFileStreamPath("autosave.json").toPath()));
        assertEquals("Saved chart",ChartIo.readAutosave(context).name);
    }

    @Test public void serializationFailureCannotTruncateTheChosenJsonFile() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        File destination=new File(context.getCacheDir(),"existing-chart.json");
        byte[] original="original document".getBytes(StandardCharsets.UTF_8);
        Files.write(destination.toPath(),original);
        ChartDocument chart=ChartDocument.fromJson("{}"); chart.bpmChanges.get(0).bpm=Double.NaN;
        assertThrows(JSONException.class,()->ChartIo.writeChart(context,Uri.fromFile(destination),chart));
        assertArrayEquals(original,Files.readAllBytes(destination.toPath()));
    }

    @Test public void stagedJsonExportIsCompleteAndReadable() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        File destination=new File(context.getCacheDir(),"chart.json");
        ChartDocument chart=ChartDocument.fromJson("{}"); chart.name="Exported chart";
        ChartIo.writeChart(context,Uri.fromFile(destination),chart);
        assertEquals("Exported chart",ChartIo.readChart(context.getContentResolver(),Uri.fromFile(destination)).name);
    }
}
