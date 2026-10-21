package com.xpe.mobile.packageio;
import com.xpe.mobile.model.*;
import com.xpe.mobile.preview.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;
public class NoiseFieldImportTest {
    @Test public void officialImportAndRpeSavePreserveEveryNoiseFieldAndUnknownEventField()throws Exception{
        JSONObject area=NoiseFieldTest.area(false,0,1,2,3).put("future",new JSONObject().put("keep",true));
        JSONObject source=new JSONObject("{\"formatVersion\":3,\"offset\":0.25,\"judgeLineList\":[{\"bpm\":120,\"notesAbove\":[],\"notesBelow\":[]}]}");
        source.put("blockAreaList",new JSONArray().put(area));ChartDocument c=OfficialPhigrosConverter.convert(source);
        assertEquals(1,c.noiseFields.size());assertEquals(250,c.offsetMs);
        JSONObject saved=new JSONObject(c.toJsonString());assertEquals(area.toString(),saved.getJSONArray("blockAreaList").getJSONObject(0).toString());
        ChartDocument loaded=ChartDocument.fromParsedJson(saved);assertEquals(1,loaded.noiseFields.size());assertEquals(1,loaded.noiseFields.get(0).enable,0);
    }
    @Test public void malformedListOrUnknownAreaFormatReportsItsLocation()throws Exception {
        for(Object value:new Object[]{new JSONObject(),new JSONArray().put(new JSONObject().put("bottomLeft",new JSONObject()))}) {
            try{ChartDocument.fromParsedJson(new JSONObject().put("blockAreaList",value));fail();}
            catch(JSONException expected){assertTrue(expected.getMessage().contains("blockAreaList"));}
        }
        assertTrue(new JSONObject(ChartDocument.fromJson("{\"blockAreaList\":[]}").toJsonString()).has("blockAreaList"));
    }
    @Test public void suppliedDesultorySignalsSurvivesConversionAndAllTimelineSeeks()throws Exception {
        String archive=System.getenv("PHISTUDIO_NOISE_FIELD_CHART");Assume.assumeNotNull(archive);
        JSONObject source;try(ZipFile zip=new ZipFile(archive);var input=zip.getInputStream(zip.getEntry("2856450826125632.json"))){source=new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
        ChartDocument c=OfficialPhigrosConverter.convert(source);assertEquals(160,c.noiseFields.size());assertEquals(2026,c.totalNotes());
        JSONObject saved=new JSONObject(c.toJsonString());assertEquals(source.getJSONArray("blockAreaList").toString(),saved.getJSONArray("blockAreaList").toString());
        ChartDocument loaded=ChartDocument.fromParsedJson(saved);
        int visible=0,active=0,ready=0,subtract=0;
        for(double aspect:new double[]{1.5,16.0/9,21.0/9})for(int frame=0;frame<150*60;frame++){
            var state=NoiseFieldEvaluator.evaluate(loaded.noiseFields,frame/60.0,aspect);
            for(var zone:state.zones){visible++;if(zone.active)active++;if(zone.ready)ready++;if(zone.subtract)subtract++;for(float p:zone.corners())assertTrue(Float.isFinite(p));}
        }
        assertTrue(visible>10000);assertTrue(active>10000);assertTrue(ready>0);assertTrue(subtract>0);
        for(double t:new double[]{1,15,40,82,120,149,82,1}){
            var frame=ChartEvaluator.evaluateAtSeconds(loaded,t,false,-1,false,16.0/9,null);
            assertEquals(NoiseFieldEvaluator.evaluate(loaded.noiseFields,t,16.0/9).zones.size(),frame.noiseFields.zones.size());
        }
        java.nio.file.Path temporary=java.nio.file.Files.createTempDirectory("noise-field-package");
        try {
            ChartPackage pack;
            try(var input=new FileInputStream(archive)) { pack=new PackageImporter().importPackage(input,temporary.resolve("first").toFile(),"Desultory Signals.pez"); }
            assertEquals(160,pack.getChart().noiseFields.size());
            assertTrue(pack.getAudioFile().isFile());
            ByteArrayOutputStream output=new ByteArrayOutputStream();new PackageExporter().exportPackage(pack,pack.getChart(),output);
            ChartPackage reopened=new PackageImporter().importPackage(new ByteArrayInputStream(output.toByteArray()),temporary.resolve("second").toFile(),"saved.pez");
            assertEquals(160,reopened.getChart().noiseFields.size());assertEquals(2026,reopened.getChart().totalNotes());
            assertEquals(source.getJSONArray("blockAreaList").toString(),new JSONObject(reopened.getChart().toJsonString()).getJSONArray("blockAreaList").toString());
        } finally {
            try(var paths=java.nio.file.Files.walk(temporary)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(path->{try{java.nio.file.Files.delete(path);}catch(IOException ex){throw new java.io.UncheckedIOException(ex);}});}
        }
    }
}
