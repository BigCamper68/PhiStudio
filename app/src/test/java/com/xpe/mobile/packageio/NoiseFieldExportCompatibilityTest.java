package com.xpe.mobile.packageio;

import com.xpe.mobile.model.*;
import com.xpe.mobile.noiseeditor.NoiseFieldEdits;
import com.xpe.mobile.preview.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.*;
import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;

public class NoiseFieldExportCompatibilityTest {
    private static JSONObject vector(double x, double y) throws Exception { return new JSONObject().put("x",x).put("y",y); }
    private static JSONObject key(String channel, double t, double value, String tag) throws Exception {
        JSONObject key = new JSONObject().put("time",t).put("anchor",vector(.5,.5)).put("tag",tag);
        if (channel.equals("rotateEvents")) return key.put("rotation",value).put("easeType",0);
        return key.put(channel.equals("moveEvents")?"endPosition":"scale",vector(value,1)).put("easeTypeX",0).put("easeTypeY",0);
    }
    @Test public void everySerializationSortsAllChannelsStablyWithoutMutatingRawIndicesOrPrecision() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{}");
        JSONObject raw = NoiseFieldEdits.create(0,10,false).toJson();
        for (String channel : List.of("moveEvents","scaleEvents","rotateEvents")) raw.put(channel,new JSONArray()
                .put(key(channel,8,1,"last")).put(key(channel,1.123456789012345,0,"first"))
                .put(key(channel,3,1,"same1")).put(key(channel,3,2,"same2")));
        NoiseField field = NoiseField.fromJson(raw); chart.noiseFields.add(field);
        String original = raw.toString(); String json = chart.toJsonString();
        assertSame(field,chart.noiseFields.get(0)); assertEquals(original,field.toJson().toString());
        for (String channel : List.of("moveEvents","scaleEvents","rotateEvents")) {
            JSONArray keys = new JSONObject(json).getJSONArray("blockAreaList").getJSONObject(0).getJSONArray(channel);
            assertEquals(1.123456789012345,keys.getJSONObject(0).getDouble("time"),0);
            assertEquals("first",keys.getJSONObject(0).getString("tag"));
            assertEquals("same1",keys.getJSONObject(1).getString("tag"));
            assertEquals("same2",keys.getJSONObject(2).getString("tag"));
            assertEquals("last",keys.getJSONObject(3).getString("tag"));
        }
        NoiseField edited = NoiseFieldEdits.withKey(field,NoiseFieldEdits.Channel.MOVE,0,key("moveEvents",.2,.8,"edited"));
        chart.noiseFields.set(0,edited);
        assertEquals(.2,new JSONObject(chart.toJsonString()).getJSONArray("blockAreaList").getJSONObject(0)
                .getJSONArray("moveEvents").getJSONObject(0).getDouble("time"),0);
        assertEquals("edited",edited.toJson().getJSONArray("moveEvents").getJSONObject(0).getString("tag"));
    }
    @Test public void compatibleColorsUsePlayerRgbLifecycleAndTextureProtocolAndReimportLosslessly() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ChartPackage pack = fixture.simple(); ChartDocument chart = pack.getChart();
            for (int color : new int[]{0xffffffff,0xff40cfff,NoiseField.DEFAULT_COLOR}) {
                JSONObject raw = NoiseFieldEdits.create(1,3,chart.noiseFields.size()==1).toJson();
                raw.put("enableTime",2).put("disableTime",3).put(NoiseField.COLOR,
                        new JSONArray().put((color>>16)&255).put((color>>8)&255).put(color&255));
                chart.noiseFields.add(NoiseField.fromJson(raw));
            }
            String original = chart.toJsonString(); List<NoiseField> identities = new ArrayList<>(chart.noiseFields);
            byte[] bytes = fixture.export(pack,1.5,true); Map<String,byte[]> files = unzip(bytes);
            JSONObject root = new JSONObject(new String(files.get(pack.getChartPath()),StandardCharsets.UTF_8));
            assertFalse(root.has("blockAreaList")); assertEquals(4,root.getJSONArray("judgeLineList").length());
            assertEquals(3,root.getJSONObject("phiStudioNoiseSource").getJSONArray("fields").length());
            for (int i=0;i<3;i++) {
                JSONObject marker = root.getJSONArray("judgeLineList").getJSONObject(1+i);
                assertTrue(marker.getString("Texture").endsWith("isSubtract"+(i==1?1:0)+".png"));
                byte[] png = files.get(marker.getString("Texture")); assertNotNull(png);
                try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(png))) {
                    assertEquals(0x89504e470d0a1a0aL,input.readLong()); input.skipBytes(8); assertEquals(1,input.readInt());assertEquals(1,input.readInt());
                }
                JSONArray rgb = marker.getJSONObject("extended").getJSONArray("colorEvents").getJSONObject(0).getJSONArray("start");
                assertEquals(chart.noiseFields.get(i).color & 0xffffff,(rgb.getInt(0)<<16)|(rgb.getInt(1)<<8)|rgb.getInt(2));
                JSONArray codes=marker.getJSONArray("eventLayers").getJSONObject(0).getJSONArray("speedEvents");
                for(int code=1;code<=4;code++)assertEquals(code,codes.getJSONObject(code-1).getInt("start"));
            }
            assertEquals(original,chart.toJsonString());for(int i=0;i<identities.size();i++)assertSame(identities.get(i),chart.noiseFields.get(i));
            ChartPackage reopened = fixture.importBytes(bytes);
            assertEquals(NoiseFieldRpeSource.hash(new JSONObject(original)),NoiseFieldRpeSource.hash(new JSONObject(reopened.getChart().toJsonString()))); assertTrue(reopened.getChart().noiseMarkerSizes.isEmpty());
            PackageManifest manifest=PackageManifest.parse("info.yml",PackageManifest.Kind.YAML,new String(files.get("info.yml"),StandardCharsets.UTF_8));
            assertEquals("1.5",manifest.get("aspectRatio"));assertEquals("true",manifest.get("forceAspectRatio"));
            assertArrayEquals(files.get("song.mp3"),new byte[]{1,2,3});
            assertEquals("# source\nname: Fixture\nchart: chart.json\nmusic: song.mp3\n",new String(Files.readAllBytes(pack.getWorkspace().toPath().resolve("info.yml")),StandardCharsets.UTF_8));
            // Editing generated RGB in another editor must not silently restore stale source fields.
            root.getJSONArray("judgeLineList").getJSONObject(1).getJSONObject("extended").getJSONArray("colorEvents")
                    .getJSONObject(0).put("end",new JSONArray().put(0).put(255).put(0));
            ChartDocument external = ChartDocument.fromParsedJson(root);
            assertTrue(external.noiseFields.isEmpty()); assertEquals(4,external.judgeLines.size());
        }
    }
    @Test public void exportRatioIsPerCopyAndNativeTargetKeepsColorMetadataAndNoGuideLines() throws Exception {
        try(Fixture fixture=new Fixture()){
            ChartPackage pack=fixture.simple();pack.getChart().noiseFields.add(NoiseField.fromJson(NoiseFieldEdits.create(0,2,false).toJson()
                    .put(NoiseField.COLOR,new JSONArray().put(255).put(255).put(255))));
            for(double aspect:new double[]{1.5,16.0/9,4.0/3,21.0/9}){
                Map<String,byte[]> files=unzip(fixture.export(pack,aspect,false));
                JSONObject root=new JSONObject(new String(files.get("chart.json"),StandardCharsets.UTF_8));
                assertEquals(1,root.getJSONArray("judgeLineList").length());assertEquals(1,root.getJSONArray("blockAreaList").length());
                assertFalse(root.has("phiStudioNoiseSource"));assertEquals(4,files.size());
                PackageManifest info=PackageManifest.parse("info.yml",PackageManifest.Kind.YAML,new String(files.get("info.yml"),StandardCharsets.UTF_8));
                assertEquals(aspect,Double.parseDouble(info.get("aspectRatio")),0);
            }
        }
    }
    @Test public void suppliedMultiphexFirstFieldSurvivesNativeExportAndBothWhiteFieldsMatchCompatibleGeometry() throws Exception {
        String path=System.getenv("PHISTUDIO_MULTIPHEX_CHART");Assume.assumeNotNull(path);
        try(Fixture fixture=new Fixture()){
            ChartPackage pack;try(InputStream input=new FileInputStream(path)){pack=new PackageImporter().importPackage(input,fixture.next(),"MULTIPHEX (2).zip");}
            ChartDocument chart=pack.getChart();assertEquals(2,chart.noiseFields.size());assertEquals(40,chart.totalNotes());
            assertEquals(0xffffffff,chart.noiseFields.get(0).color);
            Map<String,byte[]> nativeFiles=unzip(fixture.export(pack,1.5,false));
            JSONObject nativeRoot=new JSONObject(new String(nativeFiles.get(pack.getChartPath()),StandardCharsets.UTF_8));
            for(int f=0;f<2;f++) for(String channel:List.of("moveEvents","scaleEvents","rotateEvents")) {
                JSONArray keys=nativeRoot.getJSONArray("blockAreaList").getJSONObject(f).getJSONArray(channel);
                for(int i=1;i<keys.length();i++)assertTrue(keys.getJSONObject(i-1).getDouble("time")<=keys.getJSONObject(i).getDouble("time"));
            }
            JSONArray scale=nativeRoot.getJSONArray("blockAreaList").getJSONObject(0).getJSONArray("scaleEvents");
            assertEquals(0,scale.getJSONObject(0).getJSONObject("scale").getDouble("x"),0);
            byte[] compatible=fixture.export(pack,1.5,true);
            Map<String,byte[]> files=unzip(compatible);JSONObject raw=new JSONObject(new String(files.get(pack.getChartPath()),StandardCharsets.UTF_8));
            raw.remove("phiStudioNoiseSource");files.put(pack.getChartPath(),raw.toString().getBytes(StandardCharsets.UTF_8));
            ChartPackage external=fixture.importBytes(zip(files));ChartDocument markers=external.getChart();
            assertEquals(1,markers.noiseMarkerSizes.size());assertTrue(markers.noiseFields.isEmpty());
            for(double t=4.27;t<5.79;t+=1.0/240) {
                var expected=NoiseFieldEvaluator.evaluate(chart.noiseFields,t,1.5);
                var actual=ChartEvaluator.evaluateAtSeconds(markers,t,false,-1,false,1.5,null).noiseFields;
                assertEquals(expected.zones.size(),actual.zones.size());
                for(int i=0;i<expected.zones.size();i++) {
                    var a=expected.zones.get(i);var b=actual.zones.get(i);assertEquals(0xffffffff,b.color);
                    assertEquals(a.active,b.active);assertEquals(a.subtract,b.subtract);
                    float[] ac=a.corners(),bc=b.corners();for(int c=0;c<8;c++) assertEquals("t="+t+" field="+i+" corner="+c,ac[c],bc[c],.01/675);
                }
            }
            // Assets are preserved exactly; generated guide PNGs are the only additions.
            for(var entry:nativeFiles.entrySet()) if(!entry.getKey().equals(pack.getChartPath())&&!entry.getKey().equals("info.yml"))
                assertArrayEquals(entry.getValue(),unzip(compatible).get(entry.getKey()));
            ChartPackage roundtrip=fixture.importBytes(compatible);assertEquals(chart.toJsonString(),roundtrip.getChart().toJsonString());
        }
    }
    @Test public void nonlinearRotationAndZeroScaleGrowthStayWithinOneHundredthOfACanvasPixel() throws Exception {
        ChartDocument chart=ChartDocument.fromJson("{\"BPMList\":[{\"bpm\":120,\"startTime\":[0,0,1]}],\"judgeLineList\":[{}]}");
        JSONObject raw=NoiseFieldEdits.create(4.25,1.6,false).toJson()
                .put("bottomLeftPercentage",vector(.65,2)).put("topRightPercentage",vector(1,-2))
                .put(NoiseField.COLOR,new JSONArray().put(255).put(255).put(255));
        raw.put("moveEvents",new JSONArray()
                .put(key("moveEvents",4.25,.825,"first").put("endPosition",vector(.825,0)).put("easeTypeX",10))
                .put(key("moveEvents",6.2,0,"last").put("endPosition",vector(0,0))));
        raw.put("scaleEvents",new JSONArray().put(key("scaleEvents",4.25,0,"first").put("easeTypeX",11))
                .put(key("scaleEvents",5.8,1,"last")));
        raw.put("rotateEvents",new JSONArray().put(key("rotateEvents",4.25,0,"first")
                .put("easeType",11).put("anchor",vector(.5,.2))).put(key("rotateEvents",5.8,-180,"last")));
        chart.noiseFields.add(NoiseField.fromJson(raw));
        NoiseFieldRpeExport exported=new NoiseFieldRpeExport(chart,"noise");
        JSONObject transport=new JSONObject(chart.toJsonString());transport.remove("blockAreaList");
        for(JSONObject line:exported.lines)transport.getJSONArray("judgeLineList").put(line);
        ChartDocument markers=ChartDocument.fromParsedJson(transport);
        markers.noiseMarkerSizes.put("noise/isSubtract0.png",new int[]{1,1});
        double maxError=0;
        for(double t=4.2501;t<5.8499;t+=1./2400){
            var expected=NoiseFieldEvaluator.evaluate(chart.noiseFields,t,1.5);
            var actual=ChartEvaluator.evaluateAtSeconds(markers,t,false,-1,false,1.5,null).noiseFields;
            assertEquals(expected.zones.size(),actual.zones.size());
            if(expected.zones.isEmpty())continue;
            float[] a=expected.zones.get(0).corners(),b=actual.zones.get(0).corners();
            for(int corner=0;corner<8;corner++)maxError=Math.max(maxError,Math.abs(a[corner]-b[corner])*675.);
        }
        assertTrue("Maximum canvas-pixel error: "+maxError,maxError<.01);
    }
    @Test public void coloredDesultoryExportKeepsAll160FieldsAndNotesWithoutExceedingAnimationBudget() throws Exception {
        String path=System.getenv("PHISTUDIO_NOISE_FIELD_CHART");Assume.assumeNotNull(path);
        try(Fixture fixture=new Fixture()){
            ChartPackage pack;try(InputStream input=new FileInputStream(path)){pack=new PackageImporter().importPackage(input,fixture.next(),"Desultory.pez.zip");}
            ChartDocument chart=pack.getChart();chart.noiseFields.set(0,NoiseField.fromJson(NoiseFieldEdits.copy(chart.noiseFields.get(0).toJson())
                    .put(NoiseField.COLOR,new JSONArray().put(64).put(207).put(255))));
            int originalLines=chart.judgeLines.size();
            byte[] compatible=fixture.export(pack,1.5,true);Map<String,byte[]> files=unzip(compatible);
            JSONObject raw=new JSONObject(new String(files.get(pack.getChartPath()),StandardCharsets.UTF_8));
            assertEquals(originalLines+160,raw.getJSONArray("judgeLineList").length());assertFalse(raw.has("blockAreaList"));
            ChartDocument restored=fixture.importBytes(compatible).getChart();assertEquals(160,restored.noiseFields.size());
            assertEquals(originalLines,restored.judgeLines.size());assertEquals(2026,restored.totalNotes());assertEquals(0xff40cfff,restored.noiseFields.get(0).color);
        }
    }
    private static class Fixture implements AutoCloseable {
        final Path root;int id;
        Fixture()throws Exception{root=Files.createTempDirectory("noise-export");}
        File next(){return root.resolve("p"+(id++)).toFile();}
        ChartPackage importBytes(byte[] bytes)throws Exception{return new PackageImporter().importPackage(new ByteArrayInputStream(bytes),next(),"fixture.zip");}
        byte[] export(ChartPackage pack,double aspect,boolean colors)throws Exception{
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();new PackageExporter().exportPackage(pack,pack.getChart(),bytes,aspect,colors);return bytes.toByteArray();
        }
        ChartPackage simple()throws Exception{
            return importBytes(zip(new LinkedHashMap<>(Map.of("chart.json","{\"META\":{\"name\":\"Fixture\"},\"BPMList\":[{\"bpm\":120,\"startTime\":[0,0,1]}],\"judgeLineList\":[{}]}".getBytes(StandardCharsets.UTF_8),
                    "song.mp3",new byte[]{1,2,3},"info.yml","# source\nname: Fixture\nchart: chart.json\nmusic: song.mp3\n".getBytes(StandardCharsets.UTF_8)))));
        }
        public void close()throws Exception{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }
    private static Map<String,byte[]> unzip(byte[] bytes)throws Exception{
        Map<String,byte[]> result=new LinkedHashMap<>();try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=zip.getNextEntry())!=null)result.put(entry.getName(),zip.readAllBytes());}return result;
    }
    private static byte[] zip(Map<String,byte[]> files)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){for(var e:files.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}}return bytes.toByteArray();
    }
}
