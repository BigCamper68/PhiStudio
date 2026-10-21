package com.xpe.mobile.noiseeditor;

import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;
import android.widget.*;
import com.xpe.mobile.R;
import com.xpe.mobile.model.NoiseField;
import com.xpe.mobile.noiseeditor.NoiseFieldEdits.Channel;
import com.xpe.mobile.shadereditor.ShaderEditorViewTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w800dp-h360dp-land-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class NoiseFieldUsabilityTest extends ShaderEditorViewTestSupport {
    private NoiseFieldEditorView noise;
    @Before public void open() {
        workbench.switchTab(4); noise = workbench.activeNoiseEditor();
        noise.run(() -> noise.document.add(1, 1, false));
        editor.shaderEditorSeek(1.25); layout(800, 360);
    }
    private EditText input(int id) { return noise.form.findViewWithTag(id); }
    private Button action(int id) {
        return buttons(noise).stream().filter(b -> b.getText().toString().equals(activity.getString(id))).findFirst().orElseThrow();
    }
    private void filter(NoiseFieldBrowser browser, int index) { browser.filters.getChildAt(index).performClick(); }

    @Test public void deleteIsVisibleWithoutScrollingAndTargetsFieldOrKeyWithUndo() throws Exception {
        for (int width : new int[] {640, 800, 1200}) {
            layout(width, 360); Rect visible = new Rect();
            assertTrue(noise.delete.getGlobalVisibleRect(visible)); assertEquals(32, visible.height());
            assertEquals(noise.delete.getWidth(), visible.width());
        }
        NoiseField original = noise.document.field(); noise.delete.performClick();
        assertTrue(noise.document.chart.noiseFields.isEmpty()); assertFalse(noise.delete.isEnabled());
        noise.undo(); assertSame(original, noise.document.field());
        noise.selectChannel(Channel.SCALE); assertFalse(noise.delete.isEnabled());
        noise.run(() -> noise.document.addKey(1.5)); NoiseField keyed = noise.document.field();
        assertEquals(activity.getString(R.string.noise_delete_key), noise.delete.getText().toString());
        noise.delete.performClick(); assertEquals(1, noise.document.count()); assertTrue(noise.document.field().scale.isEmpty());
        noise.undo(); assertSame(keyed, noise.document.field());
    }

    @Test public void centerSizeEditingKeepsDraftAndUnknownPrecisionAcrossDisplaySwitches() throws Exception {
        JSONObject raw = NoiseFieldEdits.copy(noise.document.field().toJson());
        raw.getJSONObject("bottomLeftPercentage").put("x", .123456789012345).put("extension", 71);
        raw.put("future", true); noise.document.replace(noise.document.field(), NoiseField.fromJson(raw)); noise.rebuild();
        NoiseField before = noise.document.field(); long revision = editor.getChart().revision();
        input(R.string.noise_top).setText("120"); input(R.string.noise_name).setText("Draft name");
        action(R.string.noise_bounds).performClick();
        assertEquals(revision + 2, editor.getChart().revision());
        assertFalse(noise.forms.dirty()); assertEquals("Draft name", input(R.string.noise_name).getText().toString());
        input(R.string.noise_center_y).setText("80"); input(R.string.noise_height).setText("-40");
        action(R.string.noise_center_size).performClick();
        assertEquals("100", input(R.string.noise_bottom).getText().toString());
        assertEquals("60", input(R.string.noise_top).getText().toString());
        assertTrue(noise.validateInputs()); JSONObject after = noise.document.field().toJson();
        assertEquals(.123456789012345, after.getJSONObject("bottomLeftPercentage").getDouble("x"), 0);
        assertEquals(71, after.getJSONObject("bottomLeftPercentage").getInt("extension")); assertTrue(after.getBoolean("future"));
        assertEquals("Draft name", after.getString(NoiseFieldEdits.LABEL));
        noise.undo(); noise.undo(); noise.undo(); noise.undo();
        assertSame(before, noise.document.field()); assertEquals(revision + 8, editor.getChart().revision());
    }

    @Test public void switchingCoordinatesWithoutEditingDoesNotRewriteAnyNativeValues() throws Exception {
        String raw = noise.document.field().toJson().toString(); long revision = editor.getChart().revision();
        for (int i = 0; i < 3; i++) {
            action(R.string.noise_bounds).performClick(); action(R.string.noise_center_size).performClick();
        }
        assertFalse(noise.forms.dirty()); assertTrue(noise.validateInputs());
        assertEquals(raw, noise.document.field().toJson().toString()); assertEquals(revision, editor.getChart().revision());
    }

    @Test public void invalidNumbersAndPhaseOrderIdentifyTheInputsAndKeepTheDraft() {
        input(R.string.noise_left).setText("NaN"); assertNotNull(input(R.string.noise_left).getError());
        assertFalse(noise.validateInputs()); action(R.string.noise_bounds).performClick();
        assertEquals("NaN", input(R.string.noise_left).getText().toString());
        input(R.string.noise_left).setText("25"); assertNull(input(R.string.noise_left).getError());
        input(R.string.noise_appear).setText("100");
        assertNotNull(input(R.string.noise_appear).getError()); assertNotNull(input(R.string.noise_disappear).getError());
        assertFalse(noise.validateInputs()); input(R.string.noise_appear).setText("2");
        assertNull(input(R.string.noise_appear).getError()); assertNull(input(R.string.noise_disappear).getError());
        assertTrue(noise.validateInputs());
    }

    @Test public void browserFiltersAtTheCapturedPlayheadAndKeepsRawFieldIdentity() throws Exception {
        noise.run(() -> noise.document.add(6, 2, true));
        noise.run(() -> noise.document.add(0, 3, false));
        List<NoiseField> nativeOrder = new ArrayList<>(editor.getChart().noiseFields);
        NoiseFieldBrowser browser = new NoiseFieldBrowser(noise); AlertDialog dialog = browser.show();
        assertEquals(List.of(2, 0, 1), browser.indices);
        View selected = browser.list.getAdapter().getView(0, null, browser.list); assertTrue(selected.isSelected());
        filter(browser, 1); assertEquals(List.of(2, 0), browser.indices);
        editor.shaderEditorSeek(7); filter(browser, 0); filter(browser, 1);
        assertEquals(List.of(2, 0), browser.indices); // A labelled snapshot, not a list moving under the finger.
        filter(browser, 3); assertEquals(List.of(1), browser.indices);
        browser.search.setText("nothing like this"); assertEquals(0, browser.list.getAdapter().getCount());
        browser.search.setText(""); browser.list.performItemClick(null, 0, 1);
        assertFalse(dialog.isShowing()); assertEquals(1, noise.document.selected); assertEquals(7, noise.time(), 0);
        assertEquals(nativeOrder, editor.getChart().noiseFields);
    }

    @Test public void browserDiscardsStaleIndicesAfterAnExternalDelete() {
        noise.run(() -> noise.document.add(6, 2, true)); NoiseFieldBrowser browser = new NoiseFieldBrowser(noise);
        AlertDialog dialog = browser.show(); noise.run(noise.document::delete);
        browser.list.performItemClick(null, 1, 1);
        assertEquals(0, noise.document.selected); assertFalse(dialog.isShowing());
    }

    @Test public void returningAfterExternalUndoDiscardsAnOrphanDraftWithoutReplacingAnotherField() {
        NoiseField original = noise.document.field();
        noise.run(() -> noise.document.add(3, 2, false)); input(R.string.noise_name).setText("Orphan draft");
        workbench.switchTab(0); noise.document.undo(); noise.document.undo(); workbench.switchTab(4);
        assertSame(original, noise.displayedField()); assertFalse(noise.forms.dirty()); assertNull(noise.previewFields());
        assertEquals("", noise.document.name(0));
        input(R.string.noise_top).setText("130"); workbench.switchTab(0); noise.document.undo(); noise.document.undo(); workbench.switchTab(4);
        assertNull(noise.displayedField()); assertFalse(noise.forms.dirty()); assertNull(noise.previewFields());
        noise.preview.draw(new Canvas()); noise.selectionOverlay.draw(new Canvas());
        noise.redo(); assertSame(original, noise.document.field());
    }

    @Test public void browserAdaptsToTheKeyboardWithoutLosingSearchOrFilterState() {
        NoiseFieldBrowser browser = new NoiseFieldBrowser(noise); filter(browser, 1); browser.search.setText("Field");
        for (int height : new int[] {250, 110, 250}) {
            browser.body.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
            browser.body.layout(0, 0, 500, browser.body.getMeasuredHeight());
            assertTrue(browser.body.getHeight() <= height); assertTrue(browser.list.getHeight() >= 64);
            assertTrue(browser.list.getBottom() <= browser.body.getHeight());
            assertEquals("Field", browser.search.getText().toString()); assertEquals(List.of(0), browser.indices);
            assertEquals(height < 160 ? View.GONE : View.VISIBLE, browser.count.getVisibility());
        }
    }

    @Test public void anchorPresetsUseScreenCoordinatesAndKeepTheOtherPendingValues() throws Exception {
        noise.selectChannel(Channel.ROTATE); noise.run(() -> noise.document.addKey(1));
        NoiseField before = noise.document.field(); input(R.string.noise_angle).setText("270");
        action(R.string.noise_anchor_presets).performClick();
        AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        List<Button> choices = buttons(dialog.getWindow().getDecorView());
        choices.stream().filter(b -> "↗".equals(b.getText().toString())).findFirst().orElseThrow().performClick();
        assertFalse(dialog.isShowing()); assertNotSame(before, noise.document.field());
        assertEquals("100", input(R.string.noise_anchor_x).getText().toString());
        assertEquals("100", input(R.string.noise_anchor_y).getText().toString()); assertTrue(noise.validateInputs());
        JSONObject key = NoiseFieldEdits.key(noise.document.field(), Channel.ROTATE, 0);
        assertEquals(270, key.getDouble("rotation"), 0); assertEquals(1, key.getJSONObject("anchor").getDouble("y"), 0);
        noise.undo(); assertEquals(270, NoiseFieldEdits.key(noise.document.field(), Channel.ROTATE, 0).getDouble("rotation"), 0);
        noise.undo(); assertSame(before, noise.document.field());
    }

    @Test public void previousAndNextKeysSeekChronologicallyWithoutChangingRawIndices() throws Exception {
        noise.selectChannel(Channel.MOVE);
        noise.run(() -> noise.document.addKey(2)); noise.run(() -> noise.document.addKey(1));
        NoiseField before = noise.document.field();
        Button next = buttons(noise).stream().filter(b -> "›".equals(b.getText().toString())).findFirst().orElseThrow();
        next.performClick(); assertEquals(0, noise.document.selectedKey); assertEquals(2, noise.time(), 0);
        Button previous = buttons(noise).stream().filter(b -> "‹".equals(b.getText().toString())).findFirst().orElseThrow();
        previous.performClick(); assertEquals(1, noise.document.selectedKey); assertEquals(1, noise.time(), 0);
        assertSame(before, noise.document.field());
    }

    @Test public void guidesUseTheFittedViewportAndOnlyDrawBoundedReadOnlyGeometry() {
        var settings = editor.getSettings(); settings.playerWidth = 3; settings.playerHeight = 2; editor.applySettings(settings);
        NoiseField before = noise.document.field();
        List<float[]> lines = new ArrayList<>(); List<String> labels = new ArrayList<>();
        Canvas canvas = new Canvas() {
            @Override public void drawLine(float x, float y, float x2, float y2, Paint paint) { lines.add(new float[] {x, y, x2, y2}); }
            @Override public void drawText(String text, float x, float y, Paint paint) { labels.add(text); }
        };
        new NoiseFieldSelectionOverlay(noise).draw(canvas, 480, 200);
        assertFalse(lines.isEmpty());
        for (float[] line : lines) {
            assertTrue(line[0] >= 90 && line[0] <= 390); assertTrue(line[2] >= 90 && line[2] <= 390);
            assertTrue(line[1] >= 0 && line[1] <= 200); assertTrue(line[3] >= 0 && line[3] <= 200);
        }
        assertTrue(labels.get(0).contains("Active")); assertSame(before, noise.document.field());
        editor.shaderEditorSeek(5); labels.clear(); new NoiseFieldSelectionOverlay(noise).draw(canvas, 480, 200);
        assertTrue(labels.get(0).contains("Outside lifetime")); assertSame(before, noise.document.field());
    }

    @Test public void guidesHaveAnIndependentLayerAboveGpuAndNeverCaptureTouchOrApplyDraft() {
        assertTrue(noise.indexOfChild(noise.selectionOverlay) > noise.indexOfChild(noise.gpu));
        assertEquals(noise.preview.getWidth(), noise.selectionOverlay.getWidth());
        assertEquals(noise.preview.getTop(), noise.selectionOverlay.getTop());
        android.view.MotionEvent touch = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 50, 50, 0);
        assertFalse(noise.selectionOverlay.dispatchTouchEvent(touch)); touch.recycle();
        input(R.string.noise_top).setText("130"); NoiseField before = noise.document.field();
        EditText same = input(R.string.noise_top); noise.more();
        AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        dialog.getListView().performItemClick(null, 8, 8);
        assertFalse(noise.guides); assertSame(same, input(R.string.noise_top)); assertSame(before, noise.document.field());
        assertFalse(noise.forms.dirty()); assertEquals("130", input(R.string.noise_top).getText().toString());
    }
}
