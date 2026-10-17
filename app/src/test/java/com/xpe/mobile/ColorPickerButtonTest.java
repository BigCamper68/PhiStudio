package com.xpe.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, qualifiers="w800dp-h360dp-land-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class ColorPickerButtonTest {
    private Activity activity;
    @Before public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
    }
    private View find(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) for (int i=0;i<((ViewGroup)root).getChildCount();i++) {
            View found=find(((ViewGroup)root).getChildAt(i),description); if(found!=null)return found;
        }
        return null;
    }
    private void assertNoTextInputs(View root) {
        assertFalse(root instanceof EditText);
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) assertNoTextInputs(((ViewGroup)root).getChildAt(i));
    }
    @Test public void paletteDialogIsCenteredAndCancelDoesNotCommit() {
        ColorPickerButton button=new ColorPickerButton(activity,"Color",0x80123456,true);
        activity.setContentView(button);
        int[] commits={0};button.setOnColorChanged(v->commits[0]++);
        button.performClick(); AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
        assertEquals(Gravity.CENTER,dialog.getWindow().getAttributes().gravity);
        View root=dialog.getWindow().getDecorView();assertNoTextInputs(root);
        ((SeekBar)find(root,"Red")).setProgress(200);
        ((SeekBar)find(root,"Alpha")).setProgress(73);
        assertEquals(0x80123456,button.getColor());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(0,commits[0]);assertEquals(0x80123456,button.getColor());
        button.performClick();dialog=ShadowAlertDialog.getLatestAlertDialog();root=dialog.getWindow().getDecorView();
        ((SeekBar)find(root,"Red")).setProgress(200);
        ((SeekBar)find(root,"Alpha")).setProgress(73);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(Color.argb(73,200,0x34,0x56),button.getColor());assertEquals(1,commits[0]);
    }
    @Test public void paletteStaysFixedWhileRightSettingsScroll() {
        ColorPickerButton button=new ColorPickerButton(activity,"Color",0xffff0000,true);
        activity.setContentView(button); button.performClick();
        View root=ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView();
        for (int n=0;n<3;n++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(760,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY));
            root.layout(0,0,760,300);
        }
        View palette=find(root,"Saturation and brightness palette");
        assertTrue(palette.getWidth()>100); assertTrue(palette.getHeight()>40);
        for(android.view.ViewParent v=palette.getParent();v!=null;v=v.getParent())
            assertFalse(v instanceof ScrollView);
        View hue=find(root,"Hue");
        ScrollView scroll=(ScrollView)hue.getParent().getParent();
        int[] before=new int[2],after=new int[2];palette.getLocationOnScreen(before);
        scroll.scrollTo(0,100); palette.getLocationOnScreen(after);
        assertArrayEquals(before,after);assertTrue(scroll.getScrollY()>0);
        View left=(View)palette.getParent();
        assertSame(left.getParent(),scroll.getParent());
        assertTrue(scroll.getLeft()>left.getRight());
    }
    @Test public void rgbPaletteUsesTouchAndPreservesOpaqueRgb() {
        ColorPickerButton button=new ColorPickerButton(activity,"Line color",0x123456,false);
        activity.setContentView(button);button.performClick();
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();
        assertNull(find(root,"Alpha"));
        View palette=find(root,"Saturation and brightness palette");assertNotNull(palette);
        palette.layout(0,0,300,150);
        MotionEvent event=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,0,0,0);
        palette.onTouchEvent(event);event.recycle();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(Color.WHITE,button.getColor());
    }
}
