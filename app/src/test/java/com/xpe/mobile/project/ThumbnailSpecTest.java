package com.xpe.mobile.project;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ThumbnailSpecTest {
    private File root;

    @Before
    public void setUp() throws Exception {
        root = Files.createTempDirectory("xpe-thumbnail-").toFile();
    }

    @After
    public void tearDown() {
        File[] children = root.listFiles();
        if (children != null) for (File child : children) child.delete();
        root.delete();
    }

    @Test
    public void keepsSmallImageAtFullSample() {
        assertEquals(1, ThumbnailSpec.inSampleSize(320, 180, 480, 270, 1_000_000L));
    }

    @Test
    public void boundsHugeImageToSafePixelCount() {
        int sample = ThumbnailSpec.inSampleSize(20000, 12000, 480, 270, 1_000_000L);
        long width = Math.max(1, 20000 / sample);
        long height = Math.max(1, 12000 / sample);

        assertTrue(sample >= 16);
        assertTrue(width * height <= 1_000_000L);
        assertTrue(width <= 960L);
        assertTrue(height <= 540L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidDimensions() {
        ThumbnailSpec.inSampleSize(0, 100, 480, 270, 1_000_000L);
    }

    @Test
    public void cacheKeyChangesWhenSourceMetadataChanges() throws Exception {
        File image = new File(root, "cover.png");
        Files.write(image.toPath(), new byte[]{1, 2, 3});
        image.setLastModified(1000L);
        String first = ThumbnailCacheKey.forIllustration("project", image);

        Files.write(image.toPath(), new byte[]{1, 2, 3, 4});
        image.setLastModified(2000L);
        String second = ThumbnailCacheKey.forIllustration("project", image);
        String otherProject = ThumbnailCacheKey.forIllustration("other", image);

        assertFalse(first.equals(second));
        assertFalse(second.equals(otherProject));
        assertEquals(64, second.length());
    }
}
