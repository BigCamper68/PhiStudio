package com.xpe.mobile;

import android.app.Activity;
import android.view.*;
import android.widget.*;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.model.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, qualifiers="w1200dp-h700dp-land-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class ColorFormsTest {
    Activity activity; EditorView editor; WorkbenchView workbench; EditorDialogController.Host host;
    @Before public void setup() throws Exception {
        activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.AppTheme);
        editor=new EditorView(activity);editor.setChart(ChartDocument.fromJson("{}"));
        workbench=new WorkbenchView(activity,editor,()->new TextView(activity));activity.setContentView(workbench);
        host=(EditorDialogController.Host)java.lang.reflect.Proxy.newProxyInstance(
                EditorDialogController.Host.class.getClassLoader(),new Class[]{EditorDialogController.Host.class},(p,m,a)->{
                    if(m.getName().equals("workbench"))return workbench;
                    if(m.getName().equals("editorSettings"))return editor.getSettings();
                    if(m.getName().equals("applyEditorSettings"))editor.applySettings((com.xpe.mobile.config.EditorSettings)a[0]);
                    if(m.getReturnType()==long.class)return 0L;
                    if(m.getReturnType()==boolean.class)return false;
                    if(m.getReturnType()==List.class)return Collections.emptyList();
                    return null;
                });
    }
    List<ColorPickerButton> colors(View root) {
        List<ColorPickerButton> result=new ArrayList<>();
        if(root instanceof ColorPickerButton)result.add((ColorPickerButton)root);
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)result.addAll(colors(((ViewGroup)root).getChildAt(i)));
        return result;
    }
    Button button(View root,String label) {
        if(root instanceof Button && label.contentEquals(((Button)root).getText()))return (Button)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){
            Button found=button(((ViewGroup)root).getChildAt(i),label);if(found!=null)return found;
        }
        return null;
    }
    @Test public void settingsTapToSeekCheckboxDefaultsOffAndApplies() {
        new EditorDialogController(activity,editor,host).showSettings();
        CheckBox control=(CheckBox)button(workbench,activity.getString(R.string.settings_tap_to_seek));
        assertNotNull(control);assertFalse(control.isChecked());control.setChecked(true);
        button(workbench,activity.getString(R.string.action_apply)).performClick();
        assertTrue(editor.getSettings().tapToSeek);
    }
    @Test public void settingsUsesPaletteAndCommitsRgbOnly() {
        new EditorDialogController(activity,editor,host).showSettings();
        List<ColorPickerButton> fields=colors(workbench);assertEquals(1,fields.size());
        fields.get(0).setColor(0xff123456);
        button(workbench,activity.getString(R.string.action_apply)).performClick();
        assertEquals(0x123456,editor.getSettings().lineColorRgb);
    }
    @Test public void storyboardStartAndEndColorsApplyAndUndo() {
        ExtendedLineEvents.ColorEvent event=new ExtendedLineEvents.ColorEvent();
        event.startRgb=0x123456;event.endRgb=0xabcdef;
        editor.addStoryboardEvent(StoryboardEventType.COLOR,event);
        new StoryboardDialogSection(activity,editor,host).showStoryboardEventForm(StoryboardEventType.COLOR,event,false,()->{});
        List<ColorPickerButton> fields=colors(workbench);assertEquals(2,fields.size());
        fields.get(0).setColor(0xff223344);fields.get(1).setColor(0xffaabbcc);
        button(workbench,activity.getString(R.string.action_apply)).performClick();
        assertEquals(0x223344,event.startRgb);assertEquals(0xaabbcc,event.endRgb);
        editor.performUndo();assertEquals(0x123456,event.startRgb);assertEquals(0xabcdef,event.endRgb);
    }
    @Test public void shaderRgbaUsesPaletteForConstantAndBothAnimationEndpoints() throws Exception {
        com.xpe.mobile.shadereditor.ShaderDocument doc = new com.xpe.mobile.shadereditor.ShaderDocument(
                null,editor.getChart(),name->"uniform vec4 tint; // %1,0.5,0,1%\nvoid main(){}");
        doc.add("noise",0);
        ShaderEditorView shaders=new ShaderEditorView(activity,editor,doc,new ShaderEditorView.Host(){
            public void changed(){} public void save(){} public void importShader(){}
        });
        workbench.setShaderOpener(()->workbench.showShaders(shaders));workbench.switchTab(3);
        button(shaders,"Parameters").performClick();
        workbench.measure(View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY));
        workbench.layout(0,0,800,360);
        List<ColorPickerButton> fields=colors(shaders);assertEquals(1,fields.size());
        assertTrue(fields.get(0).getWidth()>50);
        fields.get(0).performClick();
        android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertArrayEquals(new double[]{255,128,0,255},doc.components(doc.value(doc.parameters().get(0)),4),0);
        button(shaders,"Animate").performClick();fields=colors(shaders);assertEquals(2,fields.size());
        fields.get(0).setColor(0x40112233);fields.get(1).setColor(0x80445566);
        button(shaders,"Apply").performClick();
        org.json.JSONObject event=((org.json.JSONArray)doc.value(doc.parameters().get(0))).getJSONObject(0);
        assertArrayEquals(new double[]{17,34,51,64},doc.components(event.get("start"),4),0);
        assertArrayEquals(new double[]{68,85,102,128},doc.components(event.get("end"),4),0);
        doc.undo();
        assertArrayEquals(new double[]{255,128,0,255},doc.components(((org.json.JSONArray)doc.value(doc.parameters().get(0))).getJSONObject(0).get("start"),4),0);
    }

}
