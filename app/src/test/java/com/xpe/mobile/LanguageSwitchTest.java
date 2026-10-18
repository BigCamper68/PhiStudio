package com.xpe.mobile;

import com.xpe.mobile.i18n.UiText;
import com.xpe.mobile.i18n.LanguageController;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.LocaleList;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.*;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.i18n.ModelLabels;
import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.packageio.PackageImporter;
import com.xpe.mobile.packageio.PackageWorkspaceWriter;
import com.xpe.mobile.packageio.PackageWorkspaceLoader;
import com.xpe.mobile.preview.ChartEvaluator;
import com.xpe.mobile.preview.RenderScene;
import com.xpe.mobile.shadereditor.ShaderDocument;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.zip.ZipFile;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 32, 33})
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class LanguageSwitchTest {
    @Rule public org.junit.rules.TemporaryFolder packages = new org.junit.rules.TemporaryFolder();
    public static class ResourceActivity extends Activity implements UiText.ResourceOwner {
        LanguageController languages;
        @Override protected void attachBaseContext(Context base) {
            super.attachBaseContext(base);
            languages = new LanguageController(base);
        }
        @Override public Resources getResources() { return languages == null ? super.getResources() : languages.resources(); }
        @Override public Resources uiResources() { return getResources(); }
    }
    private ResourceActivity activity;
    @Before public void setup() {
        activity = Robolectric.buildActivity(ResourceActivity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        activity.languages.select("en");
    }
    @After public void teardown() { activity.languages.select("en"); activity.finish(); }

    @Test public void resourcesFallbackPluralArrayAndThemeWrapperUseSelectedLanguage() {
        Locale processLocale = Locale.getDefault();
        Context wrapper = new ContextThemeWrapper(activity, activity.getTheme());
        activity.languages.select("zh-CN");
        assertEquals("设置", activity.getString(R.string.settings_title));
        assertEquals("设置", UiText.string(wrapper, R.string.settings_title));
        assertEquals("已选择 3 项", activity.getResources().getQuantityString(R.plurals.controls_selection_count, 3, 3));
        assertEquals("线性", activity.getResources().getStringArray(R.array.event_easing_entries)[0]);
        assertTrue(activity.getString(R.string.complex_move_time_help).contains("整数:分子/分母"));
        Configuration unsupported = new Configuration(activity.getBaseContext().getResources().getConfiguration());
        unsupported.setLocales(LocaleList.forLanguageTags("fr-FR"));
        assertEquals("Settings", activity.getBaseContext().createConfigurationContext(unsupported)
                .getResources().getString(R.string.settings_title));
        assertEquals(processLocale, Locale.getDefault());
        if (Build.VERSION.SDK_INT >= 33)
            assertEquals("zh-CN",activity.getSystemService(LocaleManager.class).getApplicationLocales().toLanguageTags());
        activity.languages.select("en");
        if (Build.VERSION.SDK_INT >= 33) assertEquals("en", activity.getSystemService(LocaleManager.class).getApplicationLocales().toLanguageTags());
    }

    @Test public void chineseEditorCanvasAndEventLabelsKeepNoteNamesAndChartData() throws Exception {
        EditorView editor = new EditorView(activity);
        ChartDocument chart = ChartDocument.fromJson("{}");
        editor.setChart(chart);
        String json = chart.toJsonString();
        activity.languages.select("zh-CN");
        editor.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
        editor.layout(0, 0, 1920, 1080);
        List<String> labels = new ArrayList<>();
        Bitmap bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap) {
            @Override public void drawText(String text, float x, float y, Paint paint) {
                labels.add(text);
                super.drawText(text, x, y, paint);
            }
        };
        editor.draw(canvas);
        assertTrue(labels.toString(), labels.contains("事件"));
        for (String note : new String[] {"Tap", "Drag", "Flick", "Hold"})
            assertTrue(labels.toString(), labels.contains(note));
        assertFalse(labels.contains("Event"));
        assertTrue(labels.toString(), labels.stream().anyMatch(s -> s.contains("加载音频")));
        assertTrue(labels.toString(), labels.stream().anyMatch(s -> s.startsWith("拍 0.000  BPM ")
                && s.contains("音频") && s.contains("音符总数") && s.contains("事件总数")));
        assertArrayEquals(new String[] {"X 位移", "Y 位移", "旋转", "不透明度", "速度"},
                java.util.Arrays.stream(EventType.values()).map(t -> ModelLabels.event(activity, t)).toArray(String[]::new));
        assertEquals(json, chart.toJsonString());
        bitmap.recycle();
    }

    @Test public void suppliedPackageKeepsDataAssetsAndPreviewAcrossLanguageChanges() throws Exception {
        String supplied = System.getenv("PHISTUDIO_TEST_PACKAGE");
        Assume.assumeTrue("Set PHISTUDIO_TEST_PACKAGE to test a supplied chart package",
                supplied != null && !supplied.trim().isEmpty());
        File archive = new File(supplied);
        assertTrue("Missing supplied chart: " + archive, archive.isFile());
        byte[] originalDigest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive.toPath()));
        ChartPackage pack;
        try (FileInputStream input = new FileInputStream(archive)) {
            pack = new PackageImporter().importPackage(input, new File(packages.getRoot(), "chart"), archive.getName());
        }
        ChartDocument chart = pack.getChart();
        String json = chart.toJsonString();
        int notes = chart.totalNotes(), events = chart.totalEvents();
        assertTrue(notes > 0); assertTrue(events > 0);
        assertNotNull(pack.getAudioFile()); assertTrue(pack.getAudioFile().isFile());
        assertNotNull(pack.getIllustrationFile()); assertTrue(pack.getIllustrationFile().isFile());
        try (ZipFile zip = new ZipFile(archive)) {
            for (String path : new String[] {pack.getChartPath(), pack.getAudioPath(), pack.getIllustrationPath()}) {
                try (java.io.InputStream input = zip.getInputStream(zip.getEntry(path))) {
                    assertArrayEquals(path, input.readAllBytes(), Files.readAllBytes(new File(pack.getWorkspace(), path).toPath()));
                }
            }
        }
        List<RenderScene> baseline = new ArrayList<>();
        for (int beat = 0; beat <= 512; beat += 8)
            baseline.add(ChartEvaluator.evaluate(chart, beat, true, -1L, pack.isUseRpe170Speed()));
        EditorView editor = new EditorView(activity); editor.setChart(chart);
        for (String language : new String[] {"zh-CN", "en", "zh-CN"}) {
            activity.languages.select(language); editor.relocalizeUi();
            assertSame(chart, editor.getChart()); assertEquals(json, chart.toJsonString());
            for (RenderScene before : baseline) {
                RenderScene after = ChartEvaluator.evaluate(chart, before.beat, true, -1L, pack.isUseRpe170Speed());
                assertEquals(before.chartTimeMs, after.chartTimeMs);
                assertEquals(before.lines.size(), after.lines.size());
                for (int i = 0; i < before.lines.size(); i++) {
                    RenderScene.RenderLine a = before.lines.get(i), b = after.lines.get(i);
                    assertEquals(a.x, b.x, 0); assertEquals(a.y, b.y, 0);
                    assertEquals(a.rotationDegrees, b.rotationDegrees, 0); assertEquals(a.alpha, b.alpha);
                    assertEquals(a.text, b.text); assertEquals(a.notes.size(), b.notes.size());
                }
            }
        }
        PackageWorkspaceWriter.writeChart(pack, chart);
        ChartPackage reopened = new PackageWorkspaceLoader().load(pack.getWorkspace(), archive.getName());
        assertEquals(notes, reopened.getChart().totalNotes()); assertEquals(events, reopened.getChart().totalEvents());
        assertEquals(pack.getAudioPath(), reopened.getAudioPath());
        assertEquals(pack.getIllustrationPath(), reopened.getIllustrationPath());
        assertArrayEquals(originalDigest, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive.toPath())));
        System.out.printf(Locale.ROOT, "Supplied chart %s: %d lines, %d notes, %d events; language switch and reload passed%n",
                chart.name, chart.judgeLines.size(), notes, events);
    }

    @Test public void panelRelabelingRetainsDraftSelectionFocusScrollAndChartHistory() throws Exception {
        EditorView editor = new EditorView(activity);
        WorkbenchView workbench = new WorkbenchView(activity,editor,()->new TextView(activity));
        activity.setContentView(workbench);
        ChartDocument chart = ChartDocument.fromJson("{}"); editor.setChart(chart);
        java.lang.reflect.Field historyField = Class.forName("com.xpe.mobile.editor.EditorDocumentView").getDeclaredField("history");
        historyField.setAccessible(true);
        com.xpe.mobile.editor.EditHistory history = (com.xpe.mobile.editor.EditHistory) historyField.get(editor);
        AtomicInteger commands = new AtomicInteger();
        com.xpe.mobile.editor.EditHistory.Command command = new com.xpe.mobile.editor.EditHistory.Command() {
            public void apply() { commands.incrementAndGet(); }
            public void revert() { commands.decrementAndGet(); }
        };
        history.execute(command); history.execute(command); history.undo();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        LinearLayout content = new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);
        TextView label=new TextView(activity);UiText.text(label,R.string.settings_title);content.addView(label);
        EditText draft=new EditText(activity);draft.setText("unsaved 12.50");content.addView(draft);
        CheckBox checkbox=new CheckBox(activity);checkbox.setChecked(true);UiText.text(checkbox,R.string.settings_autosave);content.addView(checkbox);
        Spinner spinner=new Spinner(activity);UiText.spinner(spinner,R.array.batch_edit_modes);spinner.setSelection(2);content.addView(spinner);
        EditorPanelWindow panel=new EditorPanelWindow.Builder(activity,workbench).setTitle(R.string.settings_title)
                .setView(content).setPositiveButton(R.string.action_apply,null).create();panel.show();
        workbench.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));
        workbench.layout(0,0,1920,1080);
        draft.requestFocus();draft.setSelection(3,7);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        AtomicInteger selected=new AtomicInteger();spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){selected.incrementAndGet();}
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){selected.incrementAndGet();}
        });
        Shadows.shadowOf(Looper.getMainLooper()).idle();int before=selected.get();
        String json=chart.toJsonString();View originalParent=(View)draft.getParent();int scroll=panel.content.getScrollY();
        activity.languages.setLanguageChanged(workbench::relocalizeUi);activity.languages.select("zh-CN");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("设置",label.getText().toString());assertEquals("设置",panel.title.toString());
        assertEquals("应用",panel.getButton(android.content.DialogInterface.BUTTON_POSITIVE).getText().toString());
        assertSame(originalParent,draft.getParent());assertEquals("unsaved 12.50",draft.getText().toString());
        assertEquals(3,draft.getSelectionStart());assertEquals(7,draft.getSelectionEnd());assertTrue(draft.hasFocus());
        assertTrue(checkbox.isChecked());assertEquals(2,spinner.getSelectedItemPosition());assertEquals(before,selected.get());
        assertEquals(scroll,panel.content.getScrollY());assertSame(chart,editor.getChart());assertEquals(json,chart.toJsonString());
        assertTrue(history.canUndo());assertTrue(history.canRedo());assertEquals(1,commands.get());
        history.redo();assertEquals(2,commands.get());
    }

    @Test public void shaderFormIsRelabeledWithoutRebuildingOrWritingTheDocument() throws Exception {
        EditorView editor=new EditorView(activity);editor.setChart(ChartDocument.fromJson("{}"));
        ShaderDocument document=new ShaderDocument(null,editor.getChart(), name -> "void main() { gl_FragColor = vec4(1.0); }");
        ShaderEditorView shaders=new ShaderEditorView(activity,editor,document,new ShaderEditorView.Host(){
            public void changed(){fail("Language switch must not write shader data");}
            public void importShader(){} public void save(){}
        });
        shaders.page = 3; shaders.buildForm();
        Button addBpm = (Button) shaders.form.getChildAt(shaders.form.getChildCount() - 1);
        assertEquals("+ BPM change", addBpm.getText().toString());
        EditText draft=new EditText(activity);draft.setText("unapplied shader value");shaders.form.addView(draft);draft.setSelection(4);
        String json=document.json();long revision=document.revision();View parent=(View)draft.getParent();
        activity.languages.setLanguageChanged(shaders::relocalizeUi);activity.languages.select("zh-CN");
        assertSame(parent,draft.getParent());assertEquals("unapplied shader value",draft.getText().toString());assertEquals(4,draft.getSelectionStart());
        assertEquals(json,document.json());assertEquals(revision,document.revision());assertEquals("撤销",shaders.undo.getText().toString());
        assertEquals("+ BPM 变更", addBpm.getText().toString());
    }

    @Test public void legacyPreferencePersistsAndConfigurationChangesDoNotClearIt() {
        activity.languages.select("zh-CN");
        LanguageController restarted=new LanguageController(activity.getBaseContext());
        assertEquals("zh-CN",restarted.selection());
        restarted.onConfigurationChanged();assertEquals("设置",restarted.resources().getString(R.string.settings_title));
    }

    @Test @Config(sdk=33) public void startupAndConfigurationCallbacksDoNotChangePlatformLanguage() {
        Context base = activity.getBaseContext();
        base.getSharedPreferences("phistudio_language",0).edit().clear().commit();
        LocaleManager manager = base.getSystemService(LocaleManager.class);
        manager.setApplicationLocales(LocaleList.getEmptyLocaleList());
        LanguageController fresh = new LanguageController(base);
        assertEquals("en",fresh.selection());
        assertTrue("Opening the editor must not request an Android locale change",manager.getApplicationLocales().isEmpty());
        fresh.onConfigurationChanged();
        assertTrue("Returning from another app must not write a platform configuration",manager.getApplicationLocales().isEmpty());
    }

    @Test @Config(sdk=33) public void savedChineseChoiceRemainsAvailableWithoutRewritingPlatformSettings() {
        activity.languages.select("zh-CN");
        LocaleManager manager=activity.getSystemService(LocaleManager.class);
        manager.setApplicationLocales(LocaleList.getEmptyLocaleList());
        activity.languages.onConfigurationChanged();
        LanguageController reopened=new LanguageController(activity.getBaseContext());
        assertEquals("zh-CN",reopened.selection());
        assertEquals("设置",reopened.resources().getString(R.string.settings_title));
        assertTrue(manager.getApplicationLocales().isEmpty());
    }

    @Test @Config(sdk=33) public void externalPlatformSelectionAndPseudoLocalesReachExistingViews() {
        TextView title = new TextView(activity); UiText.text(title,R.string.settings_title);
        activity.languages.setLanguageChanged(() -> UiText.relocalize(title));
        LocaleManager manager = activity.getSystemService(LocaleManager.class);
        manager.setApplicationLocales(LocaleList.forLanguageTags("zh-CN"));activity.languages.onConfigurationChanged();
        assertEquals("设置",title.getText().toString());
        manager.setApplicationLocales(LocaleList.forLanguageTags("en-XA"));activity.languages.onConfigurationChanged();
        assertNotEquals("Settings",title.getText().toString());
        assertTrue(title.getText().toString(),title.getText().toString().contains("["));
        manager.setApplicationLocales(LocaleList.getEmptyLocaleList());activity.languages.onConfigurationChanged();
        assertEquals("en",activity.languages.selection());
        assertEquals("Settings",title.getText().toString());
        assertTrue(manager.getApplicationLocales().isEmpty());
    }

    @Test public void emptyLegacyChoiceBecomesPersistedEnglish() {
        Context base = activity.getBaseContext();
        base.getSharedPreferences("phistudio_language", 0).edit().clear().commit();
        if (Build.VERSION.SDK_INT >= 33) base.getSystemService(LocaleManager.class)
                .setApplicationLocales(LocaleList.getEmptyLocaleList());
        LanguageController fresh = new LanguageController(base);
        assertEquals("en", fresh.selection());
        assertEquals("Settings", fresh.resources().getString(R.string.settings_title));
        assertEquals("en", new LanguageController(base).selection());
        assertThrows(IllegalArgumentException.class, () -> fresh.select(""));
    }
}
