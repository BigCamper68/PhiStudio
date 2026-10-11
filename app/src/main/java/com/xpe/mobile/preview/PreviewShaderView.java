package com.xpe.mobile.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.TextureView;
import android.view.View;

import com.xpe.mobile.config.EditorSettings;
import java.util.function.Consumer;

/** Preview GPU surface. Hardware Canvas layers never pass through full-frame CPU bitmaps. */
public final class PreviewShaderView extends TextureView implements TextureView.SurfaceTextureListener {
    private Worker worker;
    private Consumer<String> errorListener;
    private Runnable frameListener;
    private boolean failed;

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    public PreviewShaderView(Context context) {
        super(context);
        setOpaque(true);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setSurfaceTextureListener(this);
        setVisibility(GONE);
    }
    public void setErrorListener(Consumer<String> listener) { errorListener = listener; }
    public void setFrameListener(Runnable listener) { frameListener = listener; }
    public void resetFailure() {
        failed = false;
        Worker current = worker;
        if (current != null) current.reset();
    }

    /** UI thread. A single in-flight pair keeps gameplay, particles and shader time together. */
    public boolean submit(PreviewRenderer renderer, RenderScene scene, RectF viewport, RectF area,
                          Bitmap background, EditorSettings settings, int line, double dim,
                          ShaderTimeline timeline, double seconds) {
        if (failed || timeline.effects.isEmpty()) { hidePreview(); return false; }
        setVisibility(VISIBLE);
        Worker current = worker;
        if (current == null) return false;
        int width = Math.max(1, (int) Math.ceil(area.width()));
        int height = Math.max(1, (int) Math.ceil(area.height()));
        int acquired = current.acquire(width, height);
        if (acquired < 0) return false;
        if (acquired == 0) return true; // GPU busy: keep its frame instead of queuing latency.
        try {
            Canvas gameplay = null, overlay = null;
            int gameSave = 0, overlaySave = 0;
            try {
                gameplay = current.gameplay.lock(true);
                gameSave = gameplay.save();
                overlay = current.overlay.lock(false);
                overlaySave = overlay.save();
                gameplay.translate(-area.left, -area.top);
                overlay.translate(-area.left, -area.top);
                renderer.drawShaderLayers(gameplay, overlay, scene, viewport, area, background, settings, line, dim);
            } finally {
                try {
                    if (overlay != null) {
                        if (overlaySave != 0) overlay.restoreToCount(overlaySave);
                        current.overlay.post(overlay);
                    }
                } finally {
                    if (gameplay != null) {
                        if (gameSave != 0) gameplay.restoreToCount(gameSave);
                        current.gameplay.post(gameplay);
                    }
                }
            }
            current.enqueue(new Frame(timeline, seconds, viewport, area, getWidth(), getHeight()));
            return true;
        } catch (RuntimeException ex) {
            current.fail(); reportFailure(current, ex);
            return false;
        }
    }

    public void hidePreview() {
        setVisibility(GONE);
        Worker current = worker;
        if (current != null) current.discardPending();
    }
    private void requestFrame(Worker current) {
        post(() -> { if (worker == current && frameListener != null) frameListener.run(); });
    }
    private void reportFailure(Worker current, RuntimeException exception) {
        Log.e("PhiStudioShaders", "Preview shader renderer failed", exception);
        post(() -> {
            if (worker != current) return;
            failed = true; hidePreview();
            if (errorListener != null) errorListener.accept(exception.getMessage());
            if (frameListener != null) frameListener.run();
        });
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        Worker next = new Worker(texture, width, height); worker = next;
        next.start();
    }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        Worker previous = worker; worker = null;
        if (previous != null) previous.shutdown(); else texture.release();
        // The GL worker releases SurfaceTexture after destroying its EGL window surface.
        return false;
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        Worker current = worker;
        if (current != null) current.resizeTo(width, height);
        if (frameListener != null) frameListener.run();
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
    @Override protected void onDetachedFromWindow() {
        hidePreview(); super.onDetachedFromWindow();
    }

    private final class Worker {
        final SurfaceTexture texture;
        final HandlerThread thread = new HandlerThread("PhiStudioShaders");
        Handler handler;
        ShaderEglSession egl;
        ShaderGlRenderer gl;
        ShaderCanvasLayer gameplay, overlay;
        int width, height, requestedWidth, requestedHeight, generation;
        boolean ready, stopped, inFlight, gameplayAvailable, overlayAvailable, cancelled, refreshRequested;
        Frame pending;
        Worker(SurfaceTexture texture, int width, int height) {
            this.texture = texture; requestedWidth = width; requestedHeight = height;
        }
        void start() {
            thread.start(); handler = new Handler(thread.getLooper());
            handler.post(() -> {
                try {
                    egl = new ShaderEglSession(texture, requestedWidth, requestedHeight);
                    createRenderer(); resizeLayers();
                } catch (RuntimeException ex) { fail(); reportFailure(this, ex); }
            });
        }
        private void createRenderer() {
            gl = new ShaderGlRenderer(message -> {
                Log.e("PhiStudioShaders", message);
                post(() -> { if (worker == this && errorListener != null) errorListener.accept(message); });
            });
        }
        synchronized int acquire(int w, int h) {
            if (stopped) return -1;
            if (w != requestedWidth || h != requestedHeight) resizeTo(w, h);
            if (!ready || width != w || height != h) return -1;
            if (inFlight) { refreshRequested = true; return 0; }
            inFlight = true; gameplayAvailable = false; overlayAvailable = false; cancelled = false;
            return 1;
        }
        synchronized void enqueue(Frame frame) {
            if (stopped) return;
            pending = frame; handler.post(this::drain);
        }
        void available(int callbackGeneration, boolean game) {
            synchronized (this) {
                if (stopped || callbackGeneration != generation || !inFlight) return;
                if (game) gameplayAvailable = true; else overlayAvailable = true;
            }
            drain();
        }
        /** GL handler: latch the pair only when both hardware producers have posted a buffer. */
        void drain() {
            Frame frame;
            boolean render;
            synchronized (this) {
                if (stopped || !ready || pending == null || !gameplayAvailable || !overlayAvailable) return;
                frame = pending; pending = null; render = !cancelled;
            }
            try {
                gameplay.update(); overlay.update();
                if (render) {
                    egl.resizeWindow(frame.outputWidth, frame.outputHeight);
                    gl.renderLayers(gameplay, overlay, width, height, frame.timeline, frame.seconds,
                            frame.viewport, frame.outputWidth, frame.outputHeight);
                    egl.swap();
                }
            } catch (RuntimeException ex) { fail(); reportFailure(this, ex); }
            finally {
                boolean refresh;
                synchronized (this) {
                    inFlight = false;
                    refresh = refreshRequested && !cancelled && ready && !stopped;
                    refreshRequested = false;
                }
                resizeLayers();
                if (refresh) requestFrame(this); // Preserve the final paused seek, too.
            }
        }
        synchronized void resizeTo(int w, int h) {
            if (stopped) return;
            requestedWidth = Math.max(1, w); requestedHeight = Math.max(1, h);
            handler.post(this::resizeLayers);
        }
        private synchronized void resizeLayers() {
            if (stopped || inFlight || gl == null || width == requestedWidth && height == requestedHeight) return;
            try {
                ready = false; closeLayers();
                width = requestedWidth; height = requestedHeight;
                int callbackGeneration = ++generation;
                gameplay = new ShaderCanvasLayer(width, height, handler, () -> available(callbackGeneration, true));
                overlay = new ShaderCanvasLayer(width, height, handler, () -> available(callbackGeneration, false));
                ready = true; requestFrame(this);
            } catch (RuntimeException ex) { fail(); reportFailure(this, ex); }
        }
        private void closeLayers() {
            if (gameplay != null) { gameplay.close(); gameplay = null; }
            if (overlay != null) { overlay.close(); overlay = null; }
        }
        synchronized void discardPending() {
            // Drain already posted buffers before accepting another pair, even when hidden.
            cancelled = true; refreshRequested = false;
        }
        synchronized void fail() { ready = false; }
        synchronized void reset() {
            discardPending();
            if (stopped) return;
            handler.post(() -> {
                try { synchronized (this) {
                    if (stopped) return;
                    if (ready) gl.prepare(ShaderTimeline.EMPTY);
                    else {
                        closeLayers();
                        if (gl != null) gl.close();
                        pending = null; inFlight = false; width = height = 0;
                        if (egl == null) egl = new ShaderEglSession(texture, requestedWidth, requestedHeight);
                        createRenderer(); resizeLayers();
                    }
                } } catch (RuntimeException ex) { fail(); reportFailure(this, ex); }
            });
        }
        synchronized void shutdown() {
            stopped = true; ready = false; pending = null;
            handler.post(() -> {
                try { closeLayers(); if (gl != null) gl.close(); }
                finally {
                    if (egl != null) egl.close();
                    texture.release(); thread.quitSafely();
                }
            });
        }
    }

    private static final class Frame {
        final RectF viewport;
        final ShaderTimeline timeline;
        final double seconds;
        final int outputWidth, outputHeight;
        Frame(ShaderTimeline timeline, double seconds, RectF viewport, RectF area, int width, int height) {
            this.timeline = timeline; this.seconds = seconds;
            this.viewport = new RectF(viewport); this.viewport.offset(-area.left, -area.top);
            outputWidth = width; outputHeight = height;
        }
    }
}
