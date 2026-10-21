package com.xpe.mobile.noiseeditor;

import android.graphics.Canvas;
import com.xpe.mobile.model.*;
import com.xpe.mobile.packageio.*;
import com.xpe.mobile.preview.NoiseFieldRpeSource;
import com.xpe.mobile.shadereditor.ShaderEditorViewTestSupport;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},qualifiers="w800dp-h360dp-land-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class NoiseFieldAndroidRoundTripTest extends ShaderEditorViewTestSupport {
    @Test public void issuedCheckpoint9MultiphexMustOpenInNoiseTimelineOnAndroid() throws Exception {
        String archive=System.getenv("PHISTUDIO_CHECKPOINT9_EXPORT");Assume.assumeNotNull(archive);
        try(ZipFile zip=new ZipFile(archive)){
            JSONObject root=new JSONObject(new String(zip.getInputStream(zip.getEntry("chart.json")).readAllBytes(),StandardCharsets.UTF_8));
            JSONObject source=root.getJSONObject("phiStudioNoiseSource");JSONArray generated=new JSONArray();
            for(int i=source.getInt("baseLineCount");i<root.getJSONArray("judgeLineList").length();i++)generated.put(root.getJSONArray("judgeLineList").get(i));
            System.out.println("Android JSON zero: "+new JSONObject("{\"x\":-0}").get("x").getClass()+"; source="+source.getString("lineHash")+" parsed="+NoiseFieldRpeSource.hash(generated));
        }
        Path temp=Files.createTempDirectory("android-noise-import");
        try{
            ChartPackage pack;try(InputStream input=new FileInputStream(archive)){pack=new PackageImporter().importPackage(input,temp.resolve("workspace").toFile(),"MULTIPHEX.zip");}
            assertEquals("Imported fields must remain editable, not only visible in the renderer",2,pack.getChart().noiseFields.size());
            editor.setChart(pack.getChart());workbench.switchTab(4);var noise=workbench.activeNoiseEditor();
            editor.shaderEditorSeek(5);layout(800,360);assertEquals(2,noise.document.count());
            noise.timeline.draw(new Canvas());
            var field=NoiseFieldTimeline.class.getDeclaredField("hitCount");field.setAccessible(true);
            assertTrue("Noise timeline must contain real editable blocks",field.getInt(noise.timeline)>0);
            assertEquals(0xffffffff,noise.document.field().color);
        }finally{try(var paths=Files.walk(temp)){for(Path p:paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }
    @Test public void ordinaryExportKeepsFieldsColorsTimelineUndoAndOriginalManifestOnAndroid()throws Exception{
        String archive=System.getenv("PHISTUDIO_MULTIPHEX_CHART");Assume.assumeNotNull(archive);
        Path temp=Files.createTempDirectory("android-noise-export");
        try{
            ChartPackage original;try(InputStream input=new FileInputStream(archive)){original=new PackageImporter().importPackage(input,temp.resolve("first").toFile(),"MULTIPHEX.zip");}
            String before=original.getChart().toJsonString();
            byte[] manifestBefore=Files.readAllBytes(original.getWorkspace().toPath().resolve("info.yml"));
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();new PackageExporter().exportPackage(original,original.getChart(),bytes);
            JSONObject exported=null;byte[] manifestAfter=null;
            try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){
                if(entry.getName().equals("chart.json"))exported=new JSONObject(new String(zip.readAllBytes(),StandardCharsets.UTF_8));
                else if(entry.getName().equals("info.yml"))manifestAfter=zip.readAllBytes();
            }}
            assertNotNull(exported);assertFalse(exported.has("blockAreaList"));
            String output=System.getenv("PHISTUDIO_ANDROID_EXPORT_OUTPUT");
            if(output!=null){Path directory=Path.of(output);Files.createDirectories(directory);
                Files.write(directory.resolve("MULTIPHEX-android"+android.os.Build.VERSION.SDK_INT+".zip"),bytes.toByteArray());}
            JSONObject source=exported.getJSONObject("phiStudioNoiseSource");assertEquals(2,source.getInt("version"));
            int base=source.getInt("baseLineCount");assertEquals(base+2,exported.getJSONArray("judgeLineList").length());
            for(int i=base;i<base+2;i++){
                JSONArray color=exported.getJSONArray("judgeLineList").getJSONObject(i).getJSONObject("extended").getJSONArray("colorEvents").getJSONObject(0).getJSONArray("start");
                assertEquals("[255,255,255]",color.toString());
            }
            PackageManifest oldInfo=PackageManifest.parse("info.yml",PackageManifest.Kind.YAML,new String(manifestBefore,StandardCharsets.UTF_8));
            PackageManifest newInfo=PackageManifest.parse("info.yml",PackageManifest.Kind.YAML,new String(manifestAfter,StandardCharsets.UTF_8));
            assertEquals(oldInfo.get("aspectRatio"),newInfo.get("aspectRatio"));assertEquals(oldInfo.get("forceAspectRatio"),newInfo.get("forceAspectRatio"));
            assertEquals(before,original.getChart().toJsonString());
            ChartPackage reopened=new PackageImporter().importPackage(new ByteArrayInputStream(bytes.toByteArray()),temp.resolve("second").toFile(),"export.zip");
            assertEquals(2,reopened.getChart().noiseFields.size());assertEquals(base,reopened.getChart().judgeLines.size());
            assertEquals(40,reopened.getChart().totalNotes());
            editor.setChart(reopened.getChart());workbench.switchTab(4);var noise=workbench.activeNoiseEditor();editor.shaderEditorSeek(5);layout(800,360);
            noise.run(noise.document::delete);assertEquals(1,noise.document.count());noise.undo();assertEquals(2,noise.document.count());
            noise.redo();assertEquals(1,noise.document.count());noise.undo();assertEquals(2,noise.document.count());
            PackageWorkspaceWriter.writeChart(reopened,reopened.getChart());
            ChartPackage loaded=new PackageWorkspaceLoader().load(reopened.getWorkspace(),"saved project");
            assertEquals(2,loaded.getChart().noiseFields.size());assertEquals(0xffffffff,loaded.getChart().noiseFields.get(0).color);
            assertArrayEquals(Files.readAllBytes(original.getAudioFile().toPath()),Files.readAllBytes(loaded.getAudioFile().toPath()));
        }finally{try(var paths=Files.walk(temp)){for(Path p:paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }
    @Test public void alreadySavedCheckpoint9ProjectRecoversWithoutDiscardingExternalEdits()throws Exception{
        String archive=System.getenv("PHISTUDIO_CHECKPOINT9_EXPORT");Assume.assumeNotNull(archive);
        JSONObject root;try(ZipFile zip=new ZipFile(archive)){root=new JSONObject(new String(zip.getInputStream(zip.getEntry("chart.json")).readAllBytes(),StandardCharsets.UTF_8));}
        JSONObject source=root.getJSONObject("phiStudioNoiseSource");root.remove("phiStudioNoiseSource");
        // The broken checkpoint 9 importer saved these as ordinary RPE line objects.
        JSONObject saved=new JSONObject(ChartDocument.fromParsedJson(root).toJsonString());saved.put("phiStudioNoiseSource",source);
        ChartDocument recovered=ChartDocument.fromJson(saved.toString());assertEquals(2,recovered.noiseFields.size());
        JSONObject changed=new JSONObject(saved.toString());int base=source.getInt("baseLineCount");
        changed.getJSONArray("judgeLineList").getJSONObject(base).getJSONObject("extended").getJSONArray("colorEvents").getJSONObject(0)
                .put("end",new JSONArray().put(0).put(255).put(0));
        ChartDocument edited=ChartDocument.fromJson(changed.toString());assertTrue(edited.noiseFields.isEmpty());
        assertEquals(base+2,edited.judgeLines.size());
        assertEquals(0x00ff00,edited.judgeLines.get(base).extended.colorEvents.get(0).endRgb);
    }
    @Test public void desktopVersion2ExportRemainsEditableOnAndroid()throws Exception{
        String archive=System.getenv("PHISTUDIO_DESKTOP_EXPORT");Assume.assumeNotNull(archive);
        Path temp=Files.createTempDirectory("desktop-to-android-noise");
        try{
            ChartPackage pack;try(InputStream input=new FileInputStream(archive)){pack=new PackageImporter().importPackage(input,temp.resolve("workspace").toFile(),"desktop export.zip");}
            assertEquals(2,pack.getChart().noiseFields.size());assertEquals(0xffffffff,pack.getChart().noiseFields.get(0).color);
            editor.setChart(pack.getChart());workbench.switchTab(4);var noise=workbench.activeNoiseEditor();
            editor.shaderEditorSeek(5);layout(800,360);assertEquals(2,noise.document.count());
            noise.timeline.draw(new Canvas());var hits=NoiseFieldTimeline.class.getDeclaredField("hitCount");hits.setAccessible(true);assertTrue(hits.getInt(noise.timeline)>0);
            JSONObject saved=new JSONObject(new String(Files.readAllBytes(pack.getWorkspace().toPath().resolve(pack.getChartPath())),StandardCharsets.UTF_8));
            assertTrue(saved.has("blockAreaList"));assertFalse(saved.has("phiStudioNoiseSource"));
        }finally{try(var paths=Files.walk(temp)){for(Path p:paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }
    @Test public void semanticFingerprintSurvivesNumberTypesEscapingAndKeyOrder()throws Exception{
        JSONObject value=new JSONObject().put("zero",-0.0).put("whole",1.0).put("fraction",1.23456789012345)
                .put("path","folder/isSubtract0.png").put("text","</script> \u2028 \u0080 \"/-0");
        JSONObject reparsed=new JSONObject(value.toString());assertEquals(NoiseFieldRpeSource.hash(value),NoiseFieldRpeSource.hash(reparsed));
        JSONObject other=new JSONObject().put("text",value.getString("text")).put("path",value.getString("path"))
                .put("fraction",value.getDouble("fraction")).put("whole",1).put("zero",0);
        assertEquals(NoiseFieldRpeSource.hash(value),NoiseFieldRpeSource.hash(other));
        other.put("fraction",1.23456789012346);assertNotEquals(NoiseFieldRpeSource.hash(value),NoiseFieldRpeSource.hash(other));
        String raw="{\"phiStudioNoiseSource\":{},\"literal\":\"/-0,]\\\" -0\",\"value\":-0}";
        assertEquals(new JSONObject(raw).getString("literal"),NoiseFieldRpeSource.parse(raw).getString("literal"));
    }
    @Test public void jsonWireRoundtripMustNotInvalidateSourceFingerprint()throws Exception{
        JSONObject value=new JSONObject().put("rotation",-0.0).put("path","phistudio-noise/isSubtract0.png");
        String serialized=value.toString();JSONObject loaded=new JSONObject(serialized);
        System.out.println("JSON codec="+JSONObject.class.getResource("JSONObject.class")+" signed zero="+serialized);
        assertEquals(NoiseFieldRpeSource.hash(value),NoiseFieldRpeSource.hash(loaded));
    }
}
