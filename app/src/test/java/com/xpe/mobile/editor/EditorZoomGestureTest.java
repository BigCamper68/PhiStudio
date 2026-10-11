package com.xpe.mobile.editor;

import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import com.xpe.mobile.model.ChartDocument;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w800dp-h500dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class EditorZoomGestureTest {
    private EditorView editor(int tab) throws Exception {
        EditorView view = new EditorView(RuntimeEnvironment.getApplication());
        view.setChart(ChartDocument.fromJson("{}")); view.setWorkbenchTab(tab);
        view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 800, 500);
        view.currentBeat = 4;
        return view;
    }
    private void one(EditorView view, long down, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
        view.onTouchEvent(event); event.recycle();
    }
    @Test public void androidQuickScaleIsExplicitlyDisabled() throws Exception {
        EditorView view = editor(0);
        java.lang.reflect.Field field = EditorView.class.getDeclaredField("scaleDetector"); field.setAccessible(true);
        assertFalse(((ScaleGestureDetector) field.get(view)).isQuickScaleEnabled());
    }
    @Test public void fastDoubleTapThenOneFingerDragCannotZoomAnyGrid() throws Exception {
        for (int region = 0; region < 3; region++) {
            EditorView view = editor(region == 2 ? 1 : 0);
            float x = region == 0 ? 200 : 600, y = 220;
            float original = view.pixelsPerBeat;
            long time = android.os.SystemClock.uptimeMillis();
            one(view, time, time, MotionEvent.ACTION_DOWN, x, y);
            one(view, time, time + 30, MotionEvent.ACTION_UP, x, y);
            one(view, time + 100, time + 100, MotionEvent.ACTION_DOWN, x, y);
            for (int step = 1; step <= 4; step++)
                one(view, time + 100, time + 100 + step * 20, MotionEvent.ACTION_MOVE, x, y + step * 22);
            one(view, time + 100, time + 200, MotionEvent.ACTION_UP, x, y + 88);
            assertEquals("region=" + region, original, view.pixelsPerBeat, .001f);
        }
    }
    @Test public void actualTwoFingerPinchStillChangesTheGridScale() throws Exception {
        EditorView view = editor(0); float original = view.pixelsPerBeat;
        long time = android.os.SystemClock.uptimeMillis();
        one(view, time, time, MotionEvent.ACTION_DOWN, 200, 220);
        two(view, time, time + 20, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200, 400);
        two(view, time, time + 40, MotionEvent.ACTION_MOVE, 100, 500);
        two(view, time, time + 60, MotionEvent.ACTION_MOVE, 50, 550);
        assertTrue(view.pixelsPerBeat > original);
        two(view, time, time + 80, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 50, 550);
        one(view, time, time + 100, MotionEvent.ACTION_UP, 50, 220);
    }
    @Test public void eventGroupClearsWithoutSeekingAndUnselectedTapStillSeeks() throws Exception {
        EditorView view=editor(0); view.tool=EditorSurfaceView.Tool.SELECT; view.settings.tapToSeek=true;
        com.xpe.mobile.model.LineEvent a=new com.xpe.mobile.model.LineEvent();
        com.xpe.mobile.model.LineEvent b=new com.xpe.mobile.model.LineEvent();
        view.selectedEvent=a;view.selectedEvents.add(a);view.selectedEvents.add(b);
        long t=android.os.SystemClock.uptimeMillis();
        one(view,t,t,MotionEvent.ACTION_DOWN,600,180);
        one(view,t,t+20,MotionEvent.ACTION_UP,600,180);
        assertEquals(4,view.currentBeat,0);assertTrue(view.selectedEvents.isEmpty());assertNull(view.selectedEvent);
        one(view,t+500,t+500,MotionEvent.ACTION_DOWN,600,180);
        one(view,t+500,t+520,MotionEvent.ACTION_UP,600,180);
        assertNotEquals(4,view.currentBeat,0);
    }
    private void prepareStoryboard(EditorView view) {
        android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(800,500,android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(image));image.recycle();
    }
    @Test public void defaultEmptyTapDoesNotSeekAnyGridButEnabledTapDoes() throws Exception {
        for(int region=0;region<3;region++) {
            EditorView view=editor(region==2?1:0);view.tool=EditorSurfaceView.Tool.SELECT;prepareStoryboard(view);
            float x=region==0?200:600;long t=android.os.SystemClock.uptimeMillis();
            one(view,t,t,MotionEvent.ACTION_DOWN,x,180);one(view,t,t+20,MotionEvent.ACTION_UP,x,180);
            assertEquals(4,view.currentBeat,0);
            view.settings.tapToSeek=true;
            one(view,t+500,t+500,MotionEvent.ACTION_DOWN,x,180);one(view,t+500,t+520,MotionEvent.ACTION_UP,x,180);
            assertNotEquals("region="+region,4,view.currentBeat,0);
        }
    }
    @Test public void noteGroupsClearOnTheirOwnOrEitherForeignGridWithoutSeeking() throws Exception {
        for(int region=0;region<3;region++) {
            EditorView view=editor(region==2?1:0);view.tool=EditorSurfaceView.Tool.SELECT;prepareStoryboard(view);
            view.settings.tapToSeek=true;
            com.xpe.mobile.model.Note a=new com.xpe.mobile.model.Note(),b=new com.xpe.mobile.model.Note();
            view.selectedNote=a;view.selectedNotes.add(a);view.selectedNotes.add(b);
            float x=region==0?200:600;long t=android.os.SystemClock.uptimeMillis();
            one(view,t,t,MotionEvent.ACTION_DOWN,x,180);
            assertEquals(2,view.selectedNotes.size());
            one(view,t,t+20,MotionEvent.ACTION_UP,x,180);
            assertNull(view.selectedNote);assertTrue(view.selectedNotes.isEmpty());assertEquals(4,view.currentBeat,0);
        }
    }
    @Test public void eventsAndStoryboardClearByTappingEmptyNoteGridOnlyOnRelease() throws Exception {
        for(int tab=0;tab<2;tab++) {
            EditorView view=editor(tab);view.tool=EditorSurfaceView.Tool.SELECT;prepareStoryboard(view);
            view.settings.tapToSeek=true;
            if(tab==0) {
                view.selectedEvent=new com.xpe.mobile.model.LineEvent();view.selectedEvents.add(view.selectedEvent);
            } else {
                com.xpe.mobile.model.ExtendedLineEvents.NumericEvent e=new com.xpe.mobile.model.ExtendedLineEvents.NumericEvent();
                view.addStoryboardEvent(com.xpe.mobile.model.StoryboardEventType.SCALE_X,e);
                view.storyboardTimeline.select(com.xpe.mobile.model.StoryboardEventType.SCALE_X,e);
            }
            long t=android.os.SystemClock.uptimeMillis();
            one(view,t,t,MotionEvent.ACTION_DOWN,200,180);
            if(tab==1)assertEquals(1,view.storyboardTimeline.selectionCount());
            one(view,t,t+20,MotionEvent.ACTION_UP,200,180);
            assertNull(view.selectedEvent);assertEquals(0,view.storyboardTimeline.selectionCount());assertEquals(4,view.currentBeat,0);
        }
    }
    private void two(EditorView view, long down, long time, int action, float first, float second) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        for (int index = 0; index < 2; index++) {
            properties[index] = new MotionEvent.PointerProperties(); properties[index].id = index;
            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[index] = new MotionEvent.PointerCoords(); coords[index].x = index == 0 ? first : second;
            coords[index].y = 220; coords[index].pressure = 1; coords[index].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(down, time, action, 2, properties, coords, 0, 0,
                1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        view.onTouchEvent(event); event.recycle();
    }
}
