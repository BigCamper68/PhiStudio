package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.preview.PhiraBackgroundMetrics;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Updates only backgroundDim in a Phira manifest, preserving unrelated text verbatim. */
final class BackgroundDimManifestWriter {
    private static final Pattern DIM = Pattern.compile(
            "(?im)^(\\ufeff?[ \\t]*backgroundDim[ \\t]*:[ \\t]*)([^\\r\\n]*)(\\r?$)");

    static String path(ChartPackage pack) {
        for (PackageManifest manifest : pack.getManifests()) {
            if (manifest.getKind() == PackageManifest.Kind.YAML) return manifest.getPath();
        }
        return "info.yml";
    }

    static String text(ChartPackage pack, ChartDocument chart) throws IOException {
        File file = PackageImporter.resolveInside(pack.getWorkspace(), path(pack));
        double dim = chart.getBackgroundDim();
        if (file.isFile()) {
            String original = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return update(original, dim);
        }
        // Do not change the package format for an unchanged/default dim.
        if (dim == ChartDocument.DEFAULT_BACKGROUND_DIM) return null;
        return "name: " + JSONObject.quote(chart.name) + "\n"
                + "chart: " + JSONObject.quote(pack.getChartPath()) + "\n"
                + asset("music", pack.getAudioPath())
                + asset("illustration", pack.getIllustrationPath())
                + "offset: " + java.math.BigDecimal.valueOf(pack.getManifestOffsetMs(), 3).toPlainString() + "\n"
                + "useRpe170Speed: " + pack.isUseRpe170Speed() + "\n"
                + "backgroundDim: " + Double.toString(dim) + "\n";
    }

    private static String asset(String key, String path) {
        return path == null ? "" : key + ": " + JSONObject.quote(path) + "\n";
    }

    static String update(String original, double dim) {
        Matcher matcher = DIM.matcher(original);
        if (!matcher.find()) {
            if (dim == ChartDocument.DEFAULT_BACKGROUND_DIM) return original;
            String newline = original.contains("\r\n") ? "\r\n" : "\n";
            return original + (original.isEmpty() || original.endsWith("\n") ? "" : newline)
                    + "backgroundDim: " + Double.toString(dim) + newline;
        }
        // Keep exact original bytes when the scalar already equals the requested value.
        String scalar = matcher.group(2);
        int comment = scalar.indexOf('#');
        String value = comment < 0 ? scalar : scalar.substring(0, comment).trim();
        double stored = PhiraBackgroundMetrics.chartDim(
                PackageManifest.parse("info.yml", PackageManifest.Kind.YAML, "backgroundDim: " + value)
                        .get("backgroundDim"));
        if (Double.compare(stored, dim) == 0) return original;
        StringBuffer result = new StringBuffer();
        do {
            scalar = matcher.group(2);
            comment = scalar.indexOf('#');
            String suffix = comment < 0 ? "" : " " + scalar.substring(comment);
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    matcher.group(1) + Double.toString(dim) + suffix + matcher.group(3)));
        } while (matcher.find());
        matcher.appendTail(result);
        return result.toString();
    }

    static void writeWorkspace(ChartPackage pack, ChartDocument chart) throws IOException {
        String text = text(pack, chart);
        if (text == null) return;
        File target = PackageImporter.resolveInside(pack.getWorkspace(), path(pack));
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (target.isFile() && java.util.Arrays.equals(bytes, Files.readAllBytes(target.toPath()))) return;
        File temporary = File.createTempFile(".xpe-dim-", ".tmp", target.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
                output.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            temporary.delete();
        }
    }
}
