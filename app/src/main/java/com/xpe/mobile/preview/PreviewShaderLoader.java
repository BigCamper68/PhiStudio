package com.xpe.mobile.preview;

import android.content.res.AssetManager;
import com.xpe.mobile.packageio.ChartPackage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** Reads preserved package resources without changing the chart/editor model. */
public final class PreviewShaderLoader {
    private static final Map<String, String> PRESETS = new HashMap<>();
    static {
        PRESETS.put("chromatic", "chromatic"); PRESETS.put("circleBlur", "circle_blur");
        PRESETS.put("fisheye", "fisheye"); PRESETS.put("glitch", "glitch");
        PRESETS.put("grayscale", "grayscale"); PRESETS.put("noise", "noise");
        PRESETS.put("pixel", "pixel"); PRESETS.put("radialBlur", "radial_blur");
        PRESETS.put("shockwave", "shockwave"); PRESETS.put("vignette", "vignette");
    }
    private PreviewShaderLoader() { }
    public static ShaderTimeline load(ChartPackage chartPackage, AssetManager assets) throws IOException {
        if (chartPackage == null) return ShaderTimeline.EMPTY;
        File workspace = chartPackage.getWorkspace();
        File extra = PreviewTexturePath.resolveInside(workspace, "extra.json");
        if (extra == null || !extra.isFile()) return ShaderTimeline.EMPTY;
        return ShaderTimeline.parse(utf8(Files.readAllBytes(extra.toPath())), name -> source(workspace, assets, name));
    }
    public static String source(File workspace, AssetManager assets, String name) throws IOException {
        if (name.startsWith("/")) {
            File shader = PreviewTexturePath.resolveInside(workspace, name.substring(1));
            if (shader == null || !shader.isFile()) throw new IOException("Shader file unavailable: " + name);
            return utf8(Files.readAllBytes(shader.toPath()));
        }
        String preset = PRESETS.get(name);
        if (preset == null) throw new IOException("Unknown shader: " + name);
        try (InputStream stream = assets.open("shaders/" + preset + ".glsl")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int read;
            while ((read = stream.read(buffer)) != -1) bytes.write(buffer, 0, read);
            return utf8(bytes.toByteArray());
        }
    }

    private static String utf8(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.startsWith("\ufeff") ? text.substring(1) : text;
    }
}
