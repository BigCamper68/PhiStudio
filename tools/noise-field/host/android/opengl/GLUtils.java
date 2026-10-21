package android.opengl;
import android.graphics.Bitmap;import java.nio.ByteBuffer;
public final class GLUtils {
 public static void texImage2D(int target,int level,Bitmap image,int border){int w=image.getWidth(),h=image.getHeight();ByteBuffer rgba=ByteBuffer.allocateDirect(w*h*4);for(int y=0;y<h;y++)for(int x=0;x<w;x++){int p=image.image.getRGB(x,y),a=p>>>24;rgba.put((byte)Math.round(((p>>>16)&255)*a/255f)).put((byte)Math.round(((p>>>8)&255)*a/255f)).put((byte)Math.round((p&255)*a/255f)).put((byte)a);}rgba.flip();GLES20.glTexImage2D(target,level,GLES20.GL_RGBA,w,h,border,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,rgba);}
}
