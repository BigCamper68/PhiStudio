import com.xpe.mobile.model.*;
import com.xpe.mobile.packageio.*;
import com.xpe.mobile.preview.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Differential snapshots against the preserved previous-patch classes. */
public final class SceneEquivalenceProbe {
    static MessageDigest digest;
    static DataOutputStream output;
    static int frames;
    static void value(Object v)throws Exception {
        if(v==null){output.writeByte(0);return;}
        if(v instanceof Number n){output.writeByte(1);output.writeUTF(n.getClass().getSimpleName());output.writeUTF(n.toString());return;}
        if(v instanceof Boolean b){output.writeByte(2);output.writeBoolean(b);return;}
        if(v instanceof String||v instanceof Enum){output.writeByte(3);byte[] bytes=v.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);output.writeInt(bytes.length);output.write(bytes);return;}
        if(v instanceof Map<?,?> map){output.writeByte(4);List<Object> keys=new ArrayList<>(map.keySet());keys.sort(Comparator.comparing(Object::toString));output.writeInt(keys.size());for(Object key:keys){value(key);value(map.get(key));}return;}
        if(v instanceof Iterable<?> list){output.writeByte(5);int count=0;for(Object item:list){value(item);count++;}output.writeInt(count);return;}
        if(v.getClass().isArray()){output.writeByte(6);int n=Array.getLength(v);output.writeInt(n);for(int i=0;i<n;i++)value(Array.get(v,i));return;}
        output.writeByte(7);Field[] fields=v.getClass().getFields();Arrays.sort(fields,Comparator.comparing(Field::getName));
        for(Field field:fields)if(!Modifier.isStatic(field.getModifiers())){output.writeUTF(field.getName());value(field.get(v));}
        output.writeByte(8);
    }
    static void scene(RenderScene s)throws Exception {
        value(s);frames++;
        // Stateful paint history is intentionally not a public Scene field.
        Field histories=RenderScene.class.getDeclaredField("paintHistories");histories.setAccessible(true);
        Map<?,?> map=(Map<?,?>)histories.get(s);output.writeInt(map.size());
        for(Object key:new TreeSet<>(map.keySet())) {
            Object cursor=map.get(key);
            while(cursor!=null) {
                Field stroke=cursor.getClass().getDeclaredField("stroke"),previous=cursor.getClass().getDeclaredField("previous");
                stroke.setAccessible(true);previous.setAccessible(true);value(stroke.get(cursor));cursor=previous.get(cursor);
            }
        }
    }
    static void chart(ChartDocument chart)throws Exception {
        value(chart.toJsonString());double end=8;
        for(JudgeLine line:chart.judgeLines)for(Note note:line.notes)end=Math.max(end,note.endTime.toDouble()+1);
        for(boolean modern:new boolean[]{false,true})for(double aspect:new double[]{1.5,16.0/9}) {
            ChartEvaluator.prepare(chart,modern);
            for(int i=0;i<=180;i++)scene(ChartEvaluator.evaluate(chart,end*i/180,false,-1,modern,aspect));
        }
    }
    static ChartDocument paintChart() {
        ChartDocument c=new ChartDocument();c.noteUniformScale=true;BpmChange bpm=new BpmChange();bpm.bpm=120;c.bpmChanges.add(bpm);
        JudgeLine line=new JudgeLine();c.judgeLines.add(line);
        var paint=new ExtendedLineEvents.NumericEvent();paint.start=paint.end=9;paint.endTime=new BeatTime(6,0,1);line.extended.paintEvents.add(paint);
        var stop=new ExtendedLineEvents.NumericEvent();stop.startTime=new BeatTime(6,0,1);stop.endTime=new BeatTime(8,0,1);stop.start=stop.end=0;line.extended.paintEvents.add(stop);
        var move=line.eventLayers.get(0).events(EventType.MOVE_X).get(0);move.endTime=new BeatTime(8,0,1);move.start=-100;move.end=200;
        for(NoteType type:NoteType.values()) {
            Note n=new Note();n.type=type;n.startTime=new BeatTime(2,0,1);n.endTime=new BeatTime(4,0,1);n.positionX=type.ordinal()*50;n.size=-1.5;line.notes.add(n);
        }
        return c;
    }
    public static void main(String[] args)throws Exception {
        digest=MessageDigest.getInstance("SHA-256");output=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest));
        chart(ChartDocument.fromJson(Files.readString(Path.of(args[0]))));
        for(int i=2;i<args.length;i++) {
            Path archive=Path.of(args[i]),workspace=Path.of(args[1]).resolve("chart-"+i);Files.createDirectories(workspace.getParent());
            if(Files.exists(workspace))delete(workspace.toFile());
            try(InputStream stream=Files.newInputStream(archive)) {
                ChartPackage pkg=new PackageImporter().importPackage(stream,workspace.toFile(),archive.getFileName().toString());
                chart(pkg.getChart());value(pkg.getProjectName());value(pkg.isUseRpe170Speed());value(pkg.getChart().noteUniformScale);
            }
        }
        for(double aspect:new double[]{1.5,16.0/9}) {
            ChartDocument c=paintChart();PreviewPlaybackState state=new PreviewPlaybackState();
            for(int i=0;i<=400;i++)scene(ChartEvaluator.evaluate(c,i*.02,false,-1,true,aspect,state));
            state.reset();scene(ChartEvaluator.evaluate(c,0,false,-1,true,aspect,state));
        }
        output.flush();System.out.println("frames="+frames+" sha256="+HexFormat.of().formatHex(digest.digest()));
    }
    static void delete(File file){File[] list=file.listFiles();if(list!=null)for(File child:list)delete(child);if(!file.delete())throw new IllegalStateException(file.toString());}
}
