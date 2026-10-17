package com.xpe.mobile.shadereditor;

import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.preview.PreviewTexturePath;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class ShaderWorkspace {
    private ShaderWorkspace() {}
    public static String read(ChartPackage pack) throws IOException {
        File file = PreviewTexturePath.resolveInside(pack.getWorkspace(), "extra.json");
        return file != null && file.isFile()
            ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
            : null;
    }
    public static void save(ChartPackage pack, String snapshot) throws IOException {
        write(pack, "extra.json", snapshot);
        pack.registerShaderResource("extra.json");
    }
    public static void write(ChartPackage pack, String path, String text) throws IOException {
        File target = PreviewTexturePath.resolveInside(pack.getWorkspace(), path);
        if (target == null)
            throw new IOException("Invalid shader path");
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Shader directory unavailable");
        File staging =
            File.createTempFile(".phistudio-fx-", ".tmp", pack.getWorkspace().getParentFile());
        try {
            try (FileOutputStream stream = new FileOutputStream(staging)) {
                stream.write(text.getBytes(StandardCharsets.UTF_8));
                stream.getFD().sync();
            }
            try {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            staging.delete();
        }
    }
}
