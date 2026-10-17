package com.xpe.mobile;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.shadereditor.ShaderDocument;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.*;

abstract class ShaderEditorViewTestSupport {
    protected Activity activity;

    protected EditorView editor;

    protected WorkbenchView workbench;

    protected ShaderDocument document;

    protected ShaderEditorView shaders;

    protected int updates;

    @Before
    public void setup() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        editor = new EditorView(activity);
        editor.setChart(ChartDocument.fromJson("{}"));
        workbench = new WorkbenchView(activity, editor, () -> new TextView(activity));
        activity.setContentView(workbench);
        document = new ShaderDocument(
            null, editor.getChart(), name -> "uniform float power; // %0.5%\nvoid main(){}");
        document.add("noise", 0);
        shaders = new ShaderEditorView(activity, editor, document, new ShaderEditorView.Host() {
            public void changed() {
                updates++;
            }
            public void importShader() {}
            public void save() {}
        });
        workbench.setShaderOpener(() -> workbench.showShaders(shaders));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        layout(1200, 700);
    }

    protected void layout(int width, int height) {
        workbench.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        workbench.layout(0, 0, width, height);
    }

    protected Button button(String label) {
        return buttons(shaders)
            .stream()
            .filter(b -> b.getText().toString().equals(label))
            .findFirst()
            .orElseThrow();
    }

    protected List<Button> buttons(View view) {
        List<Button> list = new ArrayList<>();
        if (view instanceof Button)
            list.add((Button) view);
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                list.addAll(buttons(((ViewGroup) view).getChildAt(i)));
        return list;
    }

    protected List<EditText> fields(View view) {
        List<EditText> list = new ArrayList<>();
        if (view instanceof EditText)
            list.add((EditText) view);
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                list.addAll(fields(((ViewGroup) view).getChildAt(i)));
        return list;
    }

    protected void collectSpinners(View view, List<Spinner> list) {
        if (view instanceof Spinner)
            list.add((Spinner) view);
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                collectSpinners(((ViewGroup) view).getChildAt(i), list);
    }

    protected float scale() {
        return editor.timelineScale();
    }

    protected android.graphics.RectF drawFirstBlock() throws Exception {
        Bitmap image = Bitmap.createBitmap(
            shaders.timeline.getWidth(), shaders.timeline.getHeight(), Bitmap.Config.ARGB_8888);
        shaders.timeline.draw(new Canvas(image));
        java.lang.reflect.Field field = shaders.timeline.getClass().getDeclaredField("hits");
        field.setAccessible(true);
        Object hit = ((List<?>) field.get(shaders.timeline)).get(0);
        java.lang.reflect.Field rectField = hit.getClass().getDeclaredField("rect");
        rectField.setAccessible(true);
        android.graphics.RectF rect =
            new android.graphics.RectF((android.graphics.RectF) rectField.get(hit));
        image.recycle();
        return rect;
    }

    protected void pinch(long time, int action, float first, float second) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = i == 0 ? first : second;
            coords[i].y = 100;
            coords[i].pressure = 1;
            coords[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(10, time, action, 2, properties, coords, 0, 0, 1, 1,
            0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        shaders.timeline.onTouchEvent(event);
        event.recycle();
    }

    protected void touch(long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(10, time, action, x, y, 0);
        shaders.timeline.onTouchEvent(event);
        event.recycle();
    }
}
