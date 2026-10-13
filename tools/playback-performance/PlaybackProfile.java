package com.xpe.mobile.preview;
import com.xpe.mobile.model.*;
import java.util.*;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;
public class PlaybackProfile {
 public static void main(String[] args) throws Exception {
  ChartDocument c;
  try(ZipFile z=new ZipFile(args[0])) { var e=z.stream().filter(x->x.getName().endsWith(".json")&&!x.getName().endsWith("extra.json")).findFirst().orElseThrow();c=ChartDocument.fromJson(new String(z.getInputStream(e).readAllBytes(),StandardCharsets.UTF_8)); }
  long prep=System.nanoTime(); ChartEvaluator.prepare(c,false);System.out.printf(Locale.ROOT,"prepare %.1f ms; lines %d notes %d events %d%n",(System.nanoTime()-prep)/1e6,c.judgeLines.size(),c.totalNotes(),c.totalEvents());
  double[] positions={5,30,60,90,120,180,240,270,285,300,330,360,390,420};
  long checksum=0;
  for(double seconds:positions) {
   double[] times=new double[240];int maxNotes=0,maxLines=0;PreviewPlaybackState playback=new PreviewPlaybackState();
   for(int i=-40;i<times.length;i++) {
    double t=seconds+Math.max(0,i)/60.0;double beat=c.millisToBeat(Math.round(t*1000));long st=System.nanoTime();
    RenderScene scene=ChartEvaluator.evaluate(c,beat,true,429499,false,16.0/9,playback);
    if(i>=0) times[i]=(System.nanoTime()-st)/1e6;
    int notes=0,lines=0;for(var l:scene.lines){notes+=l.notes.size();if(l.alpha>0)lines++;checksum+=l.notes.size()+l.hitEffects.size();}maxNotes=Math.max(maxNotes,notes);maxLines=Math.max(maxLines,lines);
   }
   Arrays.sort(times);System.out.printf(Locale.ROOT,"t=%.0f p50=%.3fms p95=%.3fms max=%.3fms visibleLines=%d notes=%d%n",seconds,times[120],times[228],times[239],maxLines,maxNotes);
  }
  System.out.println("checksum "+checksum);
 }
}
