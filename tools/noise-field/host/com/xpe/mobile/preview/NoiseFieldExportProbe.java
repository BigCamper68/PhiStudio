package com.xpe.mobile.preview;

import com.xpe.mobile.model.*;
import com.xpe.mobile.packageio.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Runs the production package exporter and records geometry for an independent player probe. */
public final class NoiseFieldExportProbe {
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[1]);Files.createDirectories(output);
        int sampleRate=args.length>2?Integer.parseInt(args[2]):240;
        if(sampleRate<1||sampleRate>48000)throw new IllegalArgumentException("Sample rate must be 1–48000 Hz");
        ChartPackage pack;try(InputStream input=Files.newInputStream(Path.of(args[0]))) {
            pack=new PackageImporter().importPackage(input,output.resolve("workspace").toFile(),"MULTIPHEX (2).zip");
        }
        ChartDocument chart=pack.getChart();String before=chart.toJsonString();
        for(boolean colors:new boolean[]{false,true})try(OutputStream file=Files.newOutputStream(output.resolve(colors?"MULTIPHEX-Phira-Pro-colors.zip":"MULTIPHEX-native-sorted.zip"))){
            new PackageExporter().exportPackage(pack,chart,file,1.5,colors);
        }
        if(!before.equals(chart.toJsonString()))throw new AssertionError("Export mutated the source chart");
        JSONArray samples=new JSONArray();
        for(int i=0;i<chart.noiseFields.size();i++) {
            NoiseField field=chart.noiseFields.get(i);TreeSet<Double> times=new TreeSet<>();
            for(double t=field.appear+.0001;t<field.disappear;t+=1.0/sampleRate)times.add(t);
            for(double t:new double[]{field.appear,field.enable,field.disable,field.disappear}) {
                times.add(t);times.add(t+.00001);times.add(t-.00001);
            }
            for(double t:times) {
                var zone=NoiseFieldEvaluator.transform(field,(float)t,1.5f);
                if(zone==null)continue;
                JSONArray corners=new JSONArray();for(float c:zone.corners())corners.put(c);
                samples.put(new JSONObject().put("field",i).put("seconds",t).put("corners",corners)
                    .put("color",field.color&0xffffff).put("visible",t>=field.appear&&t<field.disappear)
                    .put("active",t>=field.enable&&t<field.disable)
                    .put("center",new JSONArray().put(zone.x).put(zone.y))
                    .put("size",new JSONArray().put(zone.width).put(zone.height)).put("rotation",zone.rotation));
            }
        }
        JSONObject report=new JSONObject().put("chartPath",pack.getChartPath()).put("originalLines",chart.judgeLines.size())
                .put("fields",chart.noiseFields.size()).put("notes",chart.totalNotes()).put("sampleRate",sampleRate).put("samples",samples);
        Files.writeString(output.resolve("native-reference.json"),report.toString(2));
        System.out.println("Exported "+chart.noiseFields.size()+" fields; "+samples.length()+" geometry samples; source unchanged");
    }
}
