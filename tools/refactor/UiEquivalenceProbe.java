package com.xpe.mobile;

import android.content.*;
import android.content.res.*;
import android.graphics.*;
import android.util.DisplayMetrics;
import android.view.*;
import com.xpe.mobile.editor.*;
import com.xpe.mobile.model.*;
import com.xpe.mobile.preview.*;
import com.xpe.mobile.shadereditor.*;
import com.xpe.mobile.config.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Real Android UI/Canvas image comparison; does not install or start an Activity. */
public final class UiEquivalenceProbe {
    static void layout(View view,int w,int h) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h);
    }
    static void field(Object object,String name,Object value)throws Exception {
        for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try {
            Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(object,value);return;
        }catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);
    }
    static void capture(View view,File root,String name,int w,int h)throws Exception {
        layout(view,w,h);Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));
        try(OutputStream out=new FileOutputStream(new File(root,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }
    public static void main(String[] args) {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out)));System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err)));
        try {run(args);System.exit(0);}catch(Throwable error){error.printStackTrace();System.exit(1);}
    }
    static void run(String[] args)throws Exception {
        android.os.Looper.prepareMainLooper();Typeface.class.getMethod("loadPreinstalledSystemFontMap").invoke(null);
        Class<?> thread=Class.forName("android.app.ActivityThread");Object activity=thread.getMethod("systemMain").invoke(null);
        Context system=(Context)thread.getMethod("getSystemContext").invoke(activity);
        Context runtime=system.createPackageContext("com.termux",Context.CONTEXT_IGNORE_SECURITY);
        android.app.Application application=new android.app.Application();Method attach=android.app.Application.class.getDeclaredMethod("attach",Context.class);attach.setAccessible(true);attach.invoke(application,runtime);
        Field initial=thread.getDeclaredField("mInitialApplication");initial.setAccessible(true);initial.set(activity,application);
        Class<?> classifier=Class.forName("android.view.textclassifier.TextClassificationConstants");Field initialized=classifier.getDeclaredField("sMemoizedValuesInitialized");initialized.setAccessible(true);initialized.setBoolean(null,true);
        AssetManager assets=AssetManager.class.getDeclaredConstructor().newInstance();AssetManager.class.getMethod("addAssetPath",String.class).invoke(assets,args[0]);
        DisplayMetrics metrics=new DisplayMetrics();metrics.setToDefaults();Resources resources=new Resources(assets,metrics,new Configuration());
        Context context=new ContextWrapper(runtime) {
            @Override public Resources getResources(){return resources;}
            @Override public AssetManager getAssets(){return assets;}
            @Override public Object getSystemService(String name){if("autofill".equals(name)||"content_capture".equals(name)||INPUT_METHOD_SERVICE.equals(name))return null;return super.getSystemService(name);}
        };
        File root=new File(args[1]);root.mkdirs();
        String demo=new String(Files.readAllBytes(java.nio.file.Path.of(args[2])),java.nio.charset.StandardCharsets.UTF_8);
        for(int[] size:new int[][]{{1200,700},{1920,1080}})for(int tab:new int[]{0,1}) {
            EditorView editor=new EditorView(context);editor.setChart(ChartDocument.fromJson(demo));editor.setWorkbenchTab(tab);
            field(editor,"currentBeat",1.5);
            capture(editor,root,"editor-"+tab+"-"+size[0],size[0],size[1]);
        }
        for(int width:new int[]{900,1350,1920}) {
            EditorView editor=new EditorView(context);editor.setChart(ChartDocument.fromJson(demo));field(editor,"currentBeat",1.5);field(editor,"previewMode",true);
            capture(editor,root,"preview-"+width,width,900);
        }
        EditorView editor=new EditorView(context);ChartDocument chart=ChartDocument.fromJson(demo);editor.setChart(chart);
        String source="precision mediump float; varying vec2 uv; uniform sampler2D screenTexture; uniform float power; // %0.01%\nvoid main(){gl_FragColor=texture2D(screenTexture,uv);}";
        ShaderDocument document=new ShaderDocument(null,chart,name->source);document.add("chromatic",1);document.selected=0;
        ShaderEditorView shader=new ShaderEditorView(context,editor,document,new ShaderEditorView.Host(){public void changed(){}public void importShader(){}public void save(){}});
        capture(shader,root,"shader-editor",1920,1080);
        System.out.println("PASS Android API "+android.os.Build.VERSION.SDK_INT+": 8 editor/preview/shader UI images captured");
    }
}
