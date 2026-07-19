package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;

import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class PackageWorkspaceWriter {
    private PackageWorkspaceWriter() {
    }

    public static void writeChart(ChartPackage chartPackage, ChartDocument chart) throws IOException {
        if (chartPackage == null || chart == null) {
            throw new IOException("A loaded project is required for workspace autosave");
        }
        File target = PackageImporter.resolveInside(
                chartPackage.getWorkspace(), chartPackage.getChartPath());
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("Chart workspace directory is missing");
        }
        File workspaceParent = chartPackage.getWorkspace().getParentFile();
        if (workspaceParent == null || !workspaceParent.isDirectory()) {
            throw new IOException("Project library directory is missing");
        }
        File temporary = File.createTempFile(".xpe-chart-", ".tmp", workspaceParent);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary);
                 Writer writer = new BufferedWriter(new OutputStreamWriter(
                         output, StandardCharsets.UTF_8), 32 * 1024)) {
                try {
                    chart.writeJson(writer);
                    writer.flush();
                } catch (JSONException exception) {
                    throw new IOException("Unable to serialize project chart", exception);
                }
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
