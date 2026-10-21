package com.xpe.mobile.preview;

import android.graphics.RectF;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.NoteTextureSet;
import com.xpe.mobile.model.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34})
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PreviewNotePickerTest {
    private PreviewRenderer renderer;
    private final EditorSettings settings=new EditorSettings();
    private final RectF viewport=new RectF(40,80,1040,680);
    @Before public void setup(){
        var context=RuntimeEnvironment.getApplication();
        renderer=new PreviewRenderer(new NoteTextureSet(context.getResources()),1,"",context.getAssets());
    }
    private RenderScene.RenderNote note(NoteType type,double distance,double end,boolean above,double size,double sy,boolean head){
        return new RenderScene.RenderNote(type,0,distance,end,above,size,255,false,false,head,0xffffff,sy,0,new Note());
    }
    private RenderScene.RenderLine line(int index,double angle,RenderScene.RenderNote...notes){
        return new RenderScene.RenderLine(index,0,0,0,angle,255,-1,1,1,0,null,"line.png",false,false,
                false,false,0,0,Arrays.asList(notes),List.of(),List.of());
    }
    private RenderScene scene(RenderScene.RenderLine...lines){return new RenderScene(0,0,Arrays.asList(lines),null);}
    private PreviewNotePicker.Hit pick(RenderScene scene,float x,float y,float pad){return renderer.pickNote(scene,viewport,settings,x,y,pad);}

    @Test public void choosesVisibleTopmostNoteInTheActualGlobalTypeAndLineOrder(){
        var flick=note(NoteType.FLICK,0,0,true,1,1,true);
        var tap=note(NoteType.TAP,0,0,true,1,1,true);
        assertSame(flick.source,pick(scene(line(7,0,flick),line(1,0,tap)),540,380,0).note);
        var second=note(NoteType.TAP,0,0,true,1,1,true);
        var hit=pick(scene(line(7,0,tap),line(1,0,second)),540,380,0);
        assertSame(second.source,hit.note);assertEquals(1,hit.lineIndex);
    }
    @Test public void fingerToleranceFindsThinNotesButViewportAndInvisibleGeometryStayExcluded(){
        var drag=note(NoteType.DRAG,0,0,true,1,1,true);var scene=scene(line(0,0,drag));
        assertSame(drag.source,pick(scene,540,380,8).note);
        var bitmap=renderer.textures.bitmap(NoteType.DRAG,false);
        float outside=380+(float)(1000*PreviewRenderer.NOTE_WIDTH_RATIO)*bitmap.getHeight()/bitmap.getWidth()/2+4;
        assertNull(pick(scene,540,outside,0));assertSame(drag.source,pick(scene,540,outside,8).note);
        assertNull(pick(scene,20,380,1000)); // Black border outside the fitted viewport.
        assertNull(pick(scene(line(0,0,note(NoteType.TAP,0,0,true,0,1,true))),540,380,8));
        assertNull(pick(scene(line(0,0,note(NoteType.TAP,0,0,true,1,0,true))),540,380,8));
        var hidden=new RenderScene.RenderNote(NoteType.TAP,0,0,0,true,1,0,false,false,true,0xffffff,1,0,new Note());
        assertNull(pick(scene(line(0,0,hidden)),540,380,8));
        assertNull(pick(scene(line(0,0,note(NoteType.TAP,2000,2000,true,1,1,true))),540,90,100));
    }
    @Test public void previewScaleAndMirroredNotesUseTheirRenderedWidth(){
        var n=note(NoteType.TAP,0,0,true,-1,1,true);var scene=scene(line(0,0,n));
        settings.previewNoteScale=.8;assertNull(pick(scene,600,380,0));
        settings.previewNoteScale=1.2;assertSame(n.source,pick(scene,600,380,0).note);
    }
    @Test public void rotatedBelowLineHoldBodiesAndTailCapsAreSelectable(){
        var hold=note(NoteType.HOLD,0,300,false,1,1,true);
        // Below-line body extends down before rotation; a -90-degree Canvas
        // rotation puts its midpoint to the right of the line origin.
        var scene=scene(line(3,90,hold));
        assertSame(hold.source,pick(scene,640,380,0).note);
        assertNull(pick(scene,540,480,0));
    }
    @Test public void negativeSpeedHoldHasNoClickableBodyBetweenItsSeparateHeadAndTail(){
        var hold=note(NoteType.HOLD,0,-300,true,1,1,true);var scene=scene(line(0,0,hold));
        assertSame(hold.source,pick(scene,540,385,0).note);
        assertNull(pick(scene,540,480,0));
        assertSame(hold.source,pick(scene,540,575,0).note);
        var active=note(NoteType.HOLD,0,300,true,1,1,false);
        assertNull(pick(scene(line(0,0,active)),540,385,0));
        assertSame(active.source,pick(scene(line(0,0,active)),540,280,0).note);
    }
    @Test public void evaluatorRetainsIdentityEvenWhenRawNotesAndLinesAreNotDrawnInSourceOrder()throws Exception{
        ChartDocument chart=ChartDocument.fromJson("{}");JudgeLine first=chart.judgeLines.get(0);first.zOrder=20;
        Note a=new Note();a.startTime=new BeatTime(2,0,1);a.endTime=a.startTime;first.notes.add(a);
        JudgeLine second=new JudgeLine();second.zOrder=-10;Note b=new Note();b.startTime=new BeatTime(1,0,1);b.endTime=b.startTime;second.notes.add(b);
        chart.judgeLines.add(second);
        var frame=ChartEvaluator.evaluateAtSeconds(chart,0,false,-1,false,1.5,null);
        assertEquals(1,frame.lines.get(0).sourceIndex);
        assertSame(b,frame.lines.get(0).notes.get(0).source);
        assertSame(a,frame.lines.get(1).notes.get(0).source);
    }
}
