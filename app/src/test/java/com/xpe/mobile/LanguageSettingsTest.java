package com.xpe.mobile;

import com.xpe.mobile.i18n.LanguageController;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.widget.*;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.editor.EasingPreviewView;
import com.xpe.mobile.playtest.PlaytestClock;
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
    @After public void teardown(){activity.mainPlaytest.close();activity.languageController.select("en");activity.mainVideoExport.close();}
    private <T> List<T> views(View root,Class<T> type){
        List<T> found=new ArrayList<>();if(type.isInstance(root))found.add(type.cast(root));
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)found.addAll(views(((ViewGroup)root).getChildAt(i),type));
        return found;
    }
    private Button button(View root, int label) {
        return views(root,Button.class).stream().filter(v->v.getText().toString().equals(activity.getString(label)))
                .findFirst().orElseThrow();
    }
    private <T extends View> T labeled(View root, int resource, Class<T> type) {
        for (TextView label : views(root,TextView.class)) {
            if (!label.getText().toString().equals(activity.getString(resource))) continue;
            ViewGroup parent = (ViewGroup) label.getParent();
            int index = parent.indexOfChild(label) + 1;
            if (index < parent.getChildCount() && type.isInstance(parent.getChildAt(index)))
                return type.cast(parent.getChildAt(index));
        }
        throw new AssertionError("Missing field for resource " + resource);
    }
    private List<Float> curve(EasingPreviewView view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(148,View.MeasureSpec.EXACTLY));
        view.layout(0,0,300,148);
        List<Float> points = new ArrayList<>();
        view.draw(new Canvas() {
            @Override public void drawPath(Path path, Paint paint) {
                for (org.robolectric.shadows.ShadowPath.Point point : Shadows.shadowOf(path).getPoints()) {
                    points.add(point.getX()); points.add(point.getY());
                }
            }
        });
        return points;
    }

    @Test public void sharedCurvePickerRelabelsWithoutChangingChoiceWindowOrGraph() {
        LinearLayout fields = new LinearLayout(activity); fields.setOrientation(LinearLayout.VERTICAL);
        EasingSelector picker = EasingSelector.addTo(fields,R.string.batch_easing,29);
        EditText left = new EditText(activity), right = new EditText(activity);
        left.setText("0.125"); right.setText("0.75"); fields.addView(left); fields.addView(right);
        picker.bindWindow(left,right,()->true);
        new EditorPanelWindow.Builder(activity,activity.workbench).setTitle(R.string.batch_title).setView(fields).create().show();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        List<Float> graph = curve(picker.preview);
        java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
        picker.setOnChanged(changes::incrementAndGet);
        activity.languageController.select("zh-CN"); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(29,picker.type()); assertEquals(0,changes.get());
        assertEquals("0.125",left.getText().toString()); assertEquals("0.75",right.getText().toString());
        assertEquals(graph,curve(picker.preview));
        assertEquals("弹性缓入缓出",picker.spinner.getSelectedItem().toString());
        assertTrue(views(fields,TextView.class).stream().anyMatch(v->v.getText().toString().equals("数值变化曲线")));
    }

    @Test public void playtestOptionsRetainDraftRadioChoicesPreferencesAndValidationOnLanguageChange() throws Exception {
        activity.getPreferences(0).edit().clear().commit();
        AlertDialog dialog = PlaytestOptionsDialog.show(activity,activity.editorView.getChart(),6,12,s->fail("Language must not start playtest"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View root = dialog.getWindow().getDecorView();
        RadioButton custom = root.findViewWithTag("start.custom"), countdown = root.findViewWithTag("countdown.5");
        custom.performClick(); countdown.performClick();
        EditText time = root.findViewWithTag("playtest.time"); time.setText("1:99");
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        assertNotNull(time.getError()); time.setSelection(2);
        Map<String,?> saved = new HashMap<>(activity.getPreferences(0).getAll());
        String chart = activity.editorView.getChart().toJsonString();
        activity.languageController.select("zh-CN"); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(dialog.isShowing()); assertTrue(custom.isChecked()); assertTrue(countdown.isChecked());
        assertEquals("1:99",time.getText().toString()); assertEquals(2,time.getSelectionStart());
        assertEquals("时间格式无效",time.getError().toString());
        assertEquals(saved,activity.getPreferences(0).getAll());
        assertEquals(chart,activity.editorView.getChart().toJsonString());
        assertEquals("开始",dialog.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
        assertEquals("取消",dialog.getButton(DialogInterface.BUTTON_NEGATIVE).getText().toString());
        assertEquals("自定义位置",custom.getText().toString()); assertEquals("5 秒",countdown.getText().toString());
        dialog.dismiss();
    }

    @Test public void pausedPlaytestRelabelsWithoutReplacingJudgementOrClockState() throws Exception {
        activity.getPreferences(0).edit().clear().commit();
        activity.mainPlaytest.showOptions(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog options = ShadowAlertDialog.getLatestAlertDialog();
        options.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        android.app.Dialog window = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        PlaytestView screen = views(window.getWindow().getDecorView(),PlaytestView.class).get(0);
        screen.pause();
        Object judge = screen.judge(); double seconds = screen.seconds(); View pausedPanel = screen.getChildAt(screen.getChildCount()-1);
        String chart = activity.editorView.getChart().toJsonString();
        activity.languageController.select("zh-CN");
        assertEquals(PlaytestClock.State.PAUSED,screen.state()); assertSame(judge,screen.judge());
        assertEquals(seconds,screen.seconds(),0); assertSame(pausedPanel,screen.getChildAt(screen.getChildCount()-1));
        assertEquals(chart,activity.editorView.getChart().toJsonString());
        assertEquals("继续",button(screen,R.string.playtest_resume).getText().toString());
        assertTrue(views(screen,TextView.class).stream().anyMatch(v->v.getText().toString().startsWith("分数  ")));
        activity.mainPlaytest.close();
    }

    @Test public void eventCloneRetainsOuterDraftAndNestedDestinationSelectionOnLanguageChange() throws Exception {
        ChartDocument chart = activity.editorView.getChart(); JudgeLine sourceLine = activity.editorView.getCurrentJudgeLine();
        sourceLine.eventLayers.set(0,new EventLayer());
        for (int i=1;i<=2;i++) { JudgeLine line = new JudgeLine(); line.name="Destination "+i; chart.judgeLines.add(line); }
        LineEvent source = new LineEvent(); source.type=EventType.MOVE_X;
        source.startTime=new BeatTime(1,0,1); source.endTime=new BeatTime(2,0,1); source.start=10; source.end=20;
        sourceLine.layer(0).events(source.type).add(source);
        Class<?> surface = Class.forName("com.xpe.mobile.editor.EditorSurfaceView");
        java.lang.reflect.Field single=surface.getDeclaredField("selectedEvent"); single.setAccessible(true); single.set(activity.editorView,source);
        java.lang.reflect.Field multiple=surface.getDeclaredField("selectedEvents"); multiple.setAccessible(true);
        @SuppressWarnings("unchecked") Set<LineEvent> selected=(Set<LineEvent>)multiple.get(activity.editorView); selected.add(source);
        String json = chart.toJsonString();
        activity.editorDialogs.showEventClone(); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
        Spinner mode=labeled(activity.workbench,R.string.clone_source_mode,Spinner.class);
        Spinner timing=labeled(activity.workbench,R.string.clone_timing_mode,Spinner.class);
        mode.setSelection(1); timing.setSelection(1); Shadows.shadowOf(Looper.getMainLooper()).idle();
        EditText delay=labeled(activity.workbench,R.string.clone_delay,EditText.class); delay.setText("0:1/4");
        button(activity.workbench,R.string.clone_choose_lines).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        EditText search=labeled(activity.workbench,R.string.clone_search_lines,EditText.class); search.setText("Destination 1");
        CheckBox target=views(activity.workbench,CheckBox.class).stream().filter(v->v.getText().toString().contains("Destination 1")).findFirst().orElseThrow();
        target.setChecked(true); search.setSelection(3);
        activity.languageController.select("zh-CN"); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Destination 1",search.getText().toString()); assertEquals(3,search.getSelectionStart());
        assertTrue(target.isChecked()); assertTrue(target.getText().toString().startsWith("判定线 1"));
        assertEquals(1,mode.getSelectedItemPosition()); assertEquals(1,timing.getSelectedItemPosition());
        assertEquals("0:1/4",delay.getText().toString()); assertEquals(json,chart.toJsonString());
        button(activity.workbench,R.string.clone_use_lines).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
        assertEquals("移动事件",button(activity.workbench,R.string.clone_move_action).getText().toString());
        assertEquals(json,chart.toJsonString()); assertFalse(activity.editorView.isChartDirty());
    }
    @Test public void choosingLanguageInSettingsPreservesUnappliedDraftAndEditorState() {
        activity.editorDialogs.showSettings();Shadows.shadowOf(Looper.getMainLooper()).idle();
        EditText volume=views(activity.workbench,EditText.class).get(0);volume.setText("0.321");volume.setSelection(3);
        Spinner language=views(activity.workbench,Spinner.class).get(0);
        double saved=activity.editorSettings.musicVolume;ChartDocument chart=activity.editorView.getChart();
        assertEquals(2, language.getCount());
        language.setSelection(Arrays.asList(activity.languageController.codes()).indexOf("zh-CN"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("zh-CN",activity.languageController.selection());assertEquals("0.321",volume.getText().toString());assertEquals(3,volume.getSelectionStart());
        assertEquals(saved,activity.editorSettings.musicVolume,0);assertSame(chart,activity.editorView.getChart());
        assertTrue(views(activity.workbench,TextView.class).stream().anyMatch(v->v.getText().toString().equals("应用")));
        assertEquals("English",language.getAdapter().getItem(0).toString());
        assertEquals("简体中文",language.getAdapter().getItem(1).toString());
        assertTrue(views(activity.workbench,CheckBox.class).stream()
                .anyMatch(v->v.getText().toString().equals("点击谱面跳转时间")));
        assertTrue(views(activity.workbench,TextView.class).stream()
                .anyMatch(v->v.getText().toString().startsWith("外接键盘快捷键")));
    }

    @Test public void linePropertiesRetainParentDraftAcrossLanguagesAndApplyWithUndoRedo() throws Exception {
        ChartDocument chart = activity.editorView.getChart();
        JudgeLine child = new JudgeLine(); child.name = "Child"; chart.judgeLines.add(child);
        JudgeLine descendant = new JudgeLine(); descendant.father = 1; chart.judgeLines.add(descendant);
        activity.editorView.selectLine(1);
        activity.editorDialogs.showLineManager();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        button(activity.workbench,R.string.properties_button).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Spinner parent = labeled(activity.workbench,R.string.field_line_parent,Spinner.class);
        CheckBox inherit = views(activity.workbench,CheckBox.class).stream()
                .filter(v -> v.getText().toString().equals(activity.getString(R.string.field_line_inherit_rotation)))
                .findFirst().orElseThrow();
        assertEquals(2,parent.getCount()); // None and line 0; self and descendants are excluded.
        assertEquals("None",parent.getSelectedItem());
        assertFalse(inherit.isEnabled());
        String before = chart.toJsonString();
        parent.setSelection(1); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(inherit.isEnabled()); inherit.setChecked(true);
        activity.languageController.select("zh-CN"); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1,parent.getSelectedItemPosition()); assertTrue(inherit.isChecked());
        assertEquals("继承旋转",inherit.getText().toString());
        assertTrue(parent.getSelectedItem().toString().startsWith("判定线 0"));
        assertEquals(before,chart.toJsonString());
        assertFalse(activity.editorView.isChartDirty());
        button(activity.workbench,R.string.action_apply).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0,child.father); assertTrue(child.rotateWithFather);
        JudgeLine reopened = ChartDocument.fromJson(chart.toJsonString()).judgeLines.get(1);
        assertEquals(0,reopened.father); assertTrue(reopened.rotateWithFather);
        activity.editorView.performUndo(); assertEquals(before,chart.toJsonString());
        activity.editorView.performRedo(); assertEquals(0,child.father); assertTrue(child.rotateWithFather);
        button(activity.workbench,R.string.properties_button).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        parent = labeled(activity.workbench,R.string.field_line_parent,Spinner.class);
        assertEquals(1,parent.getSelectedItemPosition());
        parent.setSelection(0); Shadows.shadowOf(Looper.getMainLooper()).idle();
        button(activity.workbench,R.string.action_apply).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(-1,child.father);
        activity.editorView.performUndo(); assertEquals(0,child.father);
    }

    @Test public void parentValidationRejectsCyclesWithoutChangingTheChartOrHistory() throws Exception {
        ChartDocument chart = activity.editorView.getChart();
        JudgeLine target = chart.judgeLines.get(0), child = new JudgeLine();
        child.father = 0; chart.judgeLines.add(child);
        String before = chart.toJsonString();
        JudgeLine edited = target.copyProperties(); edited.father = 1; edited.rotateWithFather = true;
        assertEquals(EditorView.LineApplyResult.INVALID_PARENT,activity.editorView.applyJudgeLineProperties(target,edited));
        assertEquals(before,chart.toJsonString()); assertFalse(activity.editorView.isChartDirty());
        activity.editorView.performUndo(); assertEquals(before,chart.toJsonString());
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
