package com.xpe.mobile.noiseeditor;

import android.graphics.*;
import android.view.*;
import android.widget.*;
import com.xpe.mobile.R;
import com.xpe.mobile.app.AppUiTestAccess;
import com.xpe.mobile.app.MainActivity;
import com.xpe.mobile.editor.EditorTimelineMetrics;
import com.xpe.mobile.editor.LanguageSwitchTest;
import com.xpe.mobile.editor.WorkbenchView;
import com.xpe.mobile.model.*;
import com.xpe.mobile.noiseeditor.NoiseFieldEdits.Channel;
import com.xpe.mobile.shadereditor.ShaderEditorViewTestSupport;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowPopupMenu;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w800dp-h360dp-land-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class NoiseFieldEditorViewTest extends ShaderEditorViewTestSupport {
    NoiseFieldEditorView noise;
    @Before public void openNoise() {
        workbench.switchTab(4); noise = workbench.activeNoiseEditor();
        noise.run(() -> noise.document.add(1, 1, false));
        editor.scaleShaderTimeline(40/editor.timelineScale());editor.shaderEditorSeek(1);layout(800,360);
    }
    EditText input(int id) { return noise.form.findViewWithTag(id); }
    Button noiseButton(int id) { return noiseButton(noise,activity.getString(id)); }
    Button noiseButton(View view,String text) {
        if(view instanceof Button&&((Button)view).getText().toString().equals(text))return (Button)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            Button result=noiseButton(((ViewGroup)view).getChildAt(i),text);if(result!=null)return result;
        }
        return null;
    }
    void touch(View view,int action,float x,float y) {
        MotionEvent event=MotionEvent.obtain(0,20,action,x,y,0);view.dispatchTouchEvent(event);event.recycle();
    }
    Rect rect(View view){return new Rect(view.getLeft(),view.getTop(),view.getRight(),view.getBottom());}
    RectF drawBlock() {
        List<RectF> blocks=new ArrayList<>();
        noise.timeline.draw(new Canvas() {
            @Override public void drawRoundRect(RectF rect,float rx,float ry,Paint paint) {
                if(paint.getColor()==0xff2c8d81)blocks.add(new RectF(rect));
            }
        });
        assertFalse(blocks.isEmpty());return blocks.get(0);
    }
    NoiseFieldTimeline.Hit timelineHit(Channel channel, int phase) throws Exception {
        noise.timeline.draw(new Canvas());
        var field = NoiseFieldTimeline.class.getDeclaredField("hits"); field.setAccessible(true);
        @SuppressWarnings("unchecked") List<NoiseFieldTimeline.Hit> hits = (List<NoiseFieldTimeline.Hit>) field.get(noise.timeline);
        return hits.stream().filter(hit -> hit.phase == phase && hit.channel == channel).findFirst().orElseThrow();
    }
    @Test public void previewTimelineAndPropertiesHaveExactlyTheShaderEditorLayout() {
        for(int[] size:new int[][]{{800,360},{1200,700},{1920,1080},{800,240}}) {
            workbench.switchTab(3);layout(size[0],size[1]);
            Rect preview=rect(shaders.getChildAt(1)),timeline=rect(shaderTimeline()),bottom=rect(shaderBottom()),toolbar=rect(shaderToolbar());
            workbench.switchTab(4);layout(size[0],size[1]);
            assertEquals(preview,rect(noise.preview));assertEquals(timeline,rect(noise.timeline));
            assertEquals(bottom,rect(noise.bottom));assertEquals(toolbar,rect(noise.toolbar));
            assertEquals(noise.preview.getRight(),noise.timeline.getLeft());assertEquals(noise.timeline.getBottom(),noise.bottom.getTop());
            assertEquals(size[0],noise.bottom.getWidth());assertEquals(34,workbench.getChildAt(2).getHeight());
        }
    }
    @Test public void buttonsAndInputRowsMatchExistingShaderControls() {
        assertEquals(shaderPlay().getLayoutParams().width,noise.play.getLayoutParams().width);
        assertEquals(shaderPlay().getLayoutParams().height,noise.play.getLayoutParams().height);
        assertEquals(shaderPlay().getTextSize(),noise.play.getTextSize(),0);
        assertEquals(38,noise.play.getHeight());assertEquals(58,noise.play.getWidth());
        assertEquals(32,noise.delete.getHeight());assertEquals(40,input(R.string.noise_appear).getHeight());
        assertEquals(12,input(R.string.noise_appear).getTextSize(),0);
        assertEquals(88,noise.bottom.getHeight());
        noiseButton(R.string.shader_expand).performClick();layout(800,360);assertTrue(noise.bottom.getHeight()>88);
        noiseButton(R.string.shader_compact).performClick();layout(800,360);assertEquals(88,noise.bottom.getHeight());
    }
    @Test public void completedToolbarUsesTheSameSeekTrackWidthAsShaders() {
        for(int width:new int[]{640,800,1200,1920}) {
            workbench.switchTab(3);layout(width,700);int expected=shaderSeek().getWidth();
            workbench.switchTab(4);layout(width,700);assertEquals(expected,noise.playbackSeek.getWidth());
            assertEquals(noise.toolbar.getWidth(),noise.playbackSeek.getRight());
            assertEquals(48,noise.toolbar.getHeight());assertNotNull(noiseButton(R.string.shader_fx_beats));
        }
    }
    @Test public void twoEqualFooterButtonsKeepAllFourModesAndMenuReturnsToNoise() {
        LinearLayout row=(LinearLayout)((ViewGroup)workbench.getChildAt(2)).getChildAt(0);
        assertEquals(2,row.getChildCount());assertEquals(row.getChildAt(0).getWidth(),row.getChildAt(1).getWidth(),1);
        assertEquals(34,row.getChildAt(0).getHeight());assertEquals("Noise Field ▾",((Button)row.getChildAt(0)).getText().toString());
        row.getChildAt(0).performClick();PopupMenu popup=ShadowPopupMenu.getLatestPopupMenu();
        assertEquals(4,popup.getMenu().size());String[] labels={"Event","Storyboard","Shaders","Noise Field"};
        for(int i=0;i<4;i++)assertEquals(labels[i],popup.getMenu().getItem(i).getTitle().toString());
        assertTrue(popup.getMenu().findItem(4).isChecked());popup.dismiss();
        row.getChildAt(1).performClick();assertEquals(2,editor.getWorkbenchTab());assertEquals(View.GONE,noise.getVisibility());
        assertTrue(workbench.back());assertSame(noise,workbench.activeNoiseEditor());
    }
    @Test public void verticalGridUsesTheCommonBaselineBeatSpacingAndSelectionBlocks() {
        RectF block=drawBlock();float scale=editor.timelineScale();
        assertEquals(EditorTimelineMetrics.baselineY(noise.timeline.getHeight(),noise.density),block.bottom,.01);
        assertEquals((noise.axis(2)-noise.axis(1))*scale,block.height(),.01);
        assertTrue(noise.timeline.getLeft()>=noise.preview.getRight());
    }
    @Test public void previewPanningHasTheExactSameDirectionSpeedAndZoomAsShaders() {
        var settings=editor.getSettings();settings.timelineScrollSpeed=2;editor.applySettings(settings);
        String before=noise.document.field().toJson().toString();
        workbench.switchTab(3);layout(800,360);editor.shaderEditorSeek(1);
        View shaderPreview=shaders.getChildAt(1);float scale=editor.timelineScale();
        touch(shaderPreview,MotionEvent.ACTION_DOWN,100,50);touch(shaderPreview,MotionEvent.ACTION_MOVE,100,90);touch(shaderPreview,MotionEvent.ACTION_UP,100,90);
        double expected=editor.shaderEditorSeconds();
        workbench.switchTab(4);layout(800,360);editor.shaderEditorSeek(1);
        touch(noise.preview,MotionEvent.ACTION_DOWN,100,50);touch(noise.preview,MotionEvent.ACTION_MOVE,100,90);touch(noise.preview,MotionEvent.ACTION_UP,100,90);
        assertEquals(expected,editor.shaderEditorSeconds(),1e-9);assertEquals(scale,editor.timelineScale(),0);
        assertEquals(before,noise.document.field().toJson().toString());
    }
    @Test public void gpuPreviewUsesTheSamePanHandlerAsCanvasPreview() {
        float scale=editor.timelineScale();editor.shaderEditorSeek(1);
        noise.gpu.setVisibility(View.VISIBLE);
        touch(noise.gpu,MotionEvent.ACTION_DOWN,100,50);touch(noise.gpu,MotionEvent.ACTION_MOVE,100,90);touch(noise.gpu,MotionEvent.ACTION_UP,100,90);
        assertEquals(noise.seconds(noise.axis(1)+40/scale),editor.shaderEditorSeconds(),1e-9);
    }
    @Test public void blockBodiesPanAndSelectedHandlesEditOneUndoStep() {
        RectF block=drawBlock();NoiseField original=noise.document.field();
        touch(noise.timeline,MotionEvent.ACTION_DOWN,block.centerX(),block.centerY());
        touch(noise.timeline,MotionEvent.ACTION_MOVE,block.centerX(),block.centerY()+30);
        touch(noise.timeline,MotionEvent.ACTION_UP,block.centerX(),block.centerY()+30);
        assertSame(original,noise.document.field());assertTrue(editor.shaderEditorSeconds()>1);
        editor.shaderEditorSeek(1);block=drawBlock();long revision=editor.getChart().revision();
        float x=block.centerX(),y=noise.timeline.y(original.disappear);
        touch(noise.timeline,MotionEvent.ACTION_DOWN,x,y);touch(noise.timeline,MotionEvent.ACTION_MOVE,x,y-20);
        drawBlock(); // Redrawing the draft must not accumulate the original-time delta.
        assertSame(original,noise.document.field());
        touch(noise.timeline,MotionEvent.ACTION_MOVE,x,y-40);touch(noise.timeline,MotionEvent.ACTION_UP,x,y-40);
        assertEquals(2.5,noise.document.field().disappear,1e-9);assertEquals(revision+1,editor.getChart().revision());
        noise.undo();assertSame(original,noise.document.field());
    }
    @Test public void timelineEmptyTapClearsSelectionAndSeeksOnlyWhenEnabled() {
        noise.overview=true; drawBlock();double time=noise.time();
        touch(noise.timeline,MotionEvent.ACTION_DOWN,5,80);touch(noise.timeline,MotionEvent.ACTION_UP,5,80);
        assertEquals(-1,noise.document.selected);assertEquals(time,noise.time(),0);
        touch(noise.timeline,MotionEvent.ACTION_DOWN,5,80);touch(noise.timeline,MotionEvent.ACTION_UP,5,80);
        assertEquals(time,noise.time(),0);
        var settings=editor.getSettings();settings.tapToSeek=true;editor.applySettings(settings);
        touch(noise.timeline,MotionEvent.ACTION_DOWN,5,80);touch(noise.timeline,MotionEvent.ACTION_UP,5,80);
        assertNotEquals(time,noise.time(),0);
    }
    @Test public void previewAndTimelineHoldPauseThenResumePlayback() throws Exception {
        long[] calls={0,0};
        editor.setCallback((com.xpe.mobile.editor.EditorView.Callback)java.lang.reflect.Proxy.newProxyInstance(
            com.xpe.mobile.editor.EditorView.Callback.class.getClassLoader(),new Class<?>[]{com.xpe.mobile.editor.EditorView.Callback.class},(p,m,a)->{
                if(m.getName().equals("isAudioReady")||m.getName().equals("isAudioPlaying"))return true;
                if(m.getName().equals("pauseAudio"))calls[0]++;
                if(m.getName().equals("startAudio"))calls[1]++;
                if(m.getName().equals("audioDurationMs"))return 10000L;
                if(m.getReturnType()==boolean.class)return false;if(m.getReturnType()==long.class)return 0L;return null;
            }));
        editor.shaderEditorPlay();
        for(View view:new View[]{noise.preview,noise.gpu,noise.timeline}) {
            view.setVisibility(View.VISIBLE);editor.shaderEditorSeek(1.234567);long paused=calls[0],resumed=calls[1];
            touch(view,MotionEvent.ACTION_DOWN,5,80);assertEquals(paused+1,calls[0]);
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(500));
            noise.preview.draw(new Canvas());assertEquals(1.234567,noise.time(),1e-9);
            touch(view,MotionEvent.ACTION_UP,5,80);assertEquals(resumed+1,calls[1]);assertTrue(editor.isPlaying());
        }
        editor.stopPlayback();
    }
    @Test public void canceledResizeAndSecondFingerDiscardDraftAndPinchUsesCommonScale() {
        RectF block=drawBlock();NoiseField original=noise.document.field();long revision=editor.getChart().revision();
        float x=block.centerX(),y=noise.timeline.y(original.disappear);
        touch(noise.timeline,MotionEvent.ACTION_DOWN,x,y);touch(noise.timeline,MotionEvent.ACTION_MOVE,x,y-20);
        assertNotSame(original,noise.displayedField());
        touch(noise.timeline,MotionEvent.ACTION_CANCEL,x,y-20);assertSame(original,noise.displayedField());
        drawBlock();touch(noise.timeline,MotionEvent.ACTION_DOWN,x,y);touch(noise.timeline,MotionEvent.ACTION_MOVE,x,y-20);
        multi(MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),100);
        float scale=editor.timelineScale();multi(MotionEvent.ACTION_MOVE,150);
        assertEquals(scale*1.5,editor.timelineScale(),.01);assertSame(original,noise.displayedField());
        touch(noise.timeline,MotionEvent.ACTION_UP,x,y-40);assertSame(original,noise.document.field());assertEquals(revision,editor.getChart().revision());
    }
    void multi(int action,float span) {
        MotionEvent.PointerProperties a=new MotionEvent.PointerProperties();a.id=0;
        MotionEvent.PointerProperties b=new MotionEvent.PointerProperties();b.id=1;
        MotionEvent.PointerCoords ca=new MotionEvent.PointerCoords();ca.x=80;ca.y=80;
        MotionEvent.PointerCoords cb=new MotionEvent.PointerCoords();cb.x=80+span;cb.y=80;
        MotionEvent e=MotionEvent.obtain(0,40,action,2,new MotionEvent.PointerProperties[]{a,b},new MotionEvent.PointerCoords[]{ca,cb},0,0,1,1,0,0,0,0);
        noise.timeline.dispatchTouchEvent(e);e.recycle();
    }
    @Test public void formChangesAutosaveWithoutRoundingOtherRawValues() throws Exception {
        JSONObject raw=NoiseFieldEdits.copy(noise.document.field().toJson()).put("futureField",true);
        raw.getJSONObject("bottomLeftPercentage").put("x",.123456789012345).put("futureVector",9);
        noise.document.replace(noise.document.field(),NoiseField.fromJson(raw));noise.rebuild();
        NoiseField original=noise.document.field();long revision=editor.getChart().revision();
        input(R.string.noise_top).setText("125");assertFalse(noise.forms.dirty());assertNotSame(original,noise.document.field());
        assertEquals(1.25,noise.document.field().top,0);assertNull(noise.previewFields());assertEquals(revision+1,editor.getChart().revision());
        assertTrue(noise.validateInputs());JSONObject result=noise.document.field().toJson();
        assertEquals(.123456789012345,result.getJSONObject("bottomLeftPercentage").getDouble("x"),0);
        assertEquals(9,result.getJSONObject("bottomLeftPercentage").getInt("futureVector"));assertTrue(result.getBoolean("futureField"));
        noise.undo();assertSame(original,noise.document.field());
    }
    @Test public void modeSwitchPreservesInvalidDraftJustLikeShaders() {
        NoiseField original=noise.document.field();input(R.string.noise_appear).setText("20");
        assertFalse(noise.validateInputs());workbench.showMenu();assertNull(workbench.activeNoiseEditor());
        workbench.switchTab(4);assertSame(noise,workbench.activeNoiseEditor());
        assertEquals("20",input(R.string.noise_appear).getText().toString());assertSame(original,noise.document.field());
        input(R.string.noise_appear).setText("2");assertFalse(noise.forms.dirty());workbench.showMenu();assertNull(workbench.activeNoiseEditor());
    }
    @Test public void animationUsesTheExistingEasingWidgetWithAllFifteenNativeCurves() throws Exception {
        for(Channel channel:Channel.values()) {
            noise.selectChannel(channel);noise.run(()->noise.document.addKey(1));
            input(R.string.noise_time).setText("-0.25");
            if(channel==Channel.MOVE){input(R.string.noise_x).setText("180");input(R.string.noise_y).setText("-25");}
            if(channel==Channel.SCALE){input(R.string.noise_scale_x).setText("0");input(R.string.noise_scale_y).setText("-2");}
            if(channel==Channel.ROTATE)input(R.string.noise_angle).setText("720");
            if(channel.anchored()){input(R.string.noise_anchor_x).setText("-50");input(R.string.noise_anchor_y).setText("200");}
            List<Spinner> spinners=new ArrayList<>();collectSpinners(noise,spinners);assertFalse(spinners.isEmpty());
            for(Spinner spinner:spinners){assertEquals(15,spinner.getAdapter().getCount());spinner.setSelection(14);}
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue(noise.validateInputs());
            JSONObject key=NoiseFieldEdits.key(noise.document.field(),channel,0);
            assertEquals(-.125,key.getDouble("time"),0);assertEquals(14,key.getInt(channel==Channel.ROTATE?"easeType":"easeTypeX"));
            if(channel.anchored())assertEquals(-.5,key.getJSONObject("anchor").getDouble("x"),0);
        }
        assertEquals(0,noise.document.field().scale.get(0).x,0);assertEquals(-2,noise.document.field().scale.get(0).y,0);
    }
    @Test public void animationKeyHandlesPreserveRawIndicesAndShareUndo() throws Exception {
        noise.selectChannel(Channel.MOVE);noise.run(()->noise.document.addKey(1));noise.run(()->noise.document.addKey(2));
        noise.document.selectedKey=0;noise.rebuild();editor.shaderEditorSeek(1);drawBlockForKeys();
        NoiseField original=noise.document.field();float x=timelineHit(Channel.MOVE, 2).rect.centerX(),y=noise.timeline.y(2);
        touch(noise.timeline,MotionEvent.ACTION_DOWN,x,y);touch(noise.timeline,MotionEvent.ACTION_MOVE,x,y-40);touch(noise.timeline,MotionEvent.ACTION_UP,x,y-40);
        assertEquals(1,NoiseFieldEdits.key(noise.document.field(),Channel.MOVE,0).getDouble("time"),0);
        assertEquals(2.5,NoiseFieldEdits.key(noise.document.field(),Channel.MOVE,1).getDouble("time"),0);
        noise.undo();assertSame(original,noise.document.field());assertEquals(0,noise.document.selectedKey);
    }
    void drawBlockForKeys(){noise.timeline.draw(new Canvas());}
    @Test public void resizeBackgroundAndModeChangesCancelTimelineDrafts() {
        RectF block=drawBlock();NoiseField original=noise.document.field();
        touch(noise.timeline,MotionEvent.ACTION_DOWN,block.centerX(),block.top);touch(noise.timeline,MotionEvent.ACTION_MOVE,block.centerX(),block.top-20);
        workbench.pauseModes();assertSame(original,noise.displayedField());
        input(R.string.noise_name).setText("Keep draft");EditText same=input(R.string.noise_name);same.requestFocus();
        for(int height:new int[]{700,240,360}){layout(800,height);assertSame(same,input(R.string.noise_name));assertEquals("Keep draft",same.getText().toString());}
        noise.relocalizeUi();assertSame(same,input(R.string.noise_name));assertEquals("Keep draft",same.getText().toString());
        workbench.switchTab(0);assertEquals("Keep draft",noise.document.name(0));
        workbench.switchTab(4);assertEquals("Keep draft",input(R.string.noise_name).getText().toString());
    }
    @Test public void changingChartsDisposesOldDrafts() throws Exception {
        NoiseField original=noise.document.field();input(R.string.noise_left).setText("90");
        editor.setChart(ChartDocument.fromJson("{}"));Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNull(workbench.activeNoiseEditor());assertNull(noise.getParent());assertFalse(noise.validateInputs());
        assertTrue(editor.getChart().noiseFields.isEmpty());assertEquals(.9,noise.document.chart.noiseFields.get(0).left,1e-7);
    }
    @Test public void defaultUnitAndLoopControlsAreTheSameAsShaders() {
        assertTrue(noise.beats);assertTrue(shaderBeats());assertFalse(noise.loopControl.isChecked());
        noise.loopControl.performClick();assertTrue(noise.loop);
        noiseButton(R.string.shader_fx_beats).performClick();assertFalse(noise.beats);assertNotNull(noiseButton(R.string.shader_seconds));
    }
    @Test public void actualLanguageSwitchPreservesTheCompactDraft() throws Exception {
        var a=Robolectric.buildActivity(LanguageSwitchTest.ResourceActivity.class).setup().get();a.setTheme(R.style.AppTheme);a.languages.select("en");
        var e=new com.xpe.mobile.editor.EditorView(a);e.setChart(ChartDocument.fromJson("{}"));
        var w=new WorkbenchView(a,e,()->new TextView(a));a.setContentView(w);w.switchTab(4);var n=w.activeNoiseEditor();n.run(()->n.document.add(0,4,false));
        EditText field=n.form.findViewWithTag(R.string.noise_name);field.setText("Draft");field.requestFocus();
        a.languages.select("zh-CN");w.relocalizeUi();assertSame(field,n.form.findViewWithTag(R.string.noise_name));assertEquals("Draft",field.getText().toString());
        assertEquals("删除区域",n.delete.getText().toString());a.languages.select("en");a.finish();
    }
    @Test public void firstPropertyRowIsVisibleAndBeatFractionsUseTheExistingNotation() {
        for(int id:new int[]{R.string.noise_appear,R.string.noise_enable,R.string.noise_disable,R.string.noise_disappear}) {
            Rect visible=new Rect();assertTrue(input(id).getGlobalVisibleRect(visible));assertEquals(40,visible.height());assertTrue(visible.width()>40);
        }
        input(R.string.noise_enable).setText("2:1/2");assertTrue(noise.validateInputs());assertEquals(1.25,noise.document.field().enable,0);
    }
    @Test public void activeRangeHandlesAndEventSpeedSettingMatchTheNativeResize() throws Exception {
        drawBlock();NoiseField original=noise.document.field();
        var settings=editor.getSettings();settings.eventScrollSpeed=2;editor.applySettings(settings);
        float x=timelineHit(null, 1).rect.centerX(),y=noise.timeline.y(original.disable);
        touch(noise.timeline,MotionEvent.ACTION_DOWN,x,y);touch(noise.timeline,MotionEvent.ACTION_MOVE,x,y-40);touch(noise.timeline,MotionEvent.ACTION_UP,x,y-40);
        assertEquals(3,noise.document.field().disable,1e-9);assertEquals(2,noise.document.field().disappear,0);
        noise.undo();assertSame(original,noise.document.field());
    }
    @Test public void undoRedoTracksEachInputSession() {
        input(R.string.noise_left).setText("10"); NoiseField afterLeft = noise.document.field();
        input(R.string.noise_top).setText("150");noise.undo();assertSame(afterLeft,noise.document.field());assertFalse(noise.forms.dirty());
        noise.redo();assertEquals(.1,noise.document.field().left,1e-7);assertEquals(1.5,noise.document.field().top,0);
    }
    @Test public void invalidInputDoesNotBlockNormalTimelinePanning() {
        input(R.string.noise_appear).setText("invalid");double before=noise.time();
        touch(noise.timeline,MotionEvent.ACTION_DOWN,5,50);touch(noise.timeline,MotionEvent.ACTION_MOVE,5,90);touch(noise.timeline,MotionEvent.ACTION_UP,5,90);
        assertTrue(noise.time()>before);assertEquals("invalid",input(R.string.noise_appear).getText().toString());assertTrue(noise.forms.dirty());
    }
    @Test public void backFromNoiseRestoresThePreviousWorkbenchTabLikeShaders() {
        workbench.switchTab(1);workbench.switchTab(4);assertTrue(workbench.back());assertEquals(1,editor.getWorkbenchTab());
    }
    @Test public void negativeBeatDisplayAndBpmChangesRetainNativeSecondValues() throws Exception {
        editor.setChart(ChartDocument.fromJson("{\"BPMList\":[{\"bpm\":120,\"startTime\":[0,0,1]},{\"bpm\":180,\"startTime\":[4,0,1]}]}"));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();workbench.switchTab(4);noise=workbench.activeNoiseEditor();
        noise.run(()->noise.document.add(-2,8,false));
        assertEquals(-4,Double.parseDouble(input(R.string.noise_appear).getText().toString()),0);
        input(R.string.noise_disable).setText("7");assertTrue(noise.validateInputs());assertEquals(3,noise.document.field().disable,1e-8);
        for(double seconds:new double[]{-2,-.2,0,1,2,3,5})assertEquals(seconds,noise.seconds(noise.axis(seconds)),1e-8);
    }
    @Test public void hardwareDeleteAndUndoOperateOnTheActiveNoiseDocument() throws Exception {
        MainActivity a=Robolectric.buildActivity(MainActivity.class).get();a.setTheme(R.style.AppTheme);
        WorkbenchView w=AppUiTestAccess.prepareEmptyWorkbench(a);w.switchTab(4);
        NoiseFieldEditorView n=w.activeNoiseEditor();n.run(()->n.document.add(0,4,false));
        assertTrue(a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_FORWARD_DEL)));assertEquals(0,n.document.count());
        assertTrue(a.dispatchKeyEvent(new KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_Z,0,KeyEvent.META_CTRL_ON)));assertEquals(1,n.document.count());
    }

    @Test public void handleEditingKeepsLastValidGeometryWhenInputIsInvalid() {
        input(R.string.noise_disappear).setText("invalid");assertTrue(noise.forms.dirty());
        RectF block=drawBlock();assertEquals(80,block.height(),.01);
        float x=block.centerX(),y=noise.timeline.y(2);
        touch(noise.timeline,MotionEvent.ACTION_DOWN,x,y);touch(noise.timeline,MotionEvent.ACTION_MOVE,x,y-40);touch(noise.timeline,MotionEvent.ACTION_UP,x,y-40);
        assertEquals(2.5,noise.document.field().disappear,0);assertFalse(noise.forms.dirty());
    }

}
