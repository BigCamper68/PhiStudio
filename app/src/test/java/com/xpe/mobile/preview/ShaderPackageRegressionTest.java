package com.xpe.mobile.preview;

import org.junit.Test;
import org.junit.Assume;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.zip.ZipFile;
import static org.junit.Assert.*;

/** Real chart fixtures stay outside the distributable source package. */
public final class ShaderPackageRegressionTest {
    @Test public void suppliedChartsLoadEveryEffectAndCustomShader() throws Exception {
        String directory = System.getenv("PHISTUDIO_SHADER_CHART_DIR");
        Assume.assumeNotNull(directory);
        String[] names = {"★ Ascension To Heaven.zip", "ANTINEUTRINO WITCHCRAFT.zip", "Hello (BPM) 2025.zip"};
        int[] counts = {302,27,490}, globals = {137,16,454};
        for (int i = 0; i < names.length; i++) try (ZipFile zip = new ZipFile(new File(directory, names[i]))) {
            ShaderTimeline timeline = ShaderTimeline.parse(new String(zip.getInputStream(zip.getEntry("extra.json")).readAllBytes(), StandardCharsets.UTF_8), name -> {
                if (name.startsWith("/")) return new String(zip.getInputStream(zip.getEntry(name.substring(1))).readAllBytes(), StandardCharsets.UTF_8);
                String asset = name.equals("circleBlur") ? "circle_blur" : name.equals("radialBlur") ? "radial_blur" : name;
                return new String(Files.readAllBytes(new File("src/main/assets/shaders/" + asset + ".glsl").toPath()), StandardCharsets.UTF_8);
            });
            assertEquals(names[i], counts[i], timeline.effects.size());
            int global = 0;
            for (ShaderTimeline.Effect effect : timeline.effects) {
                if (effect.global) global++;
                assertTrue(effect.source.contains("void main"));
                for (Map.Entry<String, ShaderTimeline.Animation> uniform : effect.uniforms.entrySet()) {
                    for (double seconds : new double[]{effect.start, (effect.start + effect.end)/2, effect.end, effect.start}) {
                        for (float value : uniform.getValue().at(seconds)) assertTrue(names[i] + " " + effect.name + " " + uniform.getKey(), Float.isFinite(value));
                    }
                }
            }
            assertEquals(globals[i], global);
        }
    }
}
