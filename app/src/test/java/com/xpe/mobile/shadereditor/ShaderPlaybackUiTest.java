package com.xpe.mobile.shadereditor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.xpe.mobile.editor.EditorUiTestAccess;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.model.ChartDocument;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w800dp-h500dp-land-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public final class ShaderPlaybackUiTest extends ShaderEditorViewTestSupport {
    @Test
    public void switchingPreservesShaderDraftChartPositionAndOtherPanelDrafts() {
        workbench.switchTab(1);
        EditText storyboard = new EditText(activity);
        storyboard.setText("keep storyboard");
        EditorUiTestAccess.showDraft(activity, workbench, "Draft", storyboard);
        editor.shaderEditorSeek(3.5);
        double seconds = editor.shaderEditorSeconds();
        workbench.switchTab(3);
        fields(shaders).get(0).setText("1.25");
        workbench.switchTab(2);
        assertEquals(seconds, editor.shaderEditorSeconds(), 0);
        workbench.switchTab(3);
        assertEquals("1.25", fields(shaders).get(0).getText().toString());
        workbench.switchTab(1);
        assertEquals("keep storyboard", storyboard.getText().toString());
        assertEquals(View.VISIBLE, editor.getVisibility());
        assertEquals(View.GONE, shaders.getVisibility());
    }

    @Test
    public void emptyTimelineDragSeeksWithoutEditingChartOrShaderAndDoubleTapDragDoesNotZoom()
        throws Exception {
        workbench.switchTab(3);
        layout(1200, 700);
        String snapshot = document.json();
        float scale = scale();
        long now = 10;
        float x = shaders.timeline.getWidth() - 5, y = shaders.timeline.getHeight() / 2f;
        touch(now, MotionEvent.ACTION_DOWN, x, y);
        touch(now + 10, MotionEvent.ACTION_UP, x, y);
        touch(now + 60, MotionEvent.ACTION_DOWN, x, y);
        touch(now + 70, MotionEvent.ACTION_MOVE, x, y + 30);
        touch(now + 80, MotionEvent.ACTION_UP, x, y + 30);
        assertEquals(scale, scale(), 0);
        assertTrue(editor.shaderEditorSeconds() > 0);
        assertEquals(snapshot, document.json());
        assertFalse(editor.isChartDirty());
    }

    @Test public void emptyShaderTapSeeksOnlyWhenEnabledAndNeverWhileClearing() throws Exception {
        workbench.switchTab(3); layout(1200,700);drawFirstBlock();
        document.selected=-1;double original=editor.shaderEditorSeconds();
        touch(1,MotionEvent.ACTION_DOWN,5,100);touch(2,MotionEvent.ACTION_UP,5,100);
        assertEquals(original,editor.shaderEditorSeconds(),0);
        com.xpe.mobile.config.EditorSettings settings=editor.getSettings();settings.tapToSeek=true;editor.applySettings(settings);
        document.selected=0;
        touch(10,MotionEvent.ACTION_DOWN,5,100);touch(11,MotionEvent.ACTION_UP,5,100);
        assertEquals(-1,document.selected);assertEquals(original,editor.shaderEditorSeconds(),0);
        touch(20,MotionEvent.ACTION_DOWN,5,100);touch(21,MotionEvent.ACTION_UP,5,100);
        assertNotEquals(original,editor.shaderEditorSeconds(),0);
    }

    @Test public void shaderAndNormalSeekTracksHaveIdenticalWidth() throws Exception {
        for(int w:new int[]{800,1200,1920}) {
            workbench.switchTab(0);layout(w,700);
            Bitmap image=Bitmap.createBitmap(editor.getWidth(),editor.getHeight(),Bitmap.Config.ARGB_8888);
            editor.draw(new Canvas(image));image.recycle();
            java.lang.reflect.Field bounds=Class.forName("com.xpe.mobile.editor.EditorSurfaceView").getDeclaredField("playbackSeekBounds");bounds.setAccessible(true);
            android.graphics.RectF rect=(android.graphics.RectF)bounds.get(editor);
            float normalWidth=rect.width();
            workbench.switchTab(3);layout(w,700);
            View seek=((ViewGroup)shaders.getChildAt(0)).getChildAt(1);
            assertEquals(normalWidth,seek.getWidth()-2*shaders.dp(10),1);
        }
    }

    @Test public void previewHoldPausesImmediatelyAndReleaseResumesAtSameBeat() throws Exception {
        long[] calls={0,0};
        editor.setCallback((EditorView.Callback)java.lang.reflect.Proxy.newProxyInstance(
            EditorView.Callback.class.getClassLoader(),new Class<?>[]{EditorView.Callback.class},(p,m,a)->{
                if(m.getName().equals("isAudioReady")||m.getName().equals("isAudioPlaying"))return true;
                if(m.getName().equals("pauseAudio"))calls[0]++;
                if(m.getName().equals("startAudio"))calls[1]++;
                if(m.getName().equals("audioDurationMs"))return 10000L;
                if(m.getReturnType()==boolean.class)return false;
                if(m.getReturnType()==long.class)return 0L;
                return null;
            }));
        workbench.switchTab(3);layout(1200,700);editor.shaderEditorPlay();
        for(int i:new int[]{1,2}) {
            editor.shaderEditorSeek(1.234567);long beforePause=calls[0],beforeResume=calls[1];
            View surface=shaders.getChildAt(i);surface.setVisibility(View.VISIBLE);
            MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,100,100,0);surface.dispatchTouchEvent(down);down.recycle();
            assertEquals(beforePause+1,calls[0]);
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(600));
            View preview=shaders.getChildAt(1);
            Bitmap bitmap=Bitmap.createBitmap(preview.getWidth(),preview.getHeight(),Bitmap.Config.ARGB_8888);
            preview.draw(new Canvas(bitmap));bitmap.recycle();
            assertEquals(1.234567,editor.shaderEditorSeconds(),1e-9);
            MotionEvent up=MotionEvent.obtain(0,600,MotionEvent.ACTION_UP,100,100,0);surface.dispatchTouchEvent(up);up.recycle();
            assertEquals(beforeResume+1,calls[1]);assertTrue(editor.isPlaying());
        }
    }

    @Test public void shaderGridHoldPausesOnEmptySpaceAndBlocksAndResumesOnUpOrCancel() throws Exception {
        long[] calls={0,0};
        editor.setCallback((EditorView.Callback)java.lang.reflect.Proxy.newProxyInstance(
            EditorView.Callback.class.getClassLoader(),new Class<?>[]{EditorView.Callback.class},(p,m,a)->{
                if(m.getName().equals("isAudioReady")||m.getName().equals("isAudioPlaying"))return true;
                if(m.getName().equals("pauseAudio"))calls[0]++;
                if(m.getName().equals("startAudio"))calls[1]++;
                if(m.getName().equals("audioDurationMs"))return 10000L;
                if(m.getReturnType()==boolean.class)return false;
                if(m.getReturnType()==long.class)return 0L;
                return null;
            }));
        workbench.switchTab(3);layout(1200,700);editor.shaderEditorPlay();
        String chart=document.json();
        for(int region=0;region<3;region++) {
            editor.shaderEditorSeek(.25);document.selected=0;
            android.graphics.RectF body=drawFirstBlock();
            float x=region==1?body.centerX():5,y=region==1?body.centerY():100;
            long paused=calls[0],resumed=calls[1];
            touch(10,MotionEvent.ACTION_DOWN,x,y);assertEquals(paused+1,calls[0]);
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(600));
            View preview=shaders.getChildAt(1);
            Bitmap bitmap=Bitmap.createBitmap(preview.getWidth(),preview.getHeight(),Bitmap.Config.ARGB_8888);
            preview.draw(new Canvas(bitmap));bitmap.recycle();
            assertEquals(.25,editor.shaderEditorSeconds(),1e-9);
            touch(610,region==2?MotionEvent.ACTION_CANCEL:MotionEvent.ACTION_UP,x,y);
            assertEquals(resumed+1,calls[1]);assertTrue(editor.isPlaying());
            assertEquals(chart,document.json());
        }
        editor.shaderEditorSeek(.25);
        long paused=calls[0],resumed=calls[1];
        touch(10,MotionEvent.ACTION_DOWN,5,100);
        pinch(20,MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),100,300);
        pinch(30,MotionEvent.ACTION_MOVE,150,250);
        pinch(40,MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),150,250);
        assertEquals(resumed,calls[1]);assertEquals(paused+1,calls[0]);
        touch(50,MotionEvent.ACTION_UP,150,100);
        assertEquals(resumed+1,calls[1]);assertEquals(.25,editor.shaderEditorSeconds(),1e-9);
    }

    @Test public void emptyTapClearsSelectionButPanKeepsIt() throws Exception {
        workbench.switchTab(3); layout(1200,700); drawFirstBlock();
        assertEquals(0,document.selected);
        double time=editor.shaderEditorSeconds();
        touch(1,MotionEvent.ACTION_DOWN,5,100);
        touch(2,MotionEvent.ACTION_UP,5,100);
        assertEquals(-1,document.selected);assertEquals(time,editor.shaderEditorSeconds(),0);
        document.selected=0;
        touch(10,MotionEvent.ACTION_DOWN,5,100);
        touch(20,MotionEvent.ACTION_MOVE,5,150);
        touch(30,MotionEvent.ACTION_UP,5,150);
        assertEquals(0,document.selected);assertTrue(editor.shaderEditorSeconds()>time);
    }

    @Test public void previewAndGpuSurfaceBothScrubWithoutEditingOrZooming() throws Exception {
        workbench.switchTab(3);layout(1200,700);
        String before=document.json();float scale=scale();
        for(int index:new int[]{1,2}) {
            editor.shaderEditorSeek(0);
            View surface=shaders.getChildAt(index);surface.setVisibility(View.VISIBLE);
            long clock=100L*index;
            for(int j=0;j<3;j++) {
                MotionEvent event=MotionEvent.obtain(clock,clock+j*10,
                    new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}[j],
                    100,j==0?100:196,0);
                surface.dispatchTouchEvent(event);event.recycle();
            }
            assertEquals(.5,editor.shaderEditorSeconds(),.002);
        }
        assertEquals(before,document.json());assertEquals(scale,scale(),0);
    }

    @Test public void horizontalEmptyGridPanClampsAndKeepsVisibleLanesWithoutSeeking() throws Exception {
        workbench.switchTab(3);layout(800,500);
        for(int i=0;i<12;i++)document.add("lane"+i,0);
        drawFirstBlock();double time=editor.shaderEditorSeconds();
        // Gutter is empty even when every lane has an effect.
        touch(1,MotionEvent.ACTION_DOWN,5,100);
        touch(2,MotionEvent.ACTION_MOVE,-2000,103);
        touch(3,MotionEvent.ACTION_UP,-2000,103);
        assertEquals(time,editor.shaderEditorSeconds(),0);
        android.graphics.RectF visible=drawFirstBlock();
        assertTrue(visible.right>36);assertTrue(visible.left<shaders.timeline.getWidth());
        java.lang.reflect.Field field=shaders.timeline.getClass().getDeclaredField("horizontal");field.setAccessible(true);
        assertTrue(field.getFloat(shaders.timeline)>0);
        touch(10,MotionEvent.ACTION_DOWN,5,100);
        touch(11,MotionEvent.ACTION_MOVE,2000,100);
        touch(12,MotionEvent.ACTION_UP,2000,100);
        drawFirstBlock();assertEquals(0,field.getFloat(shaders.timeline),0);
    }

    @Test
    public void parameterValueIsVisibleInCompactPanelAndAnimationIsPreserved() {
        workbench.switchTab(3);
        layout(800, 360);
        button("Parameters").performClick();
        layout(800, 360);
        android.graphics.Rect visible = new android.graphics.Rect();
        assertTrue(fields(shaders).get(0).getGlobalVisibleRect(visible));
        assertEquals(fields(shaders).get(0).getHeight(), visible.height());
        button("Animate").performClick();
        String before = document.json();
        button("Parameters").performClick();
        button("Edit animation").performClick();
        assertEquals(before, document.json());
    }

    @Test
    public void topSliderSeeksAudioAndResumesAfterDragIncludingPackageOffset() {
        long[] audio = {0, 0, 0};
        editor.setPackageOffsetMs(250);
        editor.setCallback((EditorView.Callback) java.lang.reflect.Proxy.newProxyInstance(
            EditorView.Callback.class.getClassLoader(), new Class<?>[] {EditorView.Callback.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "isAudioReady":
                    case "isAudioPlaying":
                        return true;
                    case "audioDurationMs":
                        return 10000L;
                    case "audioPositionMs":
                        return audio[0];
                    case "pauseAudio":
                        audio[1]++;
                        return null;
                    case "startAudio":
                        audio[0] = (Long) args[0];
                        audio[2]++;
                        return null;
                    case "seekAudio":
                        audio[0] = (Long) args[0];
                        return null;
                }
                if (method.getReturnType() == boolean.class)
                    return false;
                if (method.getReturnType() == long.class)
                    return 0L;
                return null;
            }));
        workbench.switchTab(3);
        layout(800, 360);
        View seek = ((ViewGroup) shaders.getChildAt(0)).getChildAt(1);
        Bitmap image =
            Bitmap.createBitmap(seek.getWidth(), seek.getHeight(), Bitmap.Config.ARGB_8888);
        seek.draw(new Canvas(image));
        image.recycle();
        editor.shaderEditorPlay();
        float left = shaders.dp(10), span = seek.getWidth() - 2 * left;
        MotionEvent event =
            MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, left + span * .25f, 25, 0);
        seek.onTouchEvent(event);
        event.recycle();
        assertEquals(1, audio[1]);
        event = MotionEvent.obtain(0, 10, MotionEvent.ACTION_MOVE, left + span * .5f, 25, 0);
        seek.onTouchEvent(event);
        event.recycle();
        CheckBox loop = (CheckBox) buttons(shaders).stream()
            .filter(b -> b instanceof CheckBox && b.getText().toString().equals("Loop"))
            .findFirst().orElseThrow();
        loop.setChecked(true);
        double draggedPosition = editor.shaderEditorSeconds();
        View preview = shaders.getChildAt(1);
        image = Bitmap.createBitmap(preview.getWidth(), preview.getHeight(), Bitmap.Config.ARGB_8888);
        preview.draw(new Canvas(image));
        image.recycle();
        assertEquals("Loop must not override an active scrub", draggedPosition, editor.shaderEditorSeconds(), 0);
        loop.setChecked(false);
        event = MotionEvent.obtain(0, 20, MotionEvent.ACTION_UP, left + span * .5f, 25, 0);
        seek.onTouchEvent(event);
        event.recycle();
        assertTrue(editor.isPlaying());
        assertEquals(5000, audio[0]);
        assertEquals(2, audio[2]);
        assertEquals(
            editor.getChart().beatToSeconds(editor.getChart().audioMillisToBeat(5000, 250)),
            editor.shaderEditorSeconds(), 1e-6);
        editor.stopPlayback();
    }

    @Test
    public void pinchZoomDoesNotSeekWhenTheLastFingerIsReleased() throws Exception {
        workbench.switchTab(3);
        layout(1200, 700);
        editor.shaderEditorSeek(2);
        double before = editor.shaderEditorSeconds();
        float original = scale();
        String snapshot = document.json();
        touch(10, MotionEvent.ACTION_DOWN, 100, 100);
        pinch(20, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            100, 200);
        pinch(35, MotionEvent.ACTION_MOVE, 70, 240);
        pinch(50, MotionEvent.ACTION_MOVE, 30, 300);
        pinch(65, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 30,
            300);
        touch(80, MotionEvent.ACTION_UP, 30, 100);
        assertTrue(scale() > original);
        assertEquals(before, editor.shaderEditorSeconds(), 0);
        assertEquals(snapshot, document.json());
    }

    @Test public void inwardPinchContinuesBelowOldMinimumAndReversesImmediately() throws Exception {
        workbench.switchTab(3); layout(1200,700);
        double seconds=editor.shaderEditorSeconds();
        float original=scale();
        touch(10,MotionEvent.ACTION_DOWN,100,100);
        pinch(20,MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),100,300);
        pinch(30,MotionEvent.ACTION_MOVE,150,250);
        float half=scale(); assertEquals(original*.5f,half,.01f);
        pinch(40,MotionEvent.ACTION_MOVE,175,225);
        assertEquals(original*.25f,scale(),.01f); assertTrue(scale()<half);
        float small=scale();
        pinch(50,MotionEvent.ACTION_MOVE,170,230);
        assertTrue(scale()>small);
        pinch(60,MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),170,230);
        touch(70,MotionEvent.ACTION_UP,170,100);
        assertEquals(seconds,editor.shaderEditorSeconds(),0);
    }

    @Test public void shaderScrubPreservesFractionalMilliseconds() {
        editor.shaderEditorSeek(1.234567);
        assertEquals(1.234567,editor.shaderEditorSeconds(),1e-10);
        editor.shaderBeginPan(); editor.shaderPanTo(2.345678); editor.shaderEndPan();
        assertEquals(2.345678,editor.shaderEditorSeconds(),1e-10);
    }

    @Test public void shaderPanKeepsExactEventBoundaryClock() {
        ChartDocument chart = editor.getChart();
        chart.bpmChanges.get(0).bpm = 202.99999999999997;
        chart.markEdited();
        editor.shaderEditorSeek(299.0);
        editor.shaderBeginPan();
        for (double seconds : new double[]{300.0, 0.015}) {
            editor.shaderPanTo(seconds);
            assertEquals(seconds, editor.shaderEditorSeconds(), 0.0);
        }
        editor.shaderEndPan();
        assertEquals(0.015, editor.shaderEditorSeconds(), 0.0);
    }

    @Test public void shaderPanStartsAtFirstAuthoredBpmIncludingNegativeBeat() {
        ChartDocument chart = editor.getChart();
        for (int first : new int[]{-4, 4}) {
            chart.bpmChanges.get(0).startTime = new com.xpe.mobile.model.BeatTime(first, 0, 1);
            chart.markEdited();
            editor.shaderEditorSeek(1.0);
            editor.shaderBeginPan();
            editor.shaderPanTo(0.0);
            editor.shaderEndPan();
            assertEquals(0.0, editor.shaderEditorSeconds(), 0.0);
        }
    }

    @Test
    public void loopStartsAtSelectedEffectWhenPlayheadIsOutsideItsRange() {
        workbench.switchTab(3);
        document.range(0, 3, 6);
        editor.shaderEditorSeek(0);
        buttons(shaders)
            .stream()
            .filter(b -> b instanceof CheckBox && b.getText().toString().equals("Loop"))
            .forEach(b -> ((CheckBox) b).setChecked(true));
        button("Play").performClick();
        assertTrue(editor.isPlaying());
        assertEquals(3, editor.shaderEditorSeconds(), 0);
        editor.stopPlayback();
    }

    @Test
    public void collapsingBottomPanelExpandsPreviewAndKeepsNavigationReachable() {
        workbench.switchTab(3);
        layout(800, 360);
        int height = shaders.timeline.getHeight();
        button("▼ / ▲").performClick();
        layout(800, 360);
        assertTrue(shaders.timeline.getHeight() > height);
        assertEquals(View.VISIBLE, workbench.getChildAt(2).getVisibility());
    }
}
