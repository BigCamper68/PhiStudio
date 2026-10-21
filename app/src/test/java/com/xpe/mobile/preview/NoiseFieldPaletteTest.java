package com.xpe.mobile.preview;

import com.xpe.mobile.model.NoiseField;
import com.xpe.mobile.noiseeditor.NoiseFieldEdits;
import java.nio.ByteBuffer;
import java.util.List;
import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;

public class NoiseFieldPaletteTest {
    private NoiseFieldFrame.Zone zone(float x,float width,int color,boolean active,boolean subtract) {
        return new NoiseFieldFrame.Zone(x,0,width,2,0,1,active,false,subtract,color);
    }
    private NoiseFieldFrame frame(NoiseFieldFrame.Zone... zones) { return new NoiseFieldFrame(1,1,List.of(zones),List.of()); }
    private int rgb(NoiseFieldPalette palette,int layer,int x,int y) {
        ByteBuffer bytes=palette.pixels();int index=(layer*32*32+y*32+x)*4;
        return 0xff000000|((bytes.get(index)&255)<<16)|((bytes.get(index+1)&255)<<8)|(bytes.get(index+2)&255);
    }
    @Test public void nativeChartsUseNoPaletteAndColorsDoNotChangeInputCoverage() {
        NoiseFieldPalette p=new NoiseFieldPalette();
        var nativeZone=zone(0,1,NoiseField.DEFAULT_COLOR,true,false);assertFalse(p.update(frame(nativeZone),32,32));
        var colored=frame(zone(0,1,0xff00ff00,true,false));assertTrue(p.update(colored,32,32));
        for(float x=-1;x<=1;x+=.02f)assertEquals(frame(nativeZone).blocks(x,0),colored.blocks(x,0));
    }
    @Test public void adjacentColorsAndInactiveFieldsStayIndependentAndCacheGeometry() {
        NoiseFieldPalette p=new NoiseFieldPalette();
        var f=frame(zone(-.5f,1,0xff00ff00,true,false),zone(.5f,1,0xff0000ff,true,false),zone(0,2,0xffffffff,false,false));
        p.update(f,32,32);assertEquals(0xff00ff00,rgb(p,0,0,16));assertEquals(0xff0000ff,rgb(p,0,31,16));assertEquals(0xffffffff,rgb(p,1,16,16));
        long revision=p.revision();p.update(new NoiseFieldFrame(5,1,f.zones,List.of()),32,32);assertEquals(revision,p.revision());
        p.update(f,40,30);assertTrue(p.revision()>revision);
    }
    @Test public void invertedWhiteFieldAndNormalRedHoleDoNotSeedRedAnywhere() {
        for(boolean active:new boolean[]{false,true}) {
            NoiseFieldPalette p=new NoiseFieldPalette();
            p.update(frame(zone(0,4,0xffffffff,active,true),zone(0,.8f,NoiseField.DEFAULT_COLOR,active,false)),32,32);
            for(int y=0;y<32;y++)for(int x=0;x<32;x++)assertEquals(0xffffffff,rgb(p,active?0:1,x,y));
        }
    }
    @Test public void canceledSubtractDoesNotTintTheSurvivingNormalRegion() {
        NoiseFieldPalette p=new NoiseFieldPalette();
        p.update(frame(zone(0,2,0xff00ff00,true,false),zone(0,.5f,0xffffffff,true,true)),32,32);
        assertEquals(0xff00ff00,rgb(p,0,16,16));assertEquals(0xff00ff00,rgb(p,0,0,16));
    }
    @Test public void rgbExtensionRoundTripsUnknownDataAndMalformedColorKeepsChartPlayable() throws Exception {
        var json=NoiseFieldEdits.copy(NoiseFieldEdits.create(0,3,false).toJson());
        json.put("futureColor",new JSONArray("[1,2,3]"));json.put(NoiseField.COLOR,new JSONArray("[4,120,255]"));
        NoiseField f=NoiseField.fromJson(json);assertEquals(0xff0478ff,f.color);
        NoiseField moved=NoiseFieldEdits.phase(f,0,-1);assertEquals(f.color,moved.color);assertEquals("[1,2,3]",moved.toJson().getJSONArray("futureColor").toString());
        for(String invalid:new String[]{"[256,0,0]","[1.2,2,3]","[1,2]","[null,1,2]"}) {
            var copy=NoiseFieldEdits.copy(json).put(NoiseField.COLOR,new JSONArray(invalid));
            assertEquals(NoiseField.DEFAULT_COLOR,NoiseField.fromJson(copy).color);
        }
    }
}
