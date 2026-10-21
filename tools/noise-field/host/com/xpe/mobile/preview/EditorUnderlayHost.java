package com.xpe.mobile.preview;

import android.content.res.AssetManager;
import android.graphics.RectF;
import android.opengl.GLES20;
import com.xpe.mobile.packageio.HostChart;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Executes the production compositor, including its actual samplerExternalOES path. */
public final class EditorUnderlayHost {
    public static void main(String[] args) throws Exception {
        GLES20.init();JSONArray results=new JSONArray();
        try {
            var chart=HostChart.load(args[1]);
            ShaderTimeline constant=ShaderTimeline.parse("{\"bpm\":60,\"effects\":[{\"shader\":\"probe\",\"start\":[0,0,1],\"end\":[200,0,1],\"global\":true}]}",
                    name->"precision mediump float;varying vec2 uv;void main(){gl_FragColor=vec4(0.8,0.2,0.4,1.0);}");
            for(int[] size:new int[][]{{480,270},{480,320},{600,270}}) {
                int w=size[0],h=size[1];
                try(ShaderCanvasLayer back=layer(w,h,20,60,100,255);
                    ShaderCanvasLayer game=layer(w,h,0,0,0,0);
                    ShaderCanvasLayer hud=layer(w,h,0,0,0,0);
                    ShaderGlRenderer gl=new ShaderGlRenderer(message->{throw new AssertionError(message);},new AssetManager(args[0]))) {
                    ByteBuffer pixels=ByteBuffer.allocateDirect(w*h*4);
                    for(float alpha:new float[]{1,128/255f}) {
                        gl.renderLayers(back,game,hud,w,h,constant,2,new RectF(0,0,w,h),w,h,NoiseFieldFrame.EMPTY,alpha);
                        pixels.clear();GLES20.glReadPixels(0,0,w,h,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);
                        int[] expected={Math.round(20*(1-alpha)+204*alpha),Math.round(60*(1-alpha)+51*alpha),Math.round(100*(1-alpha)+102*alpha)};
                        int max=0;for(int i=0;i<w*h;i++)for(int c=0;c<3;c++)max=Math.max(max,Math.abs((pixels.get(i*4+c)&255)-expected[c]));
                        if(max>1)throw new AssertionError("Grid opacity / shader order: "+max);
                        results.put(new JSONObject().put("width",w).put("height",h).put("opacity",alpha).put("maximum_channel_error",max));
                    }
                    // Real moving fields exercise external producers, both native passes and the fade.
                    for(double time:new double[]{.8,2.5,40,82,120,40,.8}) {
                        NoiseFieldFrame frame=NoiseFieldEvaluator.evaluate(chart.noiseFields,time,w/(double)h);
                        gl.renderLayers(back,game,hud,w,h,ShaderTimeline.EMPTY,time,new RectF(0,0,w,h),w,h,frame,128/255f);
                        if(GLES20.glGetError()!=0)throw new AssertionError("Compositor GL error");
                    }
                }
            }
        } finally { GLES20.shutdown(); }
        Files.writeString(Path.of(args[2]),new JSONObject().put("constant_shader_checks",results).put("real_field_frames",21)
                .put("scope","Production ShaderGlRenderer with real EGLImage-backed external textures on Mesa; Android HWUI delivery is not simulated.").toString(2)+"\n");
        System.out.println("Compositor checks passed: "+results.length()+" exact-color frames and 21 real-field frames");
    }
    private static ShaderCanvasLayer layer(int w,int h,int r,int g,int b,int a) {
        ByteBuffer data=ByteBuffer.allocateDirect(w*h*4);
        for(int i=0;i<w*h;i++)data.put((byte)r).put((byte)g).put((byte)b).put((byte)a);
        data.flip();return new ShaderCanvasLayer(w,h,data);
    }
}
