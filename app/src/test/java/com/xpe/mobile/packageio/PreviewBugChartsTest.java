package com.xpe.mobile.packageio;

import com.xpe.mobile.editor.EditHistory;
import com.xpe.mobile.editor.PropertyEditCommand;
import com.xpe.mobile.editor.PropertyValidator;
import com.xpe.mobile.model.*;
import com.xpe.mobile.preview.ChartEvaluator;
import com.xpe.mobile.preview.RenderScene;
import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class PreviewBugChartsTest {
    private static String fixture(String name) throws Exception {
        try (InputStream in = PreviewBugChartsTest.class.getResourceAsStream("/preview-bugs/" + name)) {
            assertNotNull(name, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test public void fallenLine20StaysHiddenBeforeFirstAlphaEventAndAfterSave() throws Exception {
        ChartDocument chart = ChartDocument.fromJson(fixture("fallen-line20.json"));
        for (int pass = 0; pass < 2; pass++) {
            for (double beat : new double[]{0, .25, .5, .999, 1, 1.5, 0}) {
                assertEquals("beat=" + beat, 0,
                        ChartEvaluator.evaluate(chart, beat, false).lines.get(0).alpha);
            }
            chart = ChartDocument.fromJson(chart.toJsonString());
        }
    }

    @Test public void sirensFlickSurvivesCancelledSpeedPlateauAndHitsAt24Quarter() throws Exception {
        ChartDocument chart = ChartDocument.fromJson(fixture("sirens-line11.json"));
        for (int pass = 0; pass < 2; pass++) {
            for (boolean modern : new boolean[]{false, true}) {
                for (double aspect : new double[]{1.5, 16.0 / 9, 21.0 / 9, 9.0 / 16}) {
                    for (int tick = 80; tick < 240; tick++) {
                        RenderScene.RenderLine line = ChartEvaluator.evaluate(chart, tick / 16.0,
                                false, -1, modern, aspect).lines.get(0);
                        assertEquals("beat=" + tick / 16.0, 1, line.notes.size());
                        assertEquals(NoteType.FLICK, line.notes.get(0).type);
                        assertEquals(0, line.notes.get(0).startDistance, 1e-6);
                    }
                    assertEquals(1, ChartEvaluator.evaluate(chart, 24, false, -1, modern, aspect)
                            .lines.get(0).notes.size());
                    RenderScene.RenderLine hit = ChartEvaluator.evaluate(chart, 24.3, false,
                            -1, modern, aspect).lines.get(0);
                    assertTrue(hit.notes.isEmpty());
                    assertEquals(1, hit.hitEffects.size());
                    // Backward seek must recover the same animation.
                    assertEquals(1, ChartEvaluator.evaluate(chart, 5, false, -1, modern, aspect)
                            .lines.get(0).notes.size());
                }
            }
            chart = ChartDocument.fromJson(chart.toJsonString());
        }
    }

    @Test public void pecRotationUsesPrprEndOrderingWithoutOverlapsAndCanBeEdited() throws Exception {
        ChartDocument chart = LegacyPecConverter.convert(fixture("symy-line25.pec"));
        for (int pass = 0; pass < 2; pass++) {
            EventLayer layer = chart.judgeLines.get(25).layer(0);
            for (LineEvent event : layer.events(EventType.ROTATE)) {
                assertFalse("overlap at " + event.startTime, layer.overlaps(event, event));
            }
            for (double start : new double[]{50.666, 164.666}) {
                LineEvent event = layer.events(EventType.ROTATE).stream()
                        .filter(e -> Math.abs(e.startTime.toDouble() - start) < 1e-7)
                        .findFirst().orElseThrow();
                assertEquals(-90, event.start, 0);
                assertEquals(0, event.end, 0);
                assertEquals(start + 2.497, event.endTime.toDouble(), 1e-7);
                assertEquals(3, event.easingType);
                assertEquals(90, ChartEvaluator.evaluate(chart, start, false).lines.get(25).rotationDegrees, 1e-5);
                // prpr PEC easing 3 = sine-in: halfway is -90*cos(pi/4).
                assertEquals(90 / Math.sqrt(2), ChartEvaluator.evaluate(chart, start + 2.497 / 2,
                        false).lines.get(25).rotationDegrees, 1e-5);
                LineEvent edited = event.copy(); edited.start = -80;
                assertEquals(PropertyValidator.Error.NONE, PropertyValidator.validate(edited));
                assertFalse(layer.overlaps(edited, event));
                EditHistory.Command edit = PropertyEditCommand.event(layer, event, event.copy(), edited);
                edit.apply(); assertEquals(-80, event.start, 0);
                edit.revert(); assertEquals(-90, event.start, 0);
            }
            chart = ChartDocument.fromJson(chart.toJsonString());
        }
    }

    @Test public void fullSuppliedPackagesKeepFixesThroughImportSaveAndReload() throws Exception {
        String directory = System.getenv("PHISTUDIO_BUG_CHART_DIR");
        org.junit.Assume.assumeNotNull(directory);
        String[] names = {"Fallen Symphony.zip", "siren's eye.zip", "深渊梦呓.zip"};
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("preview-bug-packages-");
        try {
            for (int index = 0; index < names.length; index++) {
                java.io.File workspace = root.resolve("chart-" + index).toFile();
                ChartPackage imported;
                try (InputStream in = java.nio.file.Files.newInputStream(
                        java.nio.file.Path.of(directory, names[index]))) {
                    imported = new PackageImporter().importPackage(in, workspace, names[index]);
                }
                for (int pass = 0; pass < 2; pass++) {
                    ChartDocument chart = imported.getChart();
                    if (index == 0) {
                        for (double beat : new double[]{0, .5, .999, 1}) {
                            assertEquals(0, renderLine(chart, 20, beat).alpha);
                        }
                    } else if (index == 1) {
                        for (int tick = 80; tick < 240; tick++) {
                            assertTrue(renderLine(chart, 11, tick / 16.0).notes.stream()
                                    .anyMatch(n -> n.type == NoteType.FLICK && Math.abs(n.startDistance) < 1e-6));
                        }
                    } else {
                        for (double beat : new double[]{50.666, 164.666}) {
                            assertEquals(90, renderLine(chart, 25, beat).rotationDegrees, 1e-6);
                        }
                        // The fix applies to every PEC track, not just the two rotations.
                        for (JudgeLine line : chart.judgeLines) {
                            for (EventLayer layer : line.eventLayers) {
                                for (EventType type : EventType.values()) {
                                    for (LineEvent event : layer.events(type)) {
                                        assertFalse(layer.overlaps(event, event));
                                    }
                                }
                            }
                        }
                    }
                    PackageWorkspaceWriter.writeChart(imported, chart);
                    imported = new PackageWorkspaceLoader().load(workspace, names[index]);
                }
            }
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(root)) {
                for (java.nio.file.Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.delete(file);
                }
            }
        }
    }

    private static RenderScene.RenderLine renderLine(ChartDocument chart, int index, double beat) {
        return ChartEvaluator.evaluate(chart, beat, false).lines.stream()
                .filter(line -> line.sourceIndex == index).findFirst().orElseThrow();
    }

    @Test public void coverUsesPrprToleranceAndExcludesYOffset() throws Exception {
        ChartDocument chart = ChartDocument.fromJson(fixture("sirens-line11.json"));
        JudgeLine line = chart.judgeLines.get(0);
        line.layer(0).events(EventType.SPEED).clear();
        LineEvent speed = new LineEvent(); speed.type = EventType.SPEED;
        speed.start = speed.end = -0.001; line.layer(0).events(EventType.SPEED).add(speed);
        Note note = line.notes.get(0); note.startTime = new BeatTime(1, 0, 1);
        note.endTime = note.startTime; note.yOffset = -100;
        assertEquals(1, ChartEvaluator.evaluate(chart, 0, false).lines.get(0).notes.size());
        speed.start = speed.end = -1; note.yOffset = 100;
        chart.markEdited();
        assertEquals(0, ChartEvaluator.evaluate(chart, 0, false).lines.get(0).notes.size());
    }
}
