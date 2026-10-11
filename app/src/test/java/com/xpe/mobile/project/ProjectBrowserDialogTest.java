package com.xpe.mobile.project;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import com.xpe.mobile.R;
import java.io.File;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w1200dp-h500dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class ProjectBrowserDialogTest {
    private Activity activity;
    private ProjectBrowserDialog browser;
    private ProjectThumbnailLoader thumbnails;
    @Before public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        File root = new File(activity.getFilesDir(), "library-ui-test");
        thumbnails = new ProjectThumbnailLoader(new File(activity.getCacheDir(), "thumbnail-ui-test"));
        browser = new ProjectBrowserDialog(activity, new ProjectLibrary(root), thumbnails);
    }
    @After public void finish() { browser.dismiss(); thumbnails.shutdown(); }
    private LinearLayout show(boolean startup, int width) {
        ProjectRecord record = new ProjectRecord("sample", "Sample", "Sample.zip", "chart.json", null, null, 0, 0);
        ProjectLibrary.Discovery discovery = new ProjectLibrary.Discovery(
                new ProjectLibrary.State(Collections.singletonList(record), "sample"), Collections.emptyList());
        ProjectBrowserDialog.Callback callback = (ProjectBrowserDialog.Callback) java.lang.reflect.Proxy.newProxyInstance(
                ProjectBrowserDialog.Callback.class.getClassLoader(), new Class<?>[]{ProjectBrowserDialog.Callback.class},
                (proxy, method, args) -> null);
        browser.show(discovery, startup, callback);
        Dialog dialog = ShadowDialog.getLatestDialog(); assertNotNull(dialog);
        View decor = dialog.getWindow().getDecorView();
        for (int pass = 0; pass < 3; pass++) {
            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, width, 400);
        }
        ViewGroup content = decor.findViewById(android.R.id.content);
        return (LinearLayout) content.getChildAt(0);
    }
    @Test public void projectsTitleAndAllThreeHeaderButtonsShareTheSameVerticalCenter() {
        for (boolean startup : new boolean[]{false, true}) {
            for (int width : new int[]{640, 864, 1200}) {
                LinearLayout header = (LinearLayout) show(startup, width).getChildAt(0);
                assertFalse(header.isBaselineAligned()); assertEquals(4, header.getChildCount());
                assertEquals(68,header.getHeight());
                for (int k=1;k<4;k++) {
                    View button=header.getChildAt(k);
                    assertEquals(12,button.getTop());assertEquals(12,header.getHeight()-button.getBottom());
                }
                float center = header.getHeight() / 2f;
                for (int index = 0; index < header.getChildCount(); index++) {
                    View child = header.getChildAt(index);
                    assertTrue(child.getHeight() > 0);
                    assertEquals(center, (child.getTop() + child.getBottom()) / 2f, .5f);
                }
            }
        }
    }
    @Test public void removeButtonEdgesMatchTheOuterEdgesOfBothTwoButtonRows() {
        LinearLayout root = show(false, 864);
        Button remove = findButton(root, activity.getString(R.string.project_action_remove));
        assertNotNull(remove);
        LinearLayout card = (LinearLayout) remove.getParent();
        GridLayout grid = (GridLayout) card.getChildAt(card.getChildCount() - 2);
        for (int row = 0; row < 2; row++) {
            View left = grid.getChildAt(row * 2), right = grid.getChildAt(row * 2 + 1);
            // Compare both rows in the common card coordinate space, independent of window attachment.
            assertEquals(grid.getLeft() + left.getLeft(), remove.getLeft());
            assertEquals(grid.getLeft() + right.getRight(), remove.getRight());
        }
    }
    @Test public void projectActionRowsHaveEqualVisibleGapsAndButtonsHaveCenteredContent() {
        LinearLayout root=show(false,864);
        Button remove=findButton(root,activity.getString(R.string.project_action_remove));
        LinearLayout card=(LinearLayout)remove.getParent();
        GridLayout grid=(GridLayout)card.getChildAt(card.getChildCount()-2);
        View open=grid.getChildAt(0),rename=grid.getChildAt(2);
        assertEquals(rename.getTop()-open.getBottom(),remove.getTop()-(grid.getTop()+rename.getBottom()));
        for(ViewGroup group:new ViewGroup[]{(ViewGroup)root.getChildAt(0),grid}) {
            for(int i=0;i<group.getChildCount();i++) if(group.getChildAt(i) instanceof Button) {
                Button b=(Button)group.getChildAt(i);
                assertEquals(44,b.getHeight());assertEquals(b.getPaddingTop(),b.getPaddingBottom());
                assertEquals(android.view.Gravity.CENTER,b.getGravity());
            }
        }
    }
    private Button findButton(View root, String text) {
        if (root instanceof Button && text.equals(((Button) root).getText().toString())) return (Button) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                Button result = findButton(group.getChildAt(index), text);
                if (result != null) return result;
            }
        }
        return null;
    }
}
