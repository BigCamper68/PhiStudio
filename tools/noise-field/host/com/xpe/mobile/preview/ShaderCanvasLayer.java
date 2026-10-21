package com.xpe.mobile.preview;

import android.opengl.GLES20;
import java.nio.ByteBuffer;

/** Host producer: real EGLImage-backed external texture in place of Android HWUI. */
public final class ShaderCanvasLayer implements AutoCloseable {
    final int texture;
    final float[] transform={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
    private final int source;
    ShaderCanvasLayer(int width,int height,ByteBuffer pixels) {
        int[] id=new int[1];GLES20.glGenTextures(1,id,0);source=id[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,source);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,width,height,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);
        texture=GLES20.externalTexture(source);
    }
    @Override public void close(){GLES20.glDeleteTextures(2,new int[]{texture,source},0);}
}
