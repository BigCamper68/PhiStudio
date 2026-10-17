package com.xpe.mobile.preview;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class PreviewTexturePathTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void resolvesNormalizedRelativePathInsideWorkspace() throws Exception {
        File workspace = temporary.newFolder("project");
        File expected = new File(workspace, "textures/line.png").getCanonicalFile();

        assertEquals(expected, PreviewTexturePath.resolveInside(
                workspace, ".\\textures\\line.png"));
    }

    @Test
    public void rejectsTraversalAbsoluteAndMalformedPaths() throws Exception {
        File workspace = temporary.newFolder("project");

        assertNull(PreviewTexturePath.resolveInside(workspace, "../outside.png"));
        assertNull(PreviewTexturePath.resolveInside(workspace, "textures/../../outside.png"));
        assertNull(PreviewTexturePath.resolveInside(workspace, "/absolute.png"));
        assertNull(PreviewTexturePath.resolveInside(workspace, "C:\\absolute.png"));
        assertNull(PreviewTexturePath.resolveInside(workspace, "textures//line.png"));
        assertNull(PreviewTexturePath.resolveInside(workspace, "line.png\0bad"));
    }
}
