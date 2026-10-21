package com.xpe.mobile.editor;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Looper;
import android.view.*;
import com.xpe.mobile.R;
import com.xpe.mobile.model.*;
import com.xpe.mobile.preview.*;
import java.time.Duration;
import java.lang.reflect.Proxy;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},qualifiers="w800dp-h360dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class PreviewNoteSourceUiTest {
    private EditorView view;private ChartDocument chart;private Note target;
    private int pauses,starts;private long seek=-1;private boolean ready;
    private ActivityController<Activity> controller;
    private long clock=1000;
    @Before public void setup()throws Exception{
        controller=Robolectric.buildActivity(Activity.class).setup();var activity=controller.get();activity.setTheme(R.style.AppTheme);
        view=new EditorView(activity);activity.setContentView(view);chart=ChartDocument.fromJson("{}");
        JudgeLine owner=new JudgeLine();owner.name="Source line";owner.zOrder=20;
        for(LineEvent speed:owner.layer(0).events(EventType.SPEED)){speed.start=0;speed.end=0;}
        target=new Note();target.startTime=new BeatTime(2,0,1);target.endTime=target.startTime;owner.notes.add(target);
        chart.judgeLines.add(owner);view.setChart(chart);
        view.setCallback((EditorChromeView.Callback)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{EditorChromeView.Callback.class},(p,m,args)->{
            switch(m.getName()){
                case "isAudioReady":return ready;
                case "isAudioPlaying":return true;
                case "pauseAudio":pauses++;return null;
                case "startAudio":starts++;return null;
                case "seekAudio":seek=(Long)args[0];return null;
                case "audioDurationMs":return 30000L;
                case "audioPositionMs":return 0L;
                case "audioPositionSeconds":return 0.;
                case "schedulesHitSounds":return false;
                default:return null;
            }
        }));
        view.previewMode=true;layout(800,360);draw();
    }
    @After public void finish(){controller.pause().stop().destroy();}
    private void layout(int w,int h){view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h);}
    private void draw(){view.draw(new Canvas());}
    private void touch(int action,float x,float y){var e=MotionEvent.obtain(1000,clock,action,x,y,0);view.onTouchEvent(e);e.recycle();clock+=20;}
    private void tap(float x,float y){touch(MotionEvent.ACTION_DOWN,x,y);touch(MotionEvent.ACTION_UP,x,y);}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout()+10));}

    @Test public void tapSelectsTheOwnerAndExactNoteWithoutSeekingEditingOrLeavingPreview()throws Exception{
        String before=chart.toJsonString();long revision=chart.revision();
        touch(MotionEvent.ACTION_DOWN,400,191.5f);assertEquals(0,view.lineIndex);assertFalse(view.previewNoteSource.hasSource());
        touch(MotionEvent.ACTION_UP,400,191.5f);assertEquals(1,view.lineIndex);assertSame(target,view.selectedNote);
        assertTrue(view.previewMode);assertEquals(0,view.currentBeat,0);assertTrue(view.previewNoteSource.description().contains("Source line"));
        assertEquals(before,chart.toJsonString());assertEquals(revision,chart.revision());assertFalse(view.history.canUndo());
    }
    @Test public void editNoteButtonReturnsToTheSelectedSourceInTheGridAndSeeksPausedAudio(){
        ready=true;tap(400,191.5f);draw();
        var button=view.buttons.get(2).bounds;tap(button.centerX(),button.centerY());
        assertFalse(view.previewMode);assertEquals(0,view.workbenchTab);assertEquals(1,view.lineIndex);
        assertSame(target,view.selectedNote);assertEquals(2,view.currentBeat,0);assertEquals(1000,seek);assertTrue(pauses>0);
        assertEquals(EditorSurfaceView.Tool.SELECT,view.tool);assertFalse(view.history.canUndo());
    }
    @Test public void longPressShowsOriginWithoutChangingLineAndTheFooterCanOpenTheNote(){
        touch(MotionEvent.ACTION_DOWN,400,191.5f);idle();
        assertTrue(view.previewNoteSource.hasSource());assertEquals(0,view.lineIndex);
        touch(MotionEvent.ACTION_UP,400,191.5f);assertEquals(0,view.lineIndex);draw();tap(400,350);
        assertFalse(view.previewMode);assertEquals(1,view.lineIndex);assertSame(target,view.selectedNote);
    }
    @Test public void hoverShowsOriginOnlyAfterDelayAndExitCancelsPendingHover(){
        var e=MotionEvent.obtain(0,0,MotionEvent.ACTION_HOVER_MOVE,400,191.5f,0);view.onHoverEvent(e);e.recycle();
        e=MotionEvent.obtain(0,0,MotionEvent.ACTION_HOVER_EXIT,400,191.5f,0);view.onHoverEvent(e);e.recycle();
        idle();assertFalse(view.previewNoteSource.hasSource());
        e=MotionEvent.obtain(0,0,MotionEvent.ACTION_HOVER_MOVE,400,191.5f,0);view.onHoverEvent(e);e.recycle();
        assertFalse(view.previewNoteSource.hasSource());idle();assertTrue(view.previewNoteSource.hasSource());assertEquals(0,view.lineIndex);
        e=MotionEvent.obtain(0,0,MotionEvent.ACTION_HOVER_EXIT,400,191.5f,0);view.onHoverEvent(e);e.recycle();
        assertFalse(view.previewNoteSource.hasSource());idle();assertFalse(view.previewNoteSource.hasSource());
    }
    @Test public void panningAndCancellationNeverSelectANoteOrLeaveDelayedHints(){
        touch(MotionEvent.ACTION_DOWN,400,191.5f);touch(MotionEvent.ACTION_MOVE,400,240);touch(MotionEvent.ACTION_UP,400,240);idle();
        assertEquals(0,view.lineIndex);assertFalse(view.previewNoteSource.hasSource());assertTrue(view.currentBeat>0);
        view.currentBeat=0;draw();touch(MotionEvent.ACTION_DOWN,400,191.5f);touch(MotionEvent.ACTION_CANCEL,400,191.5f);idle();
        assertEquals(0,view.lineIndex);assertFalse(view.previewNoteSource.hasSource());
    }
    @Test public void smallFingerWobbleDoesNotSeekAndBlackBordersDoNotPick(){
        touch(MotionEvent.ACTION_DOWN,400,191.5f);touch(MotionEvent.ACTION_MOVE,402,193);touch(MotionEvent.ACTION_UP,402,193);
        assertEquals(1,view.lineIndex);assertEquals(0,view.currentBeat,0);
        view.lineIndex=0;draw();tap(10,191.5f);assertEquals(0,view.lineIndex);assertFalse(view.previewNoteSource.hasSource());
    }
    @Test public void deletedNotesAndProjectChangesCannotNavigateToAStaleTarget()throws Exception{
        touch(MotionEvent.ACTION_DOWN,400,191.5f);chart.judgeLines.get(1).notes.remove(target);touch(MotionEvent.ACTION_UP,400,191.5f);
        assertEquals(0,view.lineIndex);assertFalse(view.previewNoteSource.hasSource());
        chart.judgeLines.get(1).notes.add(target);draw();touch(MotionEvent.ACTION_DOWN,400,191.5f);
        view.setChart(ChartDocument.fromJson("{}"));idle();touch(MotionEvent.ACTION_UP,400,191.5f);
        assertFalse(view.previewNoteSource.hasSource());assertEquals(0,view.lineIndex);
    }
    @Test public void sourceTapResumesPlayingAudioAtTheSamePosition(){
        ready=true;view.playing=true;view.lastFrameMs=android.os.SystemClock.elapsedRealtime();
        tap(400,191.5f);assertTrue(view.playing);assertEquals(1,pauses);assertEquals(1,starts);assertEquals(0,view.currentBeat,0);
    }
    @Test public void aSecondFingerCancelsPickingAndItsDelayedLongPress(){
        touch(MotionEvent.ACTION_DOWN,400,191.5f);
        MotionEvent.PointerProperties[] props={new MotionEvent.PointerProperties(),new MotionEvent.PointerProperties()};
        MotionEvent.PointerCoords[] coords={new MotionEvent.PointerCoords(),new MotionEvent.PointerCoords()};
        for(int i=0;i<2;i++){props[i].id=i;props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coords[i].x=400+i*80;coords[i].y=191.5f;coords[i].pressure=1;coords[i].size=1;}
        var event=MotionEvent.obtain(1000,clock,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,
                props,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        view.onTouchEvent(event);event.recycle();touch(MotionEvent.ACTION_UP,400,191.5f);idle();
        assertEquals(0,view.lineIndex);assertFalse(view.previewNoteSource.hasSource());
    }
    @Test public void acceptedGpuSnapshotRetainsSampledGeometryAfterItsSourceNoteChanges()throws Exception{
        RenderScene accepted=ChartEvaluator.evaluateAtSeconds(chart,0,false,-1,false,1.5,null);
        target.positionX=500;chart.markEdited();
        PreviewViewportCalculator.Result fitted=PreviewViewportCalculator.fit(0,48,800,335,view.settings.playerWidth,view.settings.playerHeight);
        RectF viewport=new RectF((float)fitted.left,(float)fitted.top,(float)fitted.right,(float)fitted.bottom);
        var surface=new PreviewShaderView(view.getContext());
        String[] names={"submittedScene","submittedViewport","submittedSettings"};
        Object[] values={accepted,viewport,view.settings};
        for(int i=0;i<names.length;i++){var field=PreviewShaderView.class.getDeclaredField(names[i]);field.setAccessible(true);field.set(surface,values[i]);}
        draw();
        view.previewNoteSource.frame(surface.submittedScene(),surface.submittedViewport(),surface.submittedSettings());
        tap(400,191.5f);assertEquals(1,view.lineIndex);assertSame(target,view.selectedNote);
        surface.hidePreview();assertNull(surface.submittedScene());
    }
}
