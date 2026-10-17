package com.xpe.mobile.preview;

import com.xpe.mobile.model.ChartDocument;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipFile;
import org.junit.Test;
import org.junit.Assume;
import static org.junit.Assert.assertTrue;

/** Optional real-chart benchmark: point PHISTUDIO_CHART_DIR at the supplied ZIPs. */
public class LargeChartBenchmarkTest {
    @Test public void suppliedCharts() throws Exception {
        String directory = System.getenv("PHISTUDIO_CHART_DIR");
        Assume.assumeNotNull(directory);
        for (String name : new String[]{"Parallel Universe Shifter", "ANTINEUTRINO WITCHCRAFT"}) {
            Assume.assumeTrue("Fixture not supplied: " + name,
                    new java.io.File(directory, name + ".zip").isFile());
            ChartDocument chart;
            try (ZipFile zip = new ZipFile(directory + "/" + name + ".zip")) {
                var entry = zip.stream().filter(e -> e.getName().endsWith(".json")
                        && !e.getName().endsWith("extra.json")).findFirst().orElseThrow();
                try (var input = zip.getInputStream(entry)) {
                    chart = ChartDocument.fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            ChartEvaluator.prepare(chart, false);
            for (int i = 0; i < 30; i++) ChartEvaluator.evaluate(chart, 552 + i / 60.0, true);
            double[] times = new double[180];
            long checksum = 0;
            for (int i = 0; i < times.length; i++) {
                double beat = i < 60 ? 100 + i / 30.0 : i < 120 ? 552 + (i - 60) / 2.0 : 594 + (i - 120) / 4.0;
                long start = System.nanoTime();
                RenderScene scene = ChartEvaluator.evaluate(chart, beat, true);
                times[i] = (System.nanoTime() - start) / 1e6;
                checksum += scene.hud.combo;
                for (var line : scene.lines) checksum += line.notes.size() + line.hitEffects.size();
            }
            Arrays.sort(times);
            System.out.println(name + ": median=" + times[90] + "ms p95=" + times[171]
                    + "ms checksum=" + checksum);
            assertTrue(checksum > 0);
        }
    }
}
