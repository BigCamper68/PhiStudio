package com.xpe.mobile.shadereditor;

import static org.junit.Assert.*;

import com.xpe.mobile.packageio.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ShaderWorkspaceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private ChartPackage project() throws Exception {
        return new ProjectWorkspaceCreator().create(new File(temporary.getRoot(), "project"),
            new ProjectWorkspaceCreator.Spec(
                "shader", "Shader project", "", "", "", 120, "ogg", "png"),
            new ByteArrayInputStream(new byte[] {1}), new ByteArrayInputStream(new byte[] {2}));
    }
    @Test
    public void firstSaveAndImportAreExportedWithoutReopeningProject() throws Exception {
        ChartPackage pack = project();
        assertNull(ShaderWorkspace.read(pack));
        String source = "precision mediump float; void main(){gl_FragColor=vec4(1.0);}";
        ShaderWorkspace.write(pack, "shaders/new.glsl", source);
        pack.registerShaderResource("shaders/new.glsl");
        String extra = "{\"bpm\":120,\"effects\":[{\"shader\":\"/shaders/"
                       + "new.glsl\",\"start\":[0,0,1],\"end\":[4,0,1]}],\"future\":123}";
        ShaderWorkspace.save(pack, extra);
        File output = new File(temporary.getRoot(), "export.zip");
        try (OutputStream stream = new FileOutputStream(output)) {
            new PackageExporter().exportPackage(pack, pack.getChart(), stream);
        }
        try (ZipFile zip = new ZipFile(output)) {
            assertEquals(extra,
                new String(zip.getInputStream(zip.getEntry("extra.json")).readAllBytes(),
                    StandardCharsets.UTF_8));
            assertEquals(source,
                new String(zip.getInputStream(zip.getEntry("shaders/new.glsl")).readAllBytes(),
                    StandardCharsets.UTF_8));
            assertFalse(zip.stream().anyMatch(e -> e.getName().contains(".tmp")));
        }
    }
    @Test
    public void shaderFilesAndRawExtraSurviveDuplicateAndReopen() throws Exception {
        ChartPackage pack = project();
        ShaderWorkspace.save(pack, "{\"bpm\":120,\"effects\":[],\"future\":42}");
        ShaderWorkspace.write(pack, "shaders/test.glsl", "exact text\r\n");
        pack.registerShaderResource("shaders/test.glsl");
        File destination = new File(temporary.getRoot(), "duplicate");
        new PackageWorkspaceCopier().copy(pack.getWorkspace(), destination);
        ChartPackage reopened = new PackageWorkspaceLoader().load(destination, "duplicate");
        assertEquals(ShaderWorkspace.read(pack), ShaderWorkspace.read(reopened));
        assertEquals("exact text\r\n",
            new String(Files.readAllBytes(new File(destination, "shaders/test.glsl").toPath()),
                StandardCharsets.UTF_8));
        assertTrue(
            reopened.getEntries().stream().anyMatch(e -> e.getPath().equals("shaders/test.glsl")));
    }
    @Test
    public void replacementNeverDuplicatesExportEntriesOrLeavesStagingFiles() throws Exception {
        ChartPackage pack = project();
        ShaderWorkspace.save(pack, "one");
        ShaderWorkspace.save(pack, "two");
        assertEquals("two", ShaderWorkspace.read(pack));
        assertEquals(
            1, pack.getEntries().stream().filter(e -> e.getPath().equals("extra.json")).count());
        assertFalse(
            Files.walk(temporary.getRoot().toPath()).anyMatch(p -> p.toString().endsWith(".tmp")));
    }
    @Test
    public void pathEscapesAndMissingResourcesAreRejected() throws Exception {
        ChartPackage pack = project();
        try {
            ShaderWorkspace.write(pack, "../escape.glsl", "bad");
            fail();
        } catch (IOException expected) {
        }
        try {
            pack.registerShaderResource("shaders/../../escape.glsl");
            fail();
        } catch (IOException expected) {
        }
        try {
            pack.registerShaderResource("shaders/missing.glsl");
            fail();
        } catch (IOException expected) {
        }
        assertFalse(new File(temporary.getRoot(), "escape.glsl").exists());
    }
}
