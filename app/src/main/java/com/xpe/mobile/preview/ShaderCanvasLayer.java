package com.xpe.mobile.preview;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.view.Surface;

/** Hardware Canvas producer and external GL consumer. The owning EGL thread creates/closes it. */
public final class ShaderCanvasLayer implements AutoCloseable {
    final int texture;
    final float[] transform = new float[16];
    private final SurfaceTexture consumer;
    private final Surface producer;

    public ShaderCanvasLayer(int width, int height, Handler handler, Runnable frameAvailable) {
        int[] names = new int[1];
        GLES20.glGenTextures(1, names, 0);
        texture = names[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        SurfaceTexture allocatedConsumer = null;
        Surface allocatedProducer = null;
        try {
            allocatedConsumer = new SurfaceTexture(texture);
            allocatedConsumer.setDefaultBufferSize(width, height);
            if (frameAvailable != null) allocatedConsumer.setOnFrameAvailableListener(ignored -> frameAvailable.run(), handler);
            allocatedProducer = new Surface(allocatedConsumer);
            consumer = allocatedConsumer;
            producer = allocatedProducer;
        } catch (RuntimeException ex) {
            if (allocatedProducer != null) allocatedProducer.release();
            if (allocatedConsumer != null) allocatedConsumer.release();
            GLES20.glDeleteTextures(1, names, 0);
            throw ex;
        }
    }

    /** UI thread; every buffer is completely redrawn, including transparent HUD pixels. */
    public Canvas lock(boolean opaque) {
        Canvas canvas = producer.lockHardwareCanvas();
        canvas.drawColor(opaque ? Color.BLACK : Color.TRANSPARENT, PorterDuff.Mode.SRC);
        return canvas;
    }
    public void post(Canvas canvas) { producer.unlockCanvasAndPost(canvas); }

    /** EGL owner thread only, after the producer's frame-available notification. */
    public void update() {
        consumer.updateTexImage();
        consumer.getTransformMatrix(transform);
    }
    @Override public void close() {
        consumer.setOnFrameAvailableListener(null);
        producer.release();
        consumer.release();
        GLES20.glDeleteTextures(1, new int[]{texture}, 0);
    }
}
