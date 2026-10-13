package com.xpe.mobile;

import android.content.Intent;
import android.net.Uri;
import com.xpe.mobile.packageio.AndroidPackageIo;
import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.preview.PreviewShaderLoader;
import com.xpe.mobile.preview.ShaderTimeline;
import com.xpe.mobile.shadereditor.ShaderDocument;
import com.xpe.mobile.shadereditor.ShaderWorkspace;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** MainShaderSession responsibilities extracted from MainActivity. */
final class MainShaderSession {
    private final MainActivity delegateOwner;

    MainShaderSession(MainActivity delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    boolean shadersDirty() {
        return delegateOwner.shaderDocument != null && delegateOwner.shaderPackage == delegateOwner.currentPackage && delegateOwner.shaderDocument.dirty();
    }

    void clearShaderSession() {
        if (delegateOwner.workbench != null) delegateOwner.workbench.clearShaderEditor();
        delegateOwner.shaderGeneration++;
        if (delegateOwner.mainHandler != null && delegateOwner.pendingShaderPreview != null) delegateOwner.mainHandler.removeCallbacks(delegateOwner.pendingShaderPreview);
        delegateOwner.pendingShaderPreview = null;
        delegateOwner.shaderDocument = null;
        delegateOwner.shaderEditor = null;
        delegateOwner.shaderPackage = null;
    }

    void openShaderEditor() {
        if (delegateOwner.currentPackage == null) {
            delegateOwner.showMessage(delegateOwner.getString(R.string.package_required));
            return;
        }
        try {
            if (delegateOwner.shaderPackage != delegateOwner.currentPackage || delegateOwner.shaderDocument == null) {
                delegateOwner.mainShaderSession.clearShaderSession();
                ChartPackage pack = delegateOwner.currentPackage;
                delegateOwner.shaderDocument = new ShaderDocument(ShaderWorkspace.read(pack), delegateOwner.editorView.getChart(),
                        name -> PreviewShaderLoader.source(pack.getWorkspace(), delegateOwner.getAssets(), name));
                delegateOwner.shaderPackage = pack;
                delegateOwner.shaderEditor = new ShaderEditorView(delegateOwner, delegateOwner.editorView, delegateOwner.shaderDocument, new ShaderEditorView.Host() {
                    public void changed() { delegateOwner.mainShaderSession.scheduleShaderPreview(); }
                    public void importShader() {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        delegateOwner.startActivityForResult(intent, MainActivity.OPEN_SHADER);
                    }
                    public void save() { delegateOwner.requestProjectSave(); }
                });
                delegateOwner.mainShaderSession.scheduleShaderPreview();
            }
            delegateOwner.workbench.showShaders(delegateOwner.shaderEditor);
        } catch (Exception exception) {
            delegateOwner.showMessage("Shaders: " + MainActivity.safeMessage(exception));
        }
    }

    void scheduleShaderPreview() {
        if (delegateOwner.shaderDocument == null || delegateOwner.previewExecutor == null || delegateOwner.previewExecutor.isShutdown()) return;
        final int generation = ++delegateOwner.shaderGeneration;
        final String snapshot = delegateOwner.shaderDocument.json();
        final Map<String,String> sources = delegateOwner.shaderDocument.sourceSnapshot();
        if (delegateOwner.pendingShaderPreview != null) delegateOwner.mainHandler.removeCallbacks(delegateOwner.pendingShaderPreview);
        delegateOwner.pendingShaderPreview = () -> delegateOwner.previewExecutor.execute(() -> {
            try {
                ShaderTimeline timeline = ShaderTimeline.parse(snapshot, name -> {
                    String source = sources.get(name);
                    if (source == null) throw new IOException("Shader unavailable: " + name);
                    return source;
                });
                delegateOwner.mainHandler.post(() -> {
                    if (generation != delegateOwner.shaderGeneration || delegateOwner.isDestroyed() || delegateOwner.isFinishing()) return;
                    delegateOwner.editorView.updatePreviewShaders(timeline);
                    if (delegateOwner.shaderEditor != null) delegateOwner.shaderEditor.invalidatePreview();
                });
            } catch (Exception exception) {
                delegateOwner.mainHandler.post(() -> {
                    if (generation == delegateOwner.shaderGeneration && delegateOwner.shaderEditor != null)
                        delegateOwner.shaderEditor.error(MainActivity.safeMessage(exception));
                });
            }
        });
        delegateOwner.mainHandler.postDelayed(delegateOwner.pendingShaderPreview, 75);
    }

    void importShader(Uri uri, Intent data) {
        if (delegateOwner.shaderDocument == null || delegateOwner.shaderPackage != delegateOwner.currentPackage) return;
        final ShaderDocument document = delegateOwner.shaderDocument;
        final ChartPackage pack = delegateOwner.shaderPackage;
        try {
            delegateOwner.takeReadPermission(uri, data);
            delegateOwner.previewExecutor.execute(() -> {
                try {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    try (InputStream stream = delegateOwner.getContentResolver().openInputStream(uri)) {
                        if (stream == null) throw new IOException("Cannot read GLSL");
                        byte[] buffer = new byte[8192];
                        int length;
                        while ((length = stream.read(buffer)) != -1) {
                            if (bytes.size() + length > 1024 * 1024) throw new IOException("GLSL exceeds 1 MB");
                            bytes.write(buffer, 0, length);
                        }
                    }
                    String raw = bytes.toString(StandardCharsets.UTF_8.name());
                    final String source = raw.startsWith("\ufeff") ? raw.substring(1) : raw;
                    if (!source.matches("(?s).*\\bvoid\\s+main\\s*\\(.*")) throw new IOException("GLSL requires void main()");
                    String filename = AndroidPackageIo.displayName(delegateOwner.getContentResolver(), uri);
                    String stem = (filename == null ? "import" : filename).replaceAll("[^\\p{L}\\p{N}._-]", "_");
                    if (stem.toLowerCase(Locale.ROOT).endsWith(".glsl")) stem = stem.substring(0, stem.length() - 5);
                    if (stem.isEmpty()) stem = "import";
                    if (stem.length() > 64) stem = stem.substring(0, 64);
                    final String path = "shaders/" + stem + "-" + UUID.randomUUID().toString().substring(0, 8) + ".glsl";
                    delegateOwner.mainHandler.post(() -> {
                        if (document != delegateOwner.shaderDocument || pack != delegateOwner.currentPackage || delegateOwner.isDestroyed()) return;
                        try {
                            ShaderWorkspace.write(pack, path, source);
                            pack.registerShaderResource(path);
                            document.registerSource("/" + path, source);
                            document.add("/" + path, delegateOwner.editorView.shaderEditorSeconds());
                            delegateOwner.shaderEditor.imported();
                        } catch (Exception exception) { delegateOwner.shaderEditor.error(MainActivity.safeMessage(exception)); }
                    });
                } catch (Exception exception) {
                    delegateOwner.mainHandler.post(() -> {
                        if (document == delegateOwner.shaderDocument && delegateOwner.shaderEditor != null)
                            delegateOwner.shaderEditor.error(MainActivity.safeMessage(exception));
                    });
                }
            });
        } catch (Exception exception) { delegateOwner.shaderEditor.error(MainActivity.safeMessage(exception)); }
    }
}
