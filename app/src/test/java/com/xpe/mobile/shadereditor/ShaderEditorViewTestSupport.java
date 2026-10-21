package com.xpe.mobile.shadereditor;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.xpe.mobile.R;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.editor.WorkbenchView;
import com.xpe.mobile.model.ChartDocument;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.annotation.*;

import static org.junit.Assert.*;

public abstract class ShaderEditorViewTestSupport {
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

    public static void selectPage(ShaderEditorView view, int page) { view.page = page; view.buildForm(); }
    public static LinearLayout form(ShaderEditorView view) { return view.form; }
    public static Button undoButton(ShaderEditorView view) { return view.undo; }

    protected View shaderTimeline() { return shaders.timeline; }
    protected View shaderBottom() { return shaders.bottom; }
    protected View shaderToolbar() { return shaders.toolbar; }
    protected Button shaderPlay() { return shaders.play; }
    protected View shaderSeek() { return shaders.playbackSeek; }
    protected boolean shaderBeats() { return shaders.beats; }

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
