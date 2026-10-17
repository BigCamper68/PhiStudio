package com.xpe.mobile;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.model.*;
import java.io.*;
import java.lang.reflect.Proxy;

/** Checks the reported edge and paused-slider pixels on the Android framework. */
final class EditorFix23DeviceChecks {
    static void require(boolean value,String message) {
        if(!value)throw new AssertionError(message);
    }
    static void save(Bitmap bitmap,File root,String name)throws Exception {
        try(OutputStream stream=new FileOutputStream(new File(root,name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);
        }
    }
    static void run(Context context,File root)throws Exception {
        float density=context.getResources().getDisplayMetrics().density;
        EditorView editor=new EditorView(context);
        ChartDocument chart=ChartDocument.fromJson("{}");editor.setChart(chart);
        require(!editor.getSettings().tapToSeek,"tap seek defaults off");
        LineEvent event=new LineEvent();event.startTime=BeatTime.zero();event.endTime=new BeatTime(100,0,1);
        chart.judgeLines.get(0).eventLayers.get(0).events(event.type).add(event);
        editor.measure(View.MeasureSpec.makeMeasureSpec(1800,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY));
        editor.layout(0,0,1800,1000);
        Bitmap image=Bitmap.createBitmap(1800,1000,Bitmap.Config.ARGB_8888);editor.draw(new Canvas(image));
        java.lang.reflect.Method splitMethod=Class.forName("com.xpe.mobile.editor.EditorSurfaceView").getDeclaredMethod("editorSplitX");splitMethod.setAccessible(true);
        java.lang.reflect.Method topMethod=Class.forName("com.xpe.mobile.editor.EditorSurfaceView").getDeclaredMethod("toolbarHeight");topMethod.setAccessible(true);
        int split=Math.round((float)splitMethod.invoke(editor)),top=Math.round((float)topMethod.invoke(editor));
        int x=split+Math.round(5*density);
        int header=image.getPixel(x,top+Math.round(5*density));
        int body=image.getPixel(x,top+Math.round(35*density));
        require(header==body,"Events body must reach through the type-name strip");
        save(image,root,"fix23-events-grid.png");image.recycle();

        int width=Math.round(38*density),height=Math.round(60*density);
        Bitmap labels=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        float scale=96*density,baseline=height-28*density;
        double position=10+(height+4*density-baseline)/scale;
        editor.drawTimelineBeatNumbers(new Canvas(labels),0,0,width,height,position,scale,false);
        int lit=0;
        for(int yy=Math.max(0,height-Math.round(20*density));yy<height;yy++)for(int xx=0;xx<width;xx++)
            if(Color.alpha(labels.getPixel(xx,yy))>0)lit++;
        require(lit>0,"edge number appears before its anchor enters the viewport");
        save(labels,root,"fix23-bottom-edge-number.png");labels.recycle();

        editor.setPackageOffsetMs(250);
        editor.setCallback((EditorView.Callback)Proxy.newProxyInstance(EditorView.Callback.class.getClassLoader(),
                new Class<?>[]{EditorView.Callback.class},(proxy,method,args)->{
                    if(method.getName().equals("isAudioReady"))return true;
                    if(method.getName().equals("audioDurationMs"))return 10000L;
                    if(method.getName().equals("audioPositionMs"))return 1000L;
                    if(method.getReturnType()==boolean.class)return false;
                    if(method.getReturnType()==long.class)return 0L;
                    return null;
                }));
        int seekWidth=Math.round(300*density),seekHeight=Math.round(48*density);
        Bitmap seek=Bitmap.createBitmap(seekWidth,seekHeight,Bitmap.Config.ARGB_8888);
        float left=10*density,right=seekWidth-10*density;
        for(double seconds:new double[]{4,6}) {
            editor.shaderBeginPan();editor.shaderPanTo(seconds);editor.shaderEndPan();
            seek.eraseColor(Color.TRANSPARENT);editor.drawShaderPlaybackSeek(new Canvas(seek),left,right);
            double fraction=chart.beatToAudioMillis(chart.secondsToBeat(seconds),250)/10000.0;
            int thumb=Math.round(left+(float)fraction*(right-left));
            int pixel=seek.getPixel(thumb,Math.round(28*density));
            require(Color.green(pixel)>Color.red(pixel),"paused slider follows chart time with package offset");
            int stale=Math.round(left+.1f*(right-left));
            require(seek.getPixel(stale,Math.round(21*density))==Color.TRANSPARENT,"thumb is not at stale audio position");
        }
        save(seek,root,"fix23-paused-seek.png");seek.recycle();
        System.out.println("PASS fixes23 Android pixels: Events under header, entering edge number, paused slider follows timeline with package offset");
    }
}
