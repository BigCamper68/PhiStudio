package com.xpe.mobile.preview;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.view.Surface;

/** Private EGL context, usable with TextureView or a pbuffer for device regression tests. */
public final class ShaderEglSession implements AutoCloseable {
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    private Surface window;
    private SurfaceTexture windowTexture;
    private EGLConfig config;
    private int windowWidth, windowHeight;
    public ShaderEglSession(SurfaceTexture texture, int width, int height) {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] versions = new int[2];
            require(EGL14.eglInitialize(display, versions, 0, versions, 1), "initialize");
            EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
            require(EGL14.eglChooseConfig(display, new int[]{EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, texture == null ? EGL14.EGL_PBUFFER_BIT : EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE}, 0, configs, 0, 1, count, 0)
                    && count[0] > 0, "choose config");
            config = configs[0];
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            require(context != EGL14.EGL_NO_CONTEXT, "create context");
            if (texture == null) surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[]{EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE}, 0);
            else {
                windowTexture = texture; windowWidth = width; windowHeight = height;
                window = new Surface(texture);
                surface = EGL14.eglCreateWindowSurface(display, configs[0], window, new int[]{EGL14.EGL_NONE}, 0);
            }
            require(surface != EGL14.EGL_NO_SURFACE, "create surface");
            require(EGL14.eglMakeCurrent(display, surface, surface, context), "make current");
        } catch (RuntimeException ex) { close(); throw ex; }
    }
    public void swap() { require(EGL14.eglSwapBuffers(display, surface), "swap buffers"); }
    /** Keep shaders/textures/context, but acquire a correctly sized back buffer before drawing. */
    public void resizeWindow(int width, int height) {
        if (window == null || windowWidth == width && windowHeight == height) return;
        windowTexture.setDefaultBufferSize(width, height);
        require(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT), "unbind resized surface");
        require(EGL14.eglDestroySurface(display, surface), "destroy resized surface");
        surface = EGL14.EGL_NO_SURFACE;
        surface = EGL14.eglCreateWindowSurface(display, config, window, new int[]{EGL14.EGL_NONE}, 0);
        require(surface != EGL14.EGL_NO_SURFACE, "resize surface");
        require(EGL14.eglMakeCurrent(display, surface, surface, context), "bind resized surface");
        windowWidth = width; windowHeight = height;
    }
    private static void require(boolean success, String action) {
        if (!success) throw new IllegalStateException("EGL " + action + ": 0x" + Integer.toHexString(EGL14.eglGetError()));
    }
    @Override public void close() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display); EGL14.eglReleaseThread();
        }
        if (window != null) window.release();
        window = null; windowTexture = null; config = null;
        display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT; surface = EGL14.EGL_NO_SURFACE;
    }
}
