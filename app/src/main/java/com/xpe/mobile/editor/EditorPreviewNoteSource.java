package com.xpe.mobile.editor;

import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import com.xpe.mobile.R;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.preview.PreviewNotePicker;
import com.xpe.mobile.preview.RenderScene;

/** Preview-to-source navigation shares the ordinary line selection and note editor. */
final class EditorPreviewNoteSource {
    private final EditorChromeView view;
    private RenderScene scene;
    private ChartDocument frameChart, sourceChart;
    private EditorSettings frameSettings;
    private long frameRevision, sourceRevision;
    private int frameWidth, frameHeight, sourceLine, sourceNoteIndex;
    private final RectF viewport = new RectF();
    private JudgeLine sourceJudgeLine;
    private Note sourceNote;
    private PreviewNotePicker.Hit down;
    private boolean held, fromHover;
    private Runnable holdTask, hoverTask;

    EditorPreviewNoteSource(EditorChromeView view) { this.view = view; }

    void frame(RenderScene frame, RectF area, EditorSettings settings) {
        scene = frame; viewport.set(area); frameChart = view.chart;
        frameSettings = settings;
        frameRevision = view.chart.revision(); frameWidth = view.getWidth(); frameHeight = view.getHeight();
    }
    private PreviewNotePicker.Hit pick(float x, float y, float padding) {
        if (!view.previewMode || scene == null || frameChart != view.chart || frameSettings != view.settings
                || frameRevision != view.chart.revision() || frameWidth != view.getWidth() || frameHeight != view.getHeight()) return null;
        return view.previewRenderer.pickNote(scene, viewport, view.settings, x, y, padding);
    }
    void down(float x, float y) {
        cancelGesture(); clearHover();
        down = pick(x, y, 8 * view.density);
        if (down == null) { clearSource(); return; }
        holdTask = () -> {
            holdTask = null;
            if (down != null && view.previewMode && !view.movedDuringGesture) {
                held = true; show(down, false); view.invalidate();
            }
        };
        view.postDelayed(holdTask, ViewConfiguration.getLongPressTimeout());
    }
    void moved() { cancelGesture(); clearSource(); clearHover(); }
    void up() {
        PreviewNotePicker.Hit hit = down; boolean longPress = held;
        cancelGesture();
        if (hit != null && !longPress && show(hit, false)) selectSource();
    }
    void cancelGesture() {
        clearHover();
        if (holdTask != null) view.removeCallbacks(holdTask);
        holdTask = null; down = null; held = false;
    }
    boolean hover(MotionEvent event) {
        if (!view.previewMode) { clearHover(); return false; }
        clearHover();
        if (hasSource() && event.getY() >= view.getHeight() - 25 * view.density
                && event.getActionMasked() != MotionEvent.ACTION_HOVER_EXIT) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
            if (fromHover) { clearSource(); view.invalidate(); }
            return true;
        }
        float x = event.getX(), y = event.getY();
        hoverTask = () -> {
            hoverTask = null;
            PreviewNotePicker.Hit hit = pick(x, y, 0);
            if (hit != null) show(hit, true); else if (fromHover) clearSource();
            view.invalidate();
        };
        view.postDelayed(hoverTask, ViewConfiguration.getLongPressTimeout());
        return true;
    }
    private void clearHover() {
        if (hoverTask != null) view.removeCallbacks(hoverTask);
        hoverTask = null;
    }
    private boolean show(PreviewNotePicker.Hit hit, boolean hover) {
        if (view.chart == null || hit.lineIndex < 0 || hit.lineIndex >= view.chart.judgeLines.size()) return false;
        JudgeLine line = view.chart.judgeLines.get(hit.lineIndex);
        int noteIndex = line.notes.indexOf(hit.note);
        if (noteIndex < 0) return false;
        sourceChart = view.chart; sourceJudgeLine = line; sourceLine = hit.lineIndex;
        sourceNote = hit.note; sourceNoteIndex = noteIndex; sourceRevision = view.chart.revision();
        fromHover = hover; return true;
    }
    boolean hasSource() {
        if (!view.previewMode || sourceChart != view.chart || sourceNote == null) return false;
        if (sourceRevision != view.chart.revision()) {
            sourceLine = view.chart.judgeLines.indexOf(sourceJudgeLine);
            sourceNoteIndex = sourceJudgeLine.notes.indexOf(sourceNote);
            sourceRevision = view.chart.revision();
            if (sourceLine < 0 || sourceNoteIndex < 0) { clearSource(); return false; }
        }
        return true;
    }
    String description() {
        return !hasSource() ? null : view.getResources().getString(R.string.preview_note_source,
                sourceLine, sourceJudgeLine.name, sourceNoteIndex + 1, sourceNote.startTime.toString());
    }
    private boolean selectSource() {
        // Recheck identities even if a caller changed a list without a revision notification.
        if (!hasSource() || view.chart.judgeLines.indexOf(sourceJudgeLine) < 0
                || !sourceJudgeLine.notes.contains(sourceNote)) { clearSource(); return false; }
        view.clearSelection();
        view.lineIndex = view.chart.judgeLines.indexOf(sourceJudgeLine);
        view.eventLayerIndex = Math.max(0, Math.min(view.eventLayerIndex, sourceJudgeLine.eventLayers.size() - 1));
        view.selectedNote = sourceNote; view.selectedNotes.add(sourceNote);
        return true;
    }
    void openNote() {
        if (!selectSource()) return;
        Note target = sourceNote;
        view.stopPlayback(); view.cancelActiveGesture();
        view.previewMode = false; view.workbenchTab = 0;
        view.tool = EditorSurfaceView.Tool.SELECT;
        view.rectangleSelectionMode = EditorSurfaceView.RectangleSelectionMode.NONE;
        view.activateEditPanel();
        view.currentBeat = Math.max(0, target.startTime.toDouble());
        view.previewPlayback.reset();
        if (view.callback != null && view.callback.isAudioReady())
            view.callback.seekAudio(view.editorPlayback.audioPositionMillis());
        if (view.shaderView != null) view.shaderView.hidePreview();
        reset(); view.invalidate();
    }
    private void clearSource() { sourceChart = null; sourceNote = null; sourceJudgeLine = null; fromHover = false; }
    void reset() { cancelGesture(); clearHover(); clearSource(); scene = null; frameChart = null; }
}
