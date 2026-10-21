package com.xpe.mobile.preview;
import com.xpe.mobile.packageio.HostChart;import com.xpe.mobile.model.*;import java.util.*;import java.nio.file.*;import org.json.*;
public final class NoiseFieldGeometryProbe {
 public static void main(String[] args)throws Exception {
  ChartDocument c=HostChart.load(args[0]);TreeSet<Float> times=new TreeSet<>();
  times.add(0f);times.add(149f);
  for(NoiseField f:c.noiseFields){
   List<Double> keys=new ArrayList<>(List.of(f.appear,f.enable,f.disable,f.disappear));
   for(var track:List.of(f.move,f.scale,f.rotate))for(var key:track)keys.add(key.time);
   for(double key:keys){float t=(float)key;times.add(t);if(t>0)times.add(Math.max(0,t-.0001f));times.add(t+.0001f);}
  }
  Float previous=null;List<Float> sampled=new ArrayList<>(times);for(float t:times){if(previous!=null)sampled.add((previous+t)*.5f);previous=t;}
  JSONArray rows=new JSONArray();
  for(float aspect:new float[]{1.5f,16f/9,21f/9})for(float time:sampled){
   JSONArray zones=new JSONArray();for(int id=0;id<c.noiseFields.size();id++){
    var z=NoiseFieldEvaluator.sample(c.noiseFields.get(id),time,aspect);if(z==null)continue;
    zones.put(new JSONArray().put(id).put(z.x).put(z.y).put(z.width).put(z.height).put(z.rotation).put(z.active).put(z.ready).put(z.subtract).put(z.opacity));
   }
   rows.put(new JSONArray().put(aspect).put(time).put(zones));
  }
  Files.writeString(Path.of(args[1]),rows.toString());System.out.println("Geometry samples: "+rows.length());
 }
}
