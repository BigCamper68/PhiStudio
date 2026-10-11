package com.xpe.mobile.editor;

import android.graphics.*;
import android.view.View;
import com.xpe.mobile.model.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w1200dp-h700dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class EditorNoteAppearanceTest {
    EditorView editor;
    @Before public void setup()throws Exception {
        editor=new EditorView(RuntimeEnvironment.getApplication());editor.setChart(ChartDocument.fromJson("{}"));
        editor.measure(View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.EXACTLY));editor.layout(0,0,1200,700);
        editor.currentBeat=4;editor.settings.highlightSimultaneousNotes=false;
    }
    static class Recorded extends Canvas {
        final List<Integer> alphas=new ArrayList<>();
        final List<RectF> borders=new ArrayList<>();
        @Override public void drawBitmap(Bitmap bitmap,Rect src,RectF dst,Paint paint) { alphas.add(paint.getAlpha()); }
        @Override public void drawRoundRect(RectF rect,float rx,float ry,Paint paint) {
            if(paint.getStyle()==Paint.Style.STROKE && paint.getColor()==Color.WHITE)borders.add(new RectF(rect));
        }
    }
    Note note(NoteType type) {
        Note note=new Note();note.type=type;note.startTime=new BeatTime(4,0,1);
        note.endTime=new BeatTime(type==NoteType.HOLD?6:4,0,1);note.alpha=0;note.size=1.7;
        editor.currentLine().notes.clear();editor.currentLine().notes.add(note);
        editor.getChart().markEdited();
        editor.selectedNotes.clear();editor.selectedNote=null;
        return note;
    }
    Recorded draw() {
        Recorded canvas=new Recorded();editor.drawNotes(canvas,0,editor.toolbarHeight(),editor.editorSplitX(),700-editor.bottomBarHeight());
        return canvas;
    }
    @Test public void everyNoteIncludingHoldIsOpaqueInGridWithoutChangingStoredAlpha() {
        for(NoteType type:NoteType.values()) {
            Note note=note(type);Recorded drawn=draw();assertFalse(type.toString(),drawn.alphas.isEmpty());
            for(int alpha:drawn.alphas)assertEquals(255,alpha);
            assertEquals(0,note.alpha);
        }
    }
    @Test public void selectionFramesMatchTapForNormalAndSimultaneousNotes() {
        for(boolean multi:new boolean[]{false,true}) {
            editor.settings.highlightSimultaneousNotes=multi;RectF tap=null;
            for(NoteType type:new NoteType[]{NoteType.TAP,NoteType.DRAG,NoteType.FLICK}) {
                Note note=note(type);
                if(multi) { Note other=note.copy();other.positionX=300;editor.currentLine().notes.add(other); }
                editor.selectedNote=note;Recorded drawn=draw();assertEquals(1,drawn.borders.size());
                if(tap==null)tap=drawn.borders.get(0);else assertEquals(type+" multi="+multi,tap,drawn.borders.get(0));
            }
        }
    }
    @Test public void fakeNoteFramesAlsoMatchTapAndHoldKeepsItsFullLength() {
        RectF tap=null;
        for(NoteType type:new NoteType[]{NoteType.TAP,NoteType.DRAG,NoteType.FLICK}) {
            Note note=note(type);note.fake=true;Recorded drawn=draw();assertEquals(1,drawn.borders.size());
            if(tap==null)tap=drawn.borders.get(0);else assertEquals(tap,drawn.borders.get(0));
        }
        Note hold=note(NoteType.HOLD);editor.selectedNote=hold;
        RectF frame=draw().borders.get(0);
        assertEquals(2*editor.pixelsPerBeat+6*editor.density,frame.height(),.001);
        assertEquals(editor.timelineY(700-editor.bottomBarHeight())+3*editor.density,frame.bottom,.001);
    }
    @Test public void previewStillUsesTheStoredNoteAlpha() {
        Note note=note(NoteType.TAP);note.alpha=73;
        draw();
        com.xpe.mobile.preview.RenderScene scene=com.xpe.mobile.preview.ChartEvaluator.evaluate(editor.getChart(),3,false,-1,false,16.0/9);
        assertFalse(scene.lines.get(0).notes.isEmpty());assertEquals(73,scene.lines.get(0).notes.get(0).alpha);
    }
}
