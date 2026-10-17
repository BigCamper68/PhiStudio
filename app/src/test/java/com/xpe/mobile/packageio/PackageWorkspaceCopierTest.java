package com.xpe.mobile.packageio;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class PackageWorkspaceCopierTest {
    private File root;

    @Before
    public void setUp() throws Exception {
        root = Files.createTempDirectory("xpe-workspace-copy-").toFile();
    }

    @After
    public void tearDown() {
        deleteRecursively(root);
    }

    @Test
    public void copiesNestedBinaryEntriesExactly() throws Exception {
        File source = directory("source/assets");
        byte[] expected = new byte[]{0, 1, 2, (byte) 0xfe, (byte) 0xff};
        Files.write(new File(source, "unknown.bin").toPath(), expected);
        File destination = new File(root, "copy");

        new PackageWorkspaceCopier().copy(new File(root, "source"), destination);

        assertArrayEquals(expected, Files.readAllBytes(
                new File(destination, "assets/unknown.bin").toPath()));
    }

    @Test
    public void enforcesEntryCountAndRemovesPartialCopy() throws Exception {
        File source = directory("entry-source");
        Files.write(new File(source, "one.bin").toPath(), new byte[]{1});
        Files.write(new File(source, "two.bin").toPath(), new byte[]{2});
        File destination = new File(root, "entry-copy");

        assertPackageError(PackageException.Code.ENTRY_COUNT_LIMIT,
                () -> new PackageWorkspaceCopier(new PackageLimits(1, 1024, 2048))
                        .copy(source, destination));

        assertFalse(destination.exists());
    }

    @Test
    public void enforcesPerFileSizeAndRemovesPartialCopy() throws Exception {
        File source = directory("size-source");
        Files.write(new File(source, "large.bin").toPath(), new byte[11]);
        File destination = new File(root, "size-copy");

        assertPackageError(PackageException.Code.ENTRY_SIZE_LIMIT,
                () -> new PackageWorkspaceCopier(new PackageLimits(10, 10, 100))
                        .copy(source, destination));

        assertFalse(destination.exists());
    }

    @Test
    public void rejectsExistingDestinationWithoutChangingIt() throws Exception {
        File source = directory("existing-source");
        Files.write(new File(source, "source.bin").toPath(), new byte[]{1});
        File destination = directory("existing-copy");
        File marker = new File(destination, "marker.bin");
        Files.write(marker.toPath(), new byte[]{9});

        try {
            new PackageWorkspaceCopier().copy(source, destination);
            fail("Expected existing destination failure");
        } catch (IOException exception) {
            assertTrue(exception.getMessage().contains("already exists"));
        }

        assertArrayEquals(new byte[]{9}, Files.readAllBytes(marker.toPath()));
    }

    private File directory(String relative) {
        File directory = new File(root, relative);
        assertTrue(directory.mkdirs());
        return directory;
    }

    private static void assertPackageError(PackageException.Code expected,
                                           ThrowingAction action) throws Exception {
        try {
            action.run();
            fail("Expected package error " + expected);
        } catch (PackageException exception) {
            assertEquals(expected, exception.getCode());
        }
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }
}
