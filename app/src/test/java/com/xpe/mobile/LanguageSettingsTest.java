package com.xpe.mobile;

import com.xpe.mobile.i18n.LanguageController;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.model.ChartDocument;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,32,33})
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class LanguageSettingsTest {
    private MainActivity activity;
    @Before public void setup() throws Exception {
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.AppTheme);
        activity.languageController.select("en");
        activity.editorView=new EditorView(activity);activity.editorView.setChart(ChartDocument.fromJson("{}"));
        activity.workbench=new WorkbenchView(activity,activity.editorView,activity::createWorkbenchMenu);
        activity.editorSettings=new EditorSettings();activity.setContentView(activity.workbench);
        activity.editorDialogs=new EditorDialogController(activity,activity.editorView,new EditorDialogController.Host(){
            public LanguageController languageController(){return activity.languageController;}
            public WorkbenchView workbench(){return activity.workbench;}
            public EditorSettings editorSettings(){return activity.editorSettings;}
            public void applyEditorSettings(EditorSettings settings){fail("Selecting a language must not apply editor settings");}
            public long packageOffsetMs(){return 0;}public long audioDurationMs(){return 0;}
            public void onLineAppearanceChanged(){}public List<String> hitSoundChoices(){return Collections.emptyList();}
            public boolean isHitSoundAvailable(String reference){return false;}public void showMessage(String message){}
        });
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @After public void teardown(){activity.languageController.select("");activity.mainVideoExport.close();}
    private <T> List<T> views(View root,Class<T> type){
        List<T> found=new ArrayList<>();if(type.isInstance(root))found.add(type.cast(root));
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)found.addAll(views(((ViewGroup)root).getChildAt(i),type));
        return found;
    }
    @Test public void choosingLanguageInSettingsPreservesUnappliedDraftAndEditorState() {
        activity.editorDialogs.showSettings();Shadows.shadowOf(Looper.getMainLooper()).idle();
        EditText volume=views(activity.workbench,EditText.class).get(0);volume.setText("0.321");volume.setSelection(3);
        Spinner language=views(activity.workbench,Spinner.class).get(0);
        double saved=activity.editorSettings.musicVolume;ChartDocument chart=activity.editorView.getChart();
        language.setSelection(Arrays.asList(activity.languageController.codes()).indexOf("zh-CN")+1);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("zh-CN",activity.languageController.selection());assertEquals("0.321",volume.getText().toString());assertEquals(3,volume.getSelectionStart());
        assertEquals(saved,activity.editorSettings.musicVolume,0);assertSame(chart,activity.editorView.getChart());
        assertTrue(views(activity.workbench,TextView.class).stream().anyMatch(v->v.getText().toString().equals("应用")));
        assertEquals("跟随系统",language.getAdapter().getItem(0).toString());
        assertTrue(views(activity.workbench,CheckBox.class).stream()
                .anyMatch(v->v.getText().toString().equals("点击谱面跳转时间")));
        assertTrue(views(activity.workbench,TextView.class).stream()
                .anyMatch(v->v.getText().toString().startsWith("外接键盘快捷键")));
    }
    @Test public void existingNativeDialogAndVideoDraftUpdateInPlace() {
        new VideoExportDialog(activity,false,3000).show(options->fail("Language switch cannot start export"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
        EditText width=views(dialog.getWindow().getDecorView(),EditText.class).get(0);width.setText("1440");width.setSelection(2);
        List<CheckBox> checks=views(dialog.getWindow().getDecorView(),CheckBox.class);checks.get(1).setChecked(false);
        activity.languageController.select("zh-CN");Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertSame(dialog,ShadowAlertDialog.getLatestAlertDialog());assertTrue(dialog.isShowing());assertEquals("1440",width.getText().toString());assertEquals(2,width.getSelectionStart());
        assertFalse(checks.get(1).isChecked());assertEquals("渲染 MP4",dialog.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
        assertEquals("取消",dialog.getButton(DialogInterface.BUTTON_NEGATIVE).getText().toString());
        assertTrue(views(dialog.getWindow().getDecorView(),TextView.class).stream()
                .anyMatch(v->v.getText().toString().startsWith("使用 PhiStudio 渲染")));
        assertTrue(views(dialog.getWindow().getDecorView(),TextView.class).stream()
                .anyMatch(v->v.getText().toString().startsWith("码率越高")));
        dialog.dismiss();
    }

    @Test public void videoValidationDetailsRelabelWithTheOpenDialogAndKeepInvalidDraft() {
        new VideoExportDialog(activity,true,3000).show(options->fail("Invalid video settings cannot submit"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        View root = dialog.getWindow().getDecorView();
        List<EditText> fields = views(root,EditText.class);
        fields.get(0).setText("1441");
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        assertTrue(views(root,TextView.class).stream().anyMatch(v->v.getText().toString()
                .equals("Check settings: Resolution must be even, between 256×256 and 3840×2160")));
        activity.languageController.select("zh-CN");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("1441",fields.get(0).getText().toString());
        assertTrue(dialog.isShowing());
        assertTrue(views(root,TextView.class).stream().anyMatch(v->v.getText().toString()
                .equals("请检查设置：宽度和高度必须为偶数，分辨率范围为 256×256 至 3840×2160")));
        fields.get(0).setText("1440"); fields.get(3).setText("3"); fields.get(4).setText("2");
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        assertTrue(views(root,TextView.class).stream().anyMatch(v->v.getText().toString()
                .equals("请检查设置：结束时间必须晚于起始时间，且不能超过 24 小时")));
        assertTrue(dialog.isShowing()); dialog.dismiss();
    }
}
