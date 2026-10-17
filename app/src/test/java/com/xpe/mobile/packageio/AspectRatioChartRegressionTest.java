package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.preview.ChartEvaluator;
import com.xpe.mobile.preview.PreviewCoordinates;
import com.xpe.mobile.preview.RenderScene;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real supplied fixtures; no Android Canvas/native libraries required. */
public class AspectRatioChartRegressionTest {
    private ChartDocument load(String archive, String entry) throws Exception {
        String directory = System.getenv("PHISTUDIO_CHART_DIR");
        Assume.assumeNotNull(directory);
        Assume.assumeTrue("Fixture not supplied: " + archive, new java.io.File(directory, archive).isFile());
        try (ZipFile zip = new ZipFile(directory + "/" + archive);
             var input = zip.getInputStream(zip.getEntry(entry))) {
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(json);
            return root.has("formatVersion") ? OfficialPhigrosConverter.convert(root)
                    : ChartDocument.fromJson(json);
        }
    }

    @Test public void oblivionHoldPairsKeepTheirAuthoredRelativeYAcrossAspects() throws Exception {
        ChartDocument chart = load("oblivionphim.pez", "oblivionphim.json");
        for (int pair = 0; pair < 4; pair++) {
            for (double lead : new double[]{.75, .5, .25, 0}) {
                RenderScene scene = ChartEvaluator.evaluate(chart, 152 + pair - lead, true);
                RenderScene.RenderLine a = line(scene, 1 + pair * 2);
                RenderScene.RenderLine b = line(scene, 2 + pair * 2);
                RenderScene.RenderNote na = firstHold(a), nb = firstHold(b);
                // These pairs include a 180-degree line: preserve the authored
                // separation, not an assumed common Y for every animation frame.
                int[] baseline = {2100,1400};
                for (int[] size : new int[][]{{1920,1080}, {2100,1400}, {2560,1080}}) {
                    assertEquals("pair " + pair + " lead " + lead,
                            (y(a,na,baseline,false)-y(b,nb,baseline,false))/baseline[1],
                            (y(a,na,size,false)-y(b,nb,size,false))/size[1], 1e-8);
                    assertEquals((y(a,na,baseline,true)-y(b,nb,baseline,true))/baseline[1],
                            (y(a,na,size,true)-y(b,nb,size,true))/size[1], 1e-8);
                }
            }
        }
    }

    @Test public void reportedSectionsMatchPrprLocalProjection() throws Exception {
        ChartDocument oblivion = load("oblivionphim.pez", "oblivionphim.json");
        ChartDocument algebra = load("Algebra.zip", "19919062.json");
        int checks = 0;
        for (int i = 0; i <= 40; i++) {
            checks += verify(oblivion, 508 + i * .25);
        }
        for (double beat : new double[]{80,82,96,97.5,99,112,114}) {
            for (double offset : new double[]{-.25,0,.25}) {
                RenderScene scene = ChartEvaluator.evaluate(algebra, beat + offset, true);
                for (int index : beat == 80 ? new int[]{31,41,51,61}
                        : beat == 82 ? new int[]{52,62,42,32} : new int[]{31,32}) {
                    assertNotNull(line(scene, index));
                }
                checks += verify(algebra, beat + offset);
            }
        }
        assertTrue(checks > 1000);
        System.out.println("Real-chart prpr projection comparisons: " + checks);
    }

    private int verify(ChartDocument chart, double beat) {
        int count = 0;
        for (int[] size : new int[][]{{1920,1080}, {2100,1400}, {2560,1080}, {1080,1920}}) {
            RenderScene scene = ChartEvaluator.evaluate(chart, beat, true, -1, false,
                    size[0] / (double) size[1]);
            for (RenderScene.RenderLine line : scene.lines) {
                for (RenderScene.RenderNote note : line.notes) {
                    double r = Math.toRadians(line.rotationDegrees);
                    double aspect = size[0] / (double)size[1];
                    double x = note.x * (note.isHold() ? 1 : 1
                            - Math.sin(Math.toRadians(line.inclineDegrees))*note.startDistance/360);
                    double y = note.startDistance * (note.above ? 1 : -1);
                    double referenceY = size[1]/2.0 - line.y/450*size[1]/2.0
                            - (Math.sin(r)*x/675 + Math.cos(r)*y/450/aspect)*size[0]/2.0;
                    assertEquals(referenceY, y(line, note, size, false), 1e-7);
                    double referenceX = size[0]/2.0 + line.x/675*size[0]/2.0
                            + (Math.cos(r)*x/675 - Math.sin(r)*y/450/aspect)*size[0]/2.0;
                    double actualX = size[0]/2.0 + line.x*PreviewCoordinates.scaleX(size[0])
                            + PreviewCoordinates.localX(x,y,line.rotationDegrees,
                            PreviewCoordinates.scaleX(size[0]),PreviewCoordinates.scaleY(size[1]));
                    assertEquals(referenceX, actualX, 1e-7);
                    count++;
                }
            }
        }
        return count;
    }

    private static double y(RenderScene.RenderLine line, RenderScene.RenderNote note,
                            int[] size, boolean tail) {
        double distance = tail ? note.endDistance : note.startDistance;
        double x = note.x * (note.isHold() ? 1 : 1
                - Math.sin(Math.toRadians(line.inclineDegrees))*distance/360);
        return size[1]/2.0 - line.y*PreviewCoordinates.scaleY(size[1])
                - PreviewCoordinates.localY(x,distance*(note.above ? 1 : -1),line.rotationDegrees,
                PreviewCoordinates.scaleX(size[0]),PreviewCoordinates.scaleY(size[1]));
    }

    private static RenderScene.RenderLine line(RenderScene scene, int index) {
        return scene.lines.stream().filter(l -> l.sourceIndex == index).findFirst().orElseThrow();
    }

    private static RenderScene.RenderNote firstHold(RenderScene.RenderLine line) {
        return line.notes.stream().filter(RenderScene.RenderNote::isHold).findFirst().orElseThrow();
    }
}
