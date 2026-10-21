package com.xpe.mobile.noiseeditor;

import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.xpe.mobile.R;
import com.xpe.mobile.editor.ColorPickerButton;
import com.xpe.mobile.model.NoiseField;
import com.xpe.mobile.noiseeditor.NoiseFieldEdits.Channel;
import com.xpe.mobile.shadereditor.ShaderEditorViewTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, qualifiers="w800dp-h360dp-land-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class NoiseFieldCheckpoint8Test extends ShaderEditorViewTestSupport {
    private NoiseFieldEditorView noise;
    @Before public void open() {
        workbench.switchTab(4); noise=workbench.activeNoiseEditor();
        noise.run(()->noise.document.add(1,2,false)); editor.shaderEditorSeek(1.4); layout(800,360);
    }
    private EditText input(int id) { return noise.form.findViewWithTag(id); }
    private List<Button> allButtons(View root) {
        List<Button> result=new ArrayList<>();
        if(root instanceof Button) result.add((Button)root);
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++)
            result.addAll(allButtons(((ViewGroup)root).getChildAt(i)));
        return result;
    }
    private ColorPickerButton color() {
        return (ColorPickerButton)allButtons(noise).stream().filter(b->b instanceof ColorPickerButton).findFirst().orElseThrow();
    }
    @Test public void validTypingSavesImmediatelyWithoutRecreatingInputAndCoalescesUndo() {
        NoiseField original=noise.document.field(); EditText left=input(R.string.noise_left); left.requestFocus();
        left.setText("1"); left.setText("12"); left.setText("123");
        assertEquals(1.23,noise.document.field().left,1e-7); assertSame(left,input(R.string.noise_left));
        assertFalse(noise.forms.dirty()); assertNull(noise.previewFields());
        Shadows.shadowOf(Looper.getMainLooper()).idle(); assertSame(left,input(R.string.noise_left));
        noise.undo(); assertSame(original,noise.document.field()); noise.redo(); assertEquals(1.23,noise.document.field().left,1e-7);
        EditText top=input(R.string.noise_top); top.requestFocus(); top.setText("110"); top.setText("120");
        noise.undo(); assertEquals(.75,noise.document.field().top,0); assertEquals(1.23,noise.document.field().left,1e-7);
        noise.undo(); assertSame(original,noise.document.field());
        assertTrue(allButtons(noise).stream().noneMatch(b->b.getText().toString().equals("Apply")||b.getText().toString().equals("Reset draft")));
    }
    @Test public void invalidIntermediateTextKeepsLastValidValueAndUndoRedoStaysUsable() {
        NoiseField original=noise.document.field(); EditText value=input(R.string.noise_left);
        value.setText("10"); NoiseField valid=noise.document.field(); value.setText("-");
        assertSame(valid,noise.document.field()); assertNotNull(value.getError());
        value.setText("-20"); assertEquals(-.2,noise.document.field().left,1e-7); assertNull(value.getError());
        noise.undo(); assertSame(original,noise.document.field()); noise.redo(); assertEquals(-.2,noise.document.field().left,1e-7);
        noise.undo(); input(R.string.noise_left).setText("33"); assertFalse(noise.document.canRedo());
        noise.undo(); assertSame(original,noise.document.field());
    }
    @Test public void oneInvalidInputDoesNotPreventOtherPropertiesFromSaving() {
        input(R.string.noise_left).setText("10"); input(R.string.noise_left).setText("-");
        input(R.string.noise_top).setText("120");
        assertEquals(.1,noise.document.field().left,1e-7);assertEquals(1.2,noise.document.field().top,1e-7);
        assertTrue(noise.forms.dirty());assertNotNull(input(R.string.noise_left).getError());
        noise.undo();assertEquals(.1,noise.document.field().left,1e-7);assertEquals(.75,noise.document.field().top,0);
        noise.redo();assertEquals(1.2,noise.document.field().top,1e-7);
    }
    @Test public void everyAnimationChannelAutosavesValuesAndRestoresThroughUndoRedo() throws Exception {
        for(Channel channel:Channel.values()) {
            noise.selectChannel(channel); noise.run(()->noise.document.addKey(1));
            NoiseField original=noise.document.field();
            EditText value=input(channel==Channel.MOVE?R.string.noise_x:channel==Channel.SCALE?R.string.noise_scale_x:R.string.noise_angle);
            value.setText("20"); value.setText("240");
            JSONObject key=NoiseFieldEdits.key(noise.document.field(),channel,0);
            assertEquals(channel==Channel.MOVE?2.4:240,channel==Channel.ROTATE?key.getDouble("rotation"):key.getJSONObject(channel.vector).getDouble("x"),0);
            noise.undo(); assertSame(original,noise.document.field()); noise.redo(); assertFalse(noise.forms.dirty());
        }
    }
    @Test public void fieldSwitchAndOverviewPreserveClockAndRawOrderAndExposeEveryOverlap() throws Exception {
        for(int i=0;i<4;i++) noise.run(()->noise.document.add(1,2,false));
        List<NoiseField> original=new ArrayList<>(noise.document.chart.noiseFields); double clock=noise.time();
        for(int i=0;i<original.size();i++) { noise.selectField(i); assertEquals(clock,noise.time(),0); }
        List<RectF> focused=blocks(); assertEquals(1,focused.size());
        noise.overview=true; noise.timeline.reveal(0); List<RectF> overview=blocks();
        assertTrue(overview.size()>1);
        for(int i=0;i<overview.size();i++) for(int j=i+1;j<overview.size();j++)
            assertFalse(RectF.intersects(overview.get(i),overview.get(j)));
        assertEquals(original,noise.document.chart.noiseFields); assertEquals(clock,noise.time(),0);
    }
    @Test public void emptyOverviewRemainsUsableAfterDeletingTheLastField() {
        noise.overview=true; noise.run(noise.document::delete); noise.timeline.draw(new Canvas());
        assertEquals(0,noise.document.count()); noise.undo(); noise.timeline.draw(new Canvas());
        assertEquals(1,noise.document.count());
    }
    private List<RectF> blocks() {
        List<RectF> result=new ArrayList<>();noise.timeline.draw(new Canvas(){
            @Override public void drawRoundRect(RectF rect,float x,float y,Paint paint){
                if(paint.getColor()==0xff2c8d81)result.add(new RectF(rect));
            }
        });return result;
    }
    @Test public void footerUsesBlankSpaceWithoutCoveringPropertiesAndModesStayEqual() {
        for(int[] size:new int[][]{{640,360},{800,240},{1200,700}}) {
            layout(size[0],size[1]); View footer=workbench.getChildAt(2);
            assertEquals(34,footer.getHeight()); assertEquals(34,noise.navigation.getHeight());
            assertEquals(noise.navigation.getRight(),footer.getLeft());assertEquals(footer.getTop(),noise.navigation.getTop());
            assertEquals(noise.bottom.getBottom(),noise.navigation.getTop());
            ViewGroup row=(ViewGroup)((ViewGroup)footer).getChildAt(0);assertEquals(row.getChildAt(0).getWidth(),row.getChildAt(1).getWidth(),1);
        }
        workbench.switchTab(0); assertEquals(View.GONE,noise.navigation.getVisibility());
    }
    @Test public void livePaletteSavesWithoutApplyGroupsOneDialogAndDefaultRemovesExtension() throws Exception {
        NoiseField original=noise.document.field(); color().performClick();
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("OK",dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString().toUpperCase(java.util.Locale.ROOT));
        assertFalse(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getVisibility()==View.VISIBLE);
        List<Button> choices=allButtons(dialog.getWindow().getDecorView());
        choices.stream().filter(b->"Preset #40D060".equals(b.getContentDescription())).findFirst().orElseThrow().performClick();
        assertEquals(0xff40d060,noise.document.field().color);
        choices.stream().filter(b->"Preset #40CFFF".equals(b.getContentDescription())).findFirst().orElseThrow().performClick();
        assertEquals(0xff40cfff,noise.document.field().color);assertEquals(3,noise.document.field().toJson().getJSONArray(NoiseField.COLOR).length());
        dialog.dismiss();noise.undo();assertSame(original,noise.document.field());noise.redo();assertEquals(0xff40cfff,noise.document.field().color);
        allButtons(noise).stream().filter(b->b.getText().toString().equals("Native red")).findFirst().orElseThrow().performClick();
        assertEquals(NoiseField.DEFAULT_COLOR,noise.document.field().color);assertFalse(noise.document.field().toJson().has(NoiseField.COLOR));
        noise.undo();assertEquals(0xff40cfff,noise.document.field().color);
    }
}
