package com.xpe.mobile.app;

import android.app.AlertDialog;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.widget.RadioButton;
import com.xpe.mobile.R;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.model.ChartDocument;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33, 34}, qualifiers = "w1024dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlaytestDisplayScaleTest {
    @Test public void optionsAndStartActionsRemainReachableWithLargeFontsAndDisplayScaling() throws Exception {
        for (int dpi : new int[]{160, 320}) for (float font : new float[]{1, 2})
            for (int width : new int[]{1024, 1536}) for (int height : new int[]{360, 720}) {
                float density = dpi / 160f;
                RuntimeEnvironment.setQualifiers("w" + Math.round(width / density) + "dp-h"
                        + Math.round(height / density) + "dp-land-" + dpi + "dpi");
                RuntimeEnvironment.setFontScale(font);
                MainActivity a = Robolectric.buildActivity(MainActivity.class).get(); a.setTheme(R.style.AppTheme);
                a.editorView = new EditorView(a); a.editorView.setChart(ChartDocument.fromJson("{}"));
                a.getPreferences(0).edit().clear().commit();
                AlertDialog d = PlaytestOptionsDialog.show(a, a.editorView.getChart(), 0, 0, s -> { });
                try {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    View decor = d.getWindow().getDecorView();
                    RadioButton custom = decor.findViewWithTag("start.custom"); custom.performClick();
                    int w = d.getWindow().getAttributes().width, h = d.getWindow().getAttributes().height;
                    for (int pass = 0; pass < 3; pass++) {
                        decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                        decor.layout(0, 0, w, h);
                    }
                    String label = dpi + "dpi font=" + font + " " + width + "x" + height;
                    for (int id : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
                        View button = d.getButton(id); Rect visible = new Rect();
                        assertTrue(label, button.getGlobalVisibleRect(visible));
                        assertTrue(label, visible.width() >= button.getWidth() - 1);
                        assertTrue(label, visible.height() >= button.getHeight() - 1);
                    }
                    for (String tag : new String[]{"start.beginning", "start.playhead", "start.custom",
                            "custom.time", "custom.beat", "countdown.0", "countdown.2", "countdown.3", "countdown.5"}) {
                        View target = decor.findViewWithTag(tag); assertTrue(label + tag, target.getWidth() > 0);
                        target.requestRectangleOnScreen(new Rect(0, 0, target.getWidth(), target.getHeight()), true);
                        Rect visible = new Rect(); assertTrue(label + tag, target.getGlobalVisibleRect(visible));
                        assertTrue(label + tag, visible.width() >= target.getWidth() - 1);
                    }
                } finally { d.dismiss(); }
            }
    }
}
