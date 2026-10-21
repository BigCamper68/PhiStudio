package com.xpe.mobile.app;

import android.net.Uri;
import com.xpe.mobile.R;
import com.xpe.mobile.editor.EditorBackgroundDecoder;
import com.xpe.mobile.i18n.UiErrors;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.preview.PreviewShaderLoader;
import com.xpe.mobile.preview.PreviewTextureDecoder;
import com.xpe.mobile.preview.ShaderTimeline;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** MainPreviewResources responsibilities extracted from MainActivity. */
final class MainPreviewResources {
    private final MainActivity delegateOwner;

    MainPreviewResources(MainActivity delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void loadEditorIllustration(ChartPackage chartPackage) {
        if (delegateOwner.shaderPackage != chartPackage) delegateOwner.mainShaderSession.clearShaderSession();
        int generation = ++delegateOwner.illustrationLoadGeneration;
        delegateOwner.editorView.setBackgroundIllustration(null);
        delegateOwner.editorView.setPreviewLineTextures(null);
        delegateOwner.editorView.setPreviewShaders(ShaderTimeline.EMPTY);
        File illustration = chartPackage == null ? null : chartPackage.getIllustrationFile();
        Set<String> textureNames = new LinkedHashSet<>();
        if (chartPackage != null && chartPackage.getChart() != null) {
            for (JudgeLine line : chartPackage.getChart().judgeLines) {
                if (line != null && line.texture != null) textureNames.add(line.texture);
            }
        }
        boolean hasIllustration = illustration != null && illustration.isFile();
        int targetWidth = Math.max(1, Math.round(
                delegateOwner.getResources().getDisplayMetrics().widthPixels * 0.70f));
        int targetHeight = Math.max(1, delegateOwner.getResources().getDisplayMetrics().heightPixels);
        try {
            delegateOwner.previewExecutor.execute(() -> {
                EditorBackgroundDecoder.Background background = hasIllustration ? EditorBackgroundDecoder.decodeForPreview(
                        illustration, targetWidth, targetHeight) : null;
                Map<String, PreviewTextureDecoder.Texture> lineTextures = chartPackage == null
                        ? Collections.emptyMap() : PreviewTextureDecoder.decode(
                        chartPackage.getWorkspace(), textureNames);
                ShaderTimeline loadedShaders;
                String shaderError;
                try {
                    loadedShaders = PreviewShaderLoader.load(chartPackage, delegateOwner.getAssets());
                    shaderError = null;
                } catch (IOException exception) {
                    loadedShaders = ShaderTimeline.EMPTY;
                    shaderError = UiErrors.describe(delegateOwner, exception);
                }
                ShaderTimeline shaders = loadedShaders;
                String error = shaderError;
                delegateOwner.mainHandler.post(() -> {
                    if (generation != delegateOwner.illustrationLoadGeneration
                            || delegateOwner.isFinishing() || delegateOwner.isDestroyed()) {
                        if (background != null) background.recycle();
                        PreviewTextureDecoder.recycleAll(lineTextures);
                        return;
                    }
                    delegateOwner.editorView.setBackgroundIllustration(background == null ? null : background.original,
                            background == null ? null : background.blurred);
                    delegateOwner.editorView.setPreviewLineTextures(lineTextures);
                    if (delegateOwner.shaderDocument != null && delegateOwner.shaderPackage == chartPackage) {
                        delegateOwner.mainShaderSession.scheduleShaderPreview();
                    } else delegateOwner.editorView.setPreviewShaders(shaders);
                    if (error != null) delegateOwner.showMessage(delegateOwner.getString(R.string.preview_shader_error, error));
                });
            });
        } catch (RuntimeException exception) {
            // The project remains usable without a decoded illustration.
        }
    }

    void clearEditorIllustration() {
        delegateOwner.mainShaderSession.clearShaderSession();
        delegateOwner.illustrationLoadGeneration++;
        if (delegateOwner.editorView != null) {
            delegateOwner.editorView.setBackgroundIllustration(null);
            delegateOwner.editorView.setPreviewLineTextures(null);
            delegateOwner.editorView.setPreviewShaders(ShaderTimeline.EMPTY);
        }
    }

    void loadAudio(Uri uri, boolean remember) throws IOException {
        if (delegateOwner.audioController == null) throw new IOException(delegateOwner.getString(R.string.audio_controller_unavailable));
        delegateOwner.audioController.load(uri);
        if (remember) {
            delegateOwner.getPreferences(MainActivity.MODE_PRIVATE).edit().putString("audio_uri", uri.toString()).apply();
        }
    }

    void restoreAudioReference() {
        String stored = delegateOwner.getPreferences(MainActivity.MODE_PRIVATE).getString("audio_uri", "");
        if (stored.isEmpty()) return;
        try {
            delegateOwner.mainPreviewResources.loadAudio(Uri.parse(stored), false);
        } catch (Exception ignored) {
            delegateOwner.getPreferences(MainActivity.MODE_PRIVATE).edit().remove("audio_uri").apply();
        }
    }

    void releaseAudio() {
        if (delegateOwner.audioController != null) delegateOwner.audioController.clearSource();
    }
}
