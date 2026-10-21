package com.xpe.mobile.preview;
import android.opengl.GLES20;import android.content.res.AssetManager;import android.graphics.RectF;
import com.xpe.mobile.packageio.HostChart;import com.xpe.mobile.model.*;
import java.nio.*;import java.nio.file.*;import java.awt.image.BufferedImage;import javax.imageio.ImageIO;import java.util.*;import org.json.*;
/** Executes the production Java compositor against Mesa GLES, never a mock GL. */
public final class NoiseFieldHost {
 public static void main(String[] args)throws Exception{
  GLES20.init();Path out=Path.of(args[2]);Files.createDirectories(out);ChartDocument chart=HostChart.load(args[1]);JSONArray report=new JSONArray();
  try(NoiseFieldGlRenderer renderer=new NoiseFieldGlRenderer(new AssetManager(args[0]))){
   for(int[] size:new int[][]{{480,270},{480,320},{600,270}}){int w=size[0],h=size[1];
    int[] texture=new int[1],fbo=new int[1];GLES20.glGenTextures(1,texture,0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture[0]);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glGenFramebuffers(1,fbo,0);GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbo[0]);GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,texture[0],0);
    for(double seconds:new double[]{0,.8,1.8,2.5,4,10,20,40,60,82,100,120,147,149,40,.8}){
     ByteBuffer pixels=ByteBuffer.allocateDirect(w*h*4);
     for(int y=0;y<h;y++)for(int x=0;x<w;x++)pixels.put((byte)(x*180/w)).put((byte)(y*150/h)).put((byte)(((x/20+y/20)&1)*90+20)).put((byte)255);pixels.flip();
     GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture[0]);GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,w,h,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);
     NoiseFieldFrame frame=NoiseFieldEvaluator.evaluate(chart.noiseFields,seconds,w/(double)h);
     if(frame.visible()){
      // One fixed held touch tests the original hover/SDF branch, too.
      frame=new NoiseFieldFrame(frame.seconds,frame.aspect,frame.zones,List.of(new NoiseFieldFrame.Touch(.2f,-.1f,1)));
      renderer.prepare(frame,w,h);renderer.disabled(fbo[0],new RectF(0,0,w,h),h);renderer.active(texture[0],fbo[0],new RectF(0,0,w,h),w,h);
     }
     GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbo[0]);GLES20.glFinish();int error=GLES20.glGetError();if(error!=0)throw new AssertionError("GL error "+error+" at "+seconds+" "+w+"x"+h);
     pixels.clear();GLES20.glReadPixels(0,0,w,h,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);
     BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<h;y++)for(int x=0;x<w;x++){int offset=(y*w+x)*4;image.setRGB(x,h-1-y,(255<<24)|((pixels.get(offset)&255)<<16)|((pixels.get(offset+1)&255)<<8)|(pixels.get(offset+2)&255));}
     String filename=w+"x"+h+"-"+String.format(Locale.ROOT,"%.3f",seconds)+".png";ImageIO.write(image,"png",out.resolve(filename).toFile());
     JSONArray zones=new JSONArray();for(var z:frame.zones)zones.put(new JSONObject().put("x",z.x).put("y",z.y).put("width",z.width).put("height",z.height).put("rotation",z.rotation).put("active",z.active).put("ready",z.ready).put("subtract",z.subtract));
     report.put(new JSONObject().put("file",filename).put("time",seconds).put("width",w).put("height",h).put("zones",zones));
    }
    GLES20.glDeleteTextures(1,texture,0);GLES20.glDeleteFramebuffers(1,fbo,0);
   }
  }finally{GLES20.shutdown();}
  Files.writeString(out.resolve("frames.json"),report.toString(2)+"\n");System.out.println("Rendered "+report.length()+" actual Java GLES frames");
 }
}
