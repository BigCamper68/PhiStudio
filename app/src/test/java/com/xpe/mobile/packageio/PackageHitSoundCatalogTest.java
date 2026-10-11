package com.xpe.mobile.packageio;

import com.xpe.mobile.model.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public final class PackageHitSoundCatalogTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private ChartPackage pack() throws Exception {
        ChartDocument chart = new ChartDocument();
        chart.song = "music.ogg"; chart.judgeLines.add(new JudgeLine());
        Note note = new Note(); note.hitSound = "sounds/custom.wav";
        chart.judgeLines.get(0).notes.add(note);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("chart.json"));
            zip.write(chart.toJsonString().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            for (String path : new String[]{"music.ogg","tap.mp3","sounds/custom.wav","sounds/other.mp3","data.bin"}) {
                zip.putNextEntry(new ZipEntry(path)); zip.write(new byte[]{1,2,3}); zip.closeEntry();
            }
        }
        return new PackageImporter().importPackage(new ByteArrayInputStream(bytes.toByteArray()),
                new File(temporary.getRoot(), "pack"), "chart.zip");
    }

    @Test public void choicesOfferReservedNamesAndPackageFilesButNotMusicOrNonAudio() throws Exception {
        ChartPackage pack = pack();
        assertEquals("music.ogg", pack.getAudioPath());
        assertEquals(List.of("tap.mp3","flick.mp3","drag.mp3","sounds/custom.wav","sounds/other.mp3"),
                PackageHitSoundCatalog.choices(pack));
        assertEquals(Set.of("sounds/custom.wav"), PackageHitSoundCatalog.referencedPaths(pack));
        assertTrue(PackageHitSoundCatalog.isAvailable(null, null));
        assertTrue(PackageHitSoundCatalog.isAvailable(null, "flick.mp3"));
        assertTrue(PackageHitSoundCatalog.isAvailable(pack, "sounds/custom.wav"));
        assertEquals(new File(pack.getWorkspace(), "sounds/custom.wav").getCanonicalFile(),
                PackageHitSoundCatalog.resolve(pack, "sounds\\custom.wav").getCanonicalFile());
        for (String path : new String[]{"../outside.wav","/outside.wav","C:\\outside.wav",
                "sounds/../custom.wav","missing.wav","data.bin","https://example.com/sound.mp3"}) {
            assertFalse(path, PackageHitSoundCatalog.isAvailable(pack, path));
        }
    }

    @Test public void canonicalSymlinkCannotEscapeWorkspace() throws Exception {
        ChartPackage pack = pack();
        File asset = new File(pack.getWorkspace(), "sounds/custom.wav");
        Files.delete(asset.toPath());
        File outside = temporary.newFile("outside.wav");
        Files.createSymbolicLink(asset.toPath(), outside.toPath());
        assertFalse(PackageHitSoundCatalog.isAvailable(pack, "sounds/custom.wav"));
    }

    @Test public void editedNoteSoundAndItsAssetSurviveSaveExportAndReimport() throws Exception {
        ChartPackage pack = pack();
        Note note = pack.getChart().judgeLines.get(0).notes.get(0);
        note.hitSound = "sounds/other.mp3";
        PackageWorkspaceWriter.writeChart(pack, pack.getChart());
        ChartPackage reopened = new PackageWorkspaceLoader().load(pack.getWorkspace(), "chart.zip");
        assertEquals(note.hitSound, reopened.getChart().judgeLines.get(0).notes.get(0).hitSound);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new PackageExporter().exportPackage(pack, pack.getChart(), output);
        ChartPackage exported = new PackageImporter().importPackage(
                new ByteArrayInputStream(output.toByteArray()), new File(temporary.getRoot(), "exported"), "chart.zip");
        assertEquals(note.hitSound, exported.getChart().judgeLines.get(0).notes.get(0).hitSound);
        assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(
                PackageHitSoundCatalog.resolve(exported, "sounds/other.mp3").toPath()));
    }

    @Test public void actualAconsmaExportPreservesItsThreeSoundFilesAndEditedNote() throws Exception {
        String directory = System.getenv("PHISTUDIO_CHART_DIR");
        Assume.assumeNotNull(directory);
        File archive = new File(directory, "Aconsma.zip");
        Assume.assumeTrue("Aconsma fixture not supplied", archive.isFile());
        ChartPackage pack;
        try (InputStream input = new FileInputStream(archive)) {
            pack = new PackageImporter().importPackage(input,
                    new File(temporary.getRoot(), "aconsma"), "Aconsma.zip");
        }
        Note note = pack.getChart().judgeLines.stream().flatMap(line -> line.notes.stream())
                .filter(n -> n.hitSound != null).findFirst().orElseThrow();
        note.hitSound = "flick.mp3";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new PackageExporter().exportPackage(pack, pack.getChart(), output);
        ChartPackage exported = new PackageImporter().importPackage(new ByteArrayInputStream(output.toByteArray()),
                new File(temporary.getRoot(), "aconsma-exported"), "Aconsma.zip");
        assertEquals("flick.mp3", exported.getChart().judgeLines.stream().flatMap(line -> line.notes.stream())
                .filter(n -> n.hitSound != null).findFirst().orElseThrow().hitSound);
        assertEquals(43, exported.getChart().judgeLines.stream().flatMap(line -> line.notes.stream())
                .filter(n -> n.hitSound != null).count());
        for (String path : new String[]{"tap.mp3","flick.mp3","drag.mp3","1300906474114773.mp3"}) {
            assertArrayEquals(Files.readAllBytes(new File(pack.getWorkspace(), path).toPath()),
                    Files.readAllBytes(new File(exported.getWorkspace(), path).toPath()));
        }
    }
}
