package com.xpe.mobile.video;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.EditorBackgroundDecoder;
import com.xpe.mobile.editor.NoteTextureSet;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.preview.*;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** The PhiStudio preview pipeline, with one awaited GPU frame per encoder timestamp. */
final class ExportFrameRenderer implements AutoCloseable {
    private ShaderEglSession egl;
    private ShaderGlRenderer gl;
    private ShaderCanvasLayer gameplay, overlay;
    private final HandlerThread callbacks = new HandlerThread("PhiStudioExportBuffers");
    private volatile CountDownLatch gameReady, overlayReady;
    private NoteTextureSet textures;
    private PreviewRenderer renderer;
    private EditorBackgroundDecoder.Background background;
    private Map<String, PreviewTextureDecoder.Texture> lineTextures = Collections.emptyMap();
    private final ChartDocument chart;
    private final ChartPackage pack;
    private final VideoExportOptions options;
    private final ShaderTimeline timeline;
    private final long trackDurationMs, packageOffsetMs;
    private final EditorSettings settings;
    private final RectF viewport;
    private final PreviewPlaybackState playback = new PreviewPlaybackState();

    ExportFrameRenderer(Context context, Surface encoder, ChartDocument chart, ChartPackage pack,
                        VideoExportOptions options, ShaderTimeline timeline, long trackDurationMs,
                        EditorSettings settings, long packageOffsetMs) throws IOException {
        this.chart = chart; this.pack = pack; this.options = options; this.timeline = timeline;
        this.trackDurationMs = trackDurationMs; this.packageOffsetMs = packageOffsetMs;
        this.settings = settings.copy();
        this.settings.markLineId = false;
        this.settings.highlightCurrentLine = false;
        this.settings.showFakeNoteOutline = false;
        viewport = new RectF(0, 0, options.width, options.height);
        try {
            ChartEvaluator.prepare(chart, pack != null && pack.isUseRpe170Speed());
            textures = new NoteTextureSet(context.getResources());
            renderer = new PreviewRenderer(textures, 1f, "AUTOPLAY", context.getAssets());
            renderer.setShowHud(options.hud);
            Set<String> names = new LinkedHashSet<>();
            for (JudgeLine line : chart.judgeLines) if (line.texture != null) names.add(line.texture);
            if (pack != null) {
                lineTextures = PreviewTextureDecoder.decode(pack.getWorkspace(), names);
                background = EditorBackgroundDecoder.decodeForPreview(pack.getIllustrationFile(), options.width, options.height);
            }
            renderer.setLineTextures(lineTextures);
            egl = new ShaderEglSession(encoder);
            gl = new ShaderGlRenderer(message -> { throw new IllegalStateException(message); });
            gl.prepare(timeline);
            callbacks.start();
            Handler handler = new Handler(callbacks.getLooper());
            gameplay = new ShaderCanvasLayer(options.width, options.height, handler,
                    () -> { CountDownLatch ready = gameReady; if (ready != null) ready.countDown(); });
            overlay = new ShaderCanvasLayer(options.width, options.height, handler,
                    () -> { CountDownLatch ready = overlayReady; if (ready != null) ready.countDown(); });
        } catch (RuntimeException | Error exception) { close(); throw exception; }
    }

    void render(long frame) throws IOException {
        Mp4Exporter.checkCancelled();
        long audioMs = options.startMs + Math.round(options.frameTimeUs(frame) / 1000.0);
        double beat = chart.audioMillisToBeat(audioMs, packageOffsetMs);
        RenderScene scene = ChartEvaluator.evaluate(chart, beat, settings.highlightSimultaneousNotes,
                trackDurationMs, pack != null && pack.isUseRpe170Speed(),
                options.width / (double) options.height, playback);
        gameReady = new CountDownLatch(1); overlayReady = new CountDownLatch(1);
        Canvas game = null, hud = null;
        try {
            game = gameplay.lock(true); hud = overlay.lock(false);
            renderer.drawShaderLayers(game, hud, scene, viewport, viewport,
                    background == null ? null : background.blurred, settings, -1, chart.getBackgroundDim());
        } finally {
            try { if (hud != null) overlay.post(hud); }
            finally { if (game != null) gameplay.post(game); }
        }
        await(gameReady); await(overlayReady);
        gameplay.update(); overlay.update();
        double chartSeconds = Math.max(0, (audioMs - (double) chart.offsetMs - packageOffsetMs) / 1000.0);
        gl.renderLayers(gameplay, overlay, options.width, options.height,
                timeline, chartSeconds, viewport, options.width, options.height);
        egl.presentAt(options.frameTimeUs(frame) * 1000);
    }

    private static void await(CountDownLatch ready) throws IOException {
        try {
            if (!ready.await(10, TimeUnit.SECONDS)) throw new IOException("GPU frame timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); Mp4Exporter.checkCancelled();
        }
    }

    @Override public void close() {
        // Continue releasing independent resources even if a driver fails during teardown.
        try { if (gameplay != null) gameplay.close(); }
        finally { try { if (overlay != null) overlay.close(); }
        finally { try { if (gl != null) gl.close(); }
        finally { try { if (egl != null) egl.close(); }
        finally {
            callbacks.quitSafely();
            if (renderer != null) renderer.release();
            PreviewTextureDecoder.recycleAll(lineTextures);
            if (background != null) background.recycle();
            if (textures != null) textures.recycle();
        }}}}
    }
}
