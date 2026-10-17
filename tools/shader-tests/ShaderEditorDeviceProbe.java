package com.xpe.mobile;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.packageio.*;
import com.xpe.mobile.preview.PreviewShaderLoader;
import com.xpe.mobile.shadereditor.*;
import java.io.*;
import java.util.*;

/** Native Android UI/model smoke test and standalone layout capture, without APK installation. */
public final class ShaderEditorDeviceProbe {
    public static void main(String[] args) {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out)));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err)));
        try {
            run(args);
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }
    static void run(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        android.graphics.Typeface.class.getMethod("loadPreinstalledSystemFontMap").invoke(null);
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object activity = thread.getMethod("systemMain").invoke(null);
        Context system = (Context) thread.getMethod("getSystemContext").invoke(activity);
        Context runtime =
            system.createPackageContext("com.termux", Context.CONTEXT_IGNORE_SECURITY);
        android.app.Application application = new android.app.Application();
        java.lang.reflect.Method attach =
            android.app.Application.class.getDeclaredMethod("attach", Context.class);
        attach.setAccessible(true);
        attach.invoke(application, runtime);
        java.lang.reflect.Field initialApplication = thread.getDeclaredField("mInitialApplication");
        initialApplication.setAccessible(true);
        initialApplication.set(activity, application);
        // Standalone UI has no system package identity for DeviceConfig text classification.
        Class<?> classifier =
            Class.forName("android.view.textclassifier.TextClassificationConstants");
        java.lang.reflect.Field memoized =
            classifier.getDeclaredField("sMemoizedValuesInitialized");
        memoized.setAccessible(true);
        memoized.setBoolean(null, true);
        AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
        AssetManager.class.getMethod("addAssetPath", String.class).invoke(assets, args[2]);
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.setToDefaults();
        Resources resources = new Resources(assets, metrics, new Configuration());
        Context wrapper = new ContextWrapper(runtime) {
            @Override
            public Resources getResources() {
                return resources;
            }
            @Override
            public AssetManager getAssets() {
                return assets;
            }
            @Override
            public Object getSystemService(String name) {
                // app_process has no application window or package-bound autofill/IME services.
                if ("autofill".equals(name) || "content_capture".equals(name)
                    || INPUT_METHOD_SERVICE.equals(name))
                    return null;
                return super.getSystemService(name);
            }
        };
        Context context = new ContextThemeWrapper(wrapper, R.style.AppTheme);
        File root = new File(args[3], "editor");
        root.mkdirs();
        int total = 0;
        for (String name : new String[] {"★ Ascension To Heaven.zip", "ANTINEUTRINO WITCHCRAFT.zip",
                 "Hello (BPM) 2025.zip"}) {
            File workspace = new File(root, name.replace(".zip", ""));
            if (workspace.exists())
                delete(workspace);
            ChartPackage pack;
            try (InputStream stream = new FileInputStream(new File(args[1], name))) {
                pack = new PackageImporter().importPackage(stream, workspace, name);
            }
            EditorView editor = new EditorView(context);
            editor.setChart(pack.getChart());
            editor.setUseRpe170Speed(pack.isUseRpe170Speed());
            ShaderDocument document = new ShaderDocument(ShaderWorkspace.read(pack),
                pack.getChart(), shader -> PreviewShaderLoader.source(workspace, assets, shader));
            ShaderEditorView screen =
                new ShaderEditorView(context, editor, document, new ShaderEditorView.Host() {
                    public void changed() {}
                    public void save() {}
                    public void importShader() {}
                });
            WorkbenchView workbench =
                new WorkbenchView(context, editor, () -> new TextView(context));
            workbench.setShaderOpener(() -> workbench.showShaders(screen));
            workbench.switchTab(3);
            editor.shaderEditorSeek(document.start(0));
            for (int[] size : new int[][] {{800, 360}, {1200, 700}, {1920, 1080}}) {
                layout(workbench, size[0], size[1]);
                require(
                    screen.timeline.getLeft() == screen.getChildAt(1).getRight(), "right timeline");
                require(screen.getChildAt(4).getTop() == screen.timeline.getBottom(),
                    "bottom parameters");
                float density = context.getResources().getDisplayMetrics().density;
                require(screen.getChildAt(0).getHeight() == Math.round(48 * density), "normal toolbar height");
                require(button(screen, "Play").getHeight() == Math.round(38 * density), "normal button height");
                require(button(screen, "Play").getWidth() == Math.round(58 * density), "normal Play width");
                require(button(screen, "Play").getLeft() == 0, "left-aligned toolbar controls");
                require(screen.getChildAt(4).getHeight() <= Math.round(88 * density), "compact inspector");
                ViewGroup toolbar = (ViewGroup)screen.getChildAt(0);
                require(toolbar.getChildAt(1).getRight() == toolbar.getWidth(), "seek pinned right");
                LinearLayout footer =
                    (LinearLayout) ((ViewGroup) workbench.getChildAt(2)).getChildAt(0);
                String[] labels = {"Events", "Storyboard", "Shaders", "Menu"};
                for (int i = 0; i < 4; i++) {
                    Button button = (Button) footer.getChildAt(i);
                    require(labels[i].contentEquals(button.getText()), "footer order");
                    require(button.getRight() <= footer.getWidth(), "button fits");
                }
            }
            layout(workbench, 2560, 1280);

            Bitmap image = Bitmap.createBitmap(2560, 1280, Bitmap.Config.ARGB_8888);
            dirty(workbench);
            workbench.draw(new Canvas(image));
            File capture = new File(root, name.replace(".zip", "-editor.png"));
            try (OutputStream stream = new FileOutputStream(capture)) {
                image.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            image.recycle();
            String initial = document.json();
            document.duplicate();
            document.undo();
            require(initial.equals(document.json()), "native undo/raw preservation");
            button(screen, "Parameters").performClick();
            button(screen, "Animation").performClick();
            button(screen, "FX BPM").performClick();
            button(screen, "Effect").performClick();
            double seconds = editor.shaderEditorSeconds();
            workbench.switchTab(0);
            require(screen.getVisibility() == View.GONE, "hidden shader editor");
            workbench.switchTab(3);
            require(editor.shaderEditorSeconds() == seconds, "shared seek position");
            total += document.count();
            System.out.println("PASS native shader editor " + name + " effects=" + document.count()
                + "; 3 layouts, normal toolbar/control sizes, compact inspector, seek bounds, 4 tabs, forms, history; capture=" + capture);
        }
        require(total == 819, "all real effects loaded");
        fix23Checks(context, root);
        EditorFix23DeviceChecks.run(context, root);
        com.xpe.mobile.editor.EditorNoteDeviceChecks.run(context, root);
        fix24HoldChecks(context);
    }
    static void fix24HoldChecks(Context context) throws Exception {
        com.xpe.mobile.model.ChartDocument chart = com.xpe.mobile.model.ChartDocument.fromJson("{}");
        EditorView editor = new EditorView(context); editor.setChart(chart);
        long[] audio = {0, 0};
        editor.setCallback((EditorView.Callback) java.lang.reflect.Proxy.newProxyInstance(
                EditorView.Callback.class.getClassLoader(), new Class<?>[]{EditorView.Callback.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isAudioReady": case "isAudioPlaying": return true;
                        case "pauseAudio": audio[0]++; return null;
                        case "startAudio": audio[1]++; return null;
                        case "audioDurationMs": return 10000L;
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                }));
        ShaderDocument doc = new ShaderDocument(null, chart, name -> "void main(){}");
        doc.add("lane", 0);
        ShaderEditorView screen = new ShaderEditorView(context, editor, doc, new ShaderEditorView.Host() {
            public void changed() {} public void save() {} public void importShader() {}
        });
        layout(screen, 1200, 700);
        editor.shaderEditorPlay();
        String original = doc.json();
        for (int region = 0; region < 3; region++) {
            editor.shaderEditorSeek(.25); doc.selected = 0;
            Bitmap grid = Bitmap.createBitmap(screen.timeline.getWidth(), screen.timeline.getHeight(), Bitmap.Config.ARGB_8888);
            screen.timeline.draw(new Canvas(grid)); grid.recycle();
            java.lang.reflect.Field hits = screen.timeline.getClass().getDeclaredField("hits"); hits.setAccessible(true);
            Object hit = ((List<?>) hits.get(screen.timeline)).get(0);
            java.lang.reflect.Field bounds = hit.getClass().getDeclaredField("rect"); bounds.setAccessible(true);
            android.graphics.RectF rect = (android.graphics.RectF) bounds.get(hit);
            float x = region == 1 ? rect.centerX() : 5, y = region == 1 ? rect.centerY() : 100;
            long paused = audio[0], resumed = audio[1];
            touch(screen.timeline, 10, android.view.MotionEvent.ACTION_DOWN, x, y);
            require(audio[0] == paused + 1, "native shader grid press pauses immediately");
            View preview = screen.getChildAt(1);
            Bitmap image = Bitmap.createBitmap(preview.getWidth(), preview.getHeight(), Bitmap.Config.ARGB_8888);
            preview.draw(new Canvas(image)); image.recycle();
            require(editor.shaderEditorSeconds() == .25, "native shader grid hold freezes playback");
            touch(screen.timeline, 20, region == 2 ? android.view.MotionEvent.ACTION_CANCEL : android.view.MotionEvent.ACTION_UP, x, y);
            require(audio[1] == resumed + 1 && editor.isPlaying(), "native shader grid release/cancel resumes playback");
            require(original.equals(doc.json()), "native grid hold preserves effect times");
        }
        System.out.println("PASS fixes24 native shader grid touch: empty area/block press pauses; hold freezes playback; release/cancel resumes without editing");
    }
    static void fix23Checks(Context context, File root) throws Exception {
        com.xpe.mobile.model.ChartDocument chart = com.xpe.mobile.model.ChartDocument.fromJson("{}");
        EditorView editor = new EditorView(context); editor.setChart(chart);
        ShaderDocument doc = new ShaderDocument(null, chart, name -> "uniform float power; // %0.5%\nvoid main(){}");
        for (int i=0;i<12;i++) doc.add("lane" + i,0);
        ShaderEditorView screen = new ShaderEditorView(context,editor,doc,new ShaderEditorView.Host() {
            public void changed() {} public void save() {} public void importShader() {}
        });
        layout(screen,1200,700);
        View timeline=screen.timeline;
        Bitmap bitmap=Bitmap.createBitmap(timeline.getWidth(),timeline.getHeight(),Bitmap.Config.ARGB_8888);
        timeline.draw(new Canvas(bitmap));
        double seconds=editor.shaderEditorSeconds();
        touch(timeline,0,android.view.MotionEvent.ACTION_DOWN,5,100);
        touch(timeline,10,android.view.MotionEvent.ACTION_MOVE,-3000,102);
        touch(timeline,20,android.view.MotionEvent.ACTION_UP,-3000,102);
        timeline.draw(new Canvas(bitmap));
        require(editor.shaderEditorSeconds()==seconds,"horizontal pan must not seek");
        java.lang.reflect.Field count=timeline.getClass().getDeclaredField("hitCount");count.setAccessible(true);
        require(count.getInt(timeline)>0,"visible blocks at far right");
        require(bitmap.getPixel(bitmap.getWidth()-1,1)==android.graphics.Color.rgb(17,23,30),"no dark header strip");
        int ruler=0;
        for(int y=35;y<bitmap.getHeight()-40;y++)for(int x=2;x<33;x++)
            if(bitmap.getPixel(x,y)!=android.graphics.Color.rgb(17,23,30))ruler++;
        require(ruler>0,"beat ruler has visible labels");
        try(OutputStream stream=new FileOutputStream(new File(root,"fix23-shader-grid.png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);
        }
        bitmap.recycle();
        touch(timeline,100,android.view.MotionEvent.ACTION_DOWN,5,100);
        touch(timeline,110,android.view.MotionEvent.ACTION_UP,5,100);
        require(doc.selected==-1,"empty tap clears shader selection");
        for(int child:new int[]{1,2}) {
            View surface=screen.getChildAt(child);surface.setVisibility(View.VISIBLE);
            seconds=editor.shaderEditorSeconds();
            touch(surface,200,android.view.MotionEvent.ACTION_DOWN,100,100);
            touch(surface,210,android.view.MotionEvent.ACTION_MOVE,100,200);
            touch(surface,220,android.view.MotionEvent.ACTION_UP,100,200);
            require(editor.shaderEditorSeconds()>seconds,"native preview scrubs child "+child);
        }
        ColorPickerButton button=new ColorPickerButton(context,"Color",0xffff0000,true);
        Class<?> pickerClass=java.util.Arrays.stream(ColorPickerButton.class.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals("Picker")).findFirst().get();
        java.lang.reflect.Constructor<?> constructor=pickerClass.getDeclaredConstructors()[0];constructor.setAccessible(true);
        View picker=(View)constructor.newInstance(button,context,0xffff0000,true);
        int pickerWidth=Math.round(600*context.getResources().getDisplayMetrics().density);
        picker.measure(View.MeasureSpec.makeMeasureSpec(pickerWidth,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(230*context.getResources().getDisplayMetrics().density),View.MeasureSpec.EXACTLY));
        picker.layout(0,0,pickerWidth,picker.getMeasuredHeight());
        Bitmap palette=Bitmap.createBitmap(picker.getWidth(),picker.getHeight(),Bitmap.Config.ARGB_8888);
        picker.draw(new Canvas(palette));
        java.lang.reflect.Field paletteField=pickerClass.getDeclaredField("palette");paletteField.setAccessible(true);
        View field=(View)paletteField.get(picker);
        View left=(View)field.getParent();
        int x=left.getLeft()+field.getRight()-2,y=left.getTop()+field.getTop()+2;
        require(android.graphics.Color.red(palette.getPixel(x,y))>240,"native palette red corner");
        require(android.graphics.Color.green(palette.getPixel(x,y))<15,"native palette saturation corner");
        require(android.graphics.Color.red(palette.getPixel(x,left.getTop()+field.getBottom()-2))<15,"native palette dark corner");
        try(OutputStream stream=new FileOutputStream(new File(root,"fix23-color-palette.png"))) {
            palette.compress(Bitmap.CompressFormat.PNG,100,stream);
        }
        palette.recycle();
        System.out.println("PASS fixes23 native grid: bounded horizontal scroll, visible blocks/names, beat ruler, no dark header, empty tap, Preview/GPU scrub; native HSV palette gradients");
    }
    static void touch(View view,long time,int action,float x,float y) {
        android.view.MotionEvent e=android.view.MotionEvent.obtain(time,time,action,x,y,0);
        view.dispatchTouchEvent(e); e.recycle();
    }
    static void dirty(View view) {
        view.invalidate();
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                dirty(((ViewGroup) view).getChildAt(i));
    }
    static void layout(View view, int w, int h) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
    }
    static Button button(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText()))
            return (Button) view;
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                Button button = button(((ViewGroup) view).getChildAt(i), label);
                if (button != null)
                    return button;
            }
        return null;
    }
    static void delete(File file) {
        File[] files = file.listFiles();
        if (files != null)
            for (File child : files) delete(child);
        file.delete();
    }
    static void require(boolean value, String label) {
        if (!value)
            throw new AssertionError(label);
    }
}
