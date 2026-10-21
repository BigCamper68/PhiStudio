package com.xpe.mobile.preview;

import com.xpe.mobile.model.NoiseField;
import com.xpe.mobile.model.ChartDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Native touch infection lasts until release, even after leaving the rectangle. */
final class NoiseFieldInput {
    private final List<NoiseField> fields;
    private final double aspect;
    private ChartDocument chart;
    private PreviewChartIndex.PreparedChart prepared;
    private final Map<Integer,Contact> contacts = new LinkedHashMap<>();
    private final Slot[] slots = new Slot[10];
    private NoiseFieldFrame geometry = NoiseFieldFrame.EMPTY;
    private float geometryTime = Float.NaN;
    private static final class Contact { float x,y; boolean blocked; Slot slot; }
    private static final class Slot {
        float x,y,start,from,to; boolean held;
        float size(float time) { return from+(to-from)*Math.max(0,Math.min(1,(time-start)/.1f)); }
    }
    NoiseFieldInput(List<NoiseField> fields,double aspect) { this.fields=fields;this.aspect=aspect; }
    NoiseFieldInput(ChartDocument chart, PreviewChartIndex.PreparedChart prepared, double aspect) {
        this(chart.noiseFields, aspect); this.chart = chart; this.prepared = prepared;
    }
    private boolean empty() { return fields.isEmpty() && (chart == null || chart.noiseMarkerSizes.isEmpty()); }
    void clear() { contacts.clear();java.util.Arrays.fill(slots,null);geometryTime=Float.NaN; }
    boolean touching() { for(Contact contact:contacts.values())if(contact.blocked)return true;return false; }
    private NoiseFieldFrame geometry(double time) {
        if ((float)time!=geometryTime) {geometryTime=(float)time;geometry=chart == null ? NoiseFieldEvaluator.evaluate(fields,time,aspect)
                : PhiraNoiseFieldMarkers.evaluate(chart, prepared, time, aspect);}
        return geometry;
    }
    boolean event(int id,PlaytestJudge.Phase phase,float x,float y,double at) {
        if(empty())return true;
        if(phase==PlaytestJudge.Phase.DOWN) {
            Contact old=contacts.remove(id);if(old!=null)release(old,(float)at);
            contacts.put(id,new Contact());
        }
        Contact contact=contacts.get(id);
        if(contact==null)return true;
        contact.x=x;contact.y=y;
        if(contact.slot!=null){contact.slot.x=x;contact.slot.y=y;}
        if(phase==PlaytestJudge.Phase.UP||phase==PlaytestJudge.Phase.CANCEL) {
            boolean allowed=!contact.blocked;release(contact,(float)at);contacts.remove(id);return allowed;
        }
        if(!contact.blocked && geometry(at).blocks(x,y))block(contact,(float)at);
        return !contact.blocked;
    }
    Set<Integer> advance(double time) {
        Set<Integer> blocked=new HashSet<>();
        if(empty())return blocked;
        NoiseFieldFrame current=geometry(time);
        for(Map.Entry<Integer,Contact> entry:contacts.entrySet()) {
            Contact contact=entry.getValue();
            if(!contact.blocked&&current.blocks(contact.x,contact.y))block(contact,(float)time);
            if(contact.blocked)blocked.add(entry.getKey());
        }
        return blocked;
    }
    NoiseFieldFrame frame(double seconds) {
        if(empty())return NoiseFieldFrame.EMPTY;
        List<NoiseFieldFrame.Touch> touches=new ArrayList<>();
        for(int i=0;i<slots.length;i++) {
            Slot slot=slots[i];if(slot==null)continue;
            float size=slot.size((float)seconds);
            if(size>0)touches.add(new NoiseFieldFrame.Touch(slot.x,slot.y,size,slot.held));
            else if(!slot.held)slots[i]=null;
        }
        NoiseFieldFrame current=geometry(seconds);
        return new NoiseFieldFrame((float)seconds,(float)aspect,current.zones,touches);
    }
    private void block(Contact contact,float time) {
        contact.blocked=true;
        for(int i=0;i<slots.length;i++) {
            if(slots[i]!=null&&slots[i].held)continue;
            Slot slot=new Slot();slot.x=contact.x;slot.y=contact.y;slot.start=time;slot.to=1;slot.held=true;
            contact.slot=slot;slots[i]=slot;break;
        }
    }
    private static void release(Contact contact,float time) {
        if(contact.slot==null)return;
        Slot slot=contact.slot;slot.from=slot.size(time);slot.to=0;slot.start=time;slot.held=false;
    }
}
