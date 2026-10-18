package com.xpe.mobile.video;

import android.content.Context;
import java.io.File;
import java.nio.file.Files;
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
public class VideoExportStorageTest {
    @Test public void workFilesAreOutsideEvictableCacheAndAbandonedWorkspacesAreReclaimed() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File root = VideoExportStorage.directory(context);
        assertTrue(root.getCanonicalPath().startsWith(context.getNoBackupFilesDir().getCanonicalPath()+File.separator));
        assertFalse(root.getCanonicalPath().startsWith(context.getCacheDir().getCanonicalPath()+File.separator));
        File abandoned = Files.createTempDirectory(root.toPath(),"phistudio-export-").toFile();
        Files.write(new File(abandoned,"music.pcm").toPath(),new byte[]{1,2});
        File first = VideoExportStorage.createWorkspace(context);
        File second = VideoExportStorage.createWorkspace(context);
        try {
            assertFalse(abandoned.exists());
            assertTrue("A second export must not remove the first active workspace",first.isDirectory());
            assertTrue(second.isDirectory());
        } finally {
            VideoExportStorage.removeWorkspace(first);
            VideoExportStorage.removeWorkspace(second);
        }
    }

    @Test public void lowStorageIsRejectedBeforeVideoEncoding() throws Exception {
        File full = new File("unused") { @Override public long getUsableSpace() { return 1024; } };
        VideoExportOptions options = new VideoExportOptions(1280,720,30,8_000_000,0,120_000,true,true,1,1,true,true);
        assertThrows(VideoExportStorage.LowSpaceException.class,()->VideoExportStorage.requireSpace(full,options));
    }
}
