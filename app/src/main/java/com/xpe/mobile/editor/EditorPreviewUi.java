package com.xpe.mobile.editor;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import com.xpe.mobile.R;
import com.xpe.mobile.preview.ChartEvaluator;
import com.xpe.mobile.preview.PreviewShaderView;
import com.xpe.mobile.preview.RenderScene;

/** EditorPreviewUi responsibilities extracted from EditorChromeView. */
final class EditorPreviewUi {
    private final EditorChromeView delegateOwner;

    EditorPreviewUi(EditorChromeView delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void drawShaderEditorPreview(Canvas canvas, RectF area, PreviewShaderView surface,
                                        double loopStart, double loopEnd) {
        if (delegateOwner.chart == null || area.isEmpty()) return;
        if (delegateOwner.playing) delegateOwner.advancePlayback();
        if (delegateOwner.playing && !delegateOwner.playbackScrubbing && loopEnd > loopStart && delegateOwner.shaderEditorSeconds() >= loopEnd) delegateOwner.shaderEditorSeek(loopStart);
        RenderScene scene = ChartEvaluator.evaluate(delegateOwner.chart, delegateOwner.currentBeat, delegateOwner.settings.highlightSimultaneousNotes,
                delegateOwner.callback == null ? -1L : delegateOwner.callback.audioDurationMs(), delegateOwner.useRpe170Speed,
                delegateOwner.settings.playerWidth / (double) delegateOwner.settings.playerHeight, delegateOwner.previewPlayback);
        PreviewViewportCalculator.Result fitted = PreviewViewportCalculator.fit(area.left, area.top,
                area.right, area.bottom, delegateOwner.settings.playerWidth, delegateOwner.settings.playerHeight);
        RectF viewport = new RectF((float) fitted.left, (float) fitted.top, (float) fitted.right, (float) fitted.bottom);
        if (!surface.submit(delegateOwner.previewRenderer, scene, viewport, area, delegateOwner.blurredBackgroundIllustration,
                delegateOwner.settings, delegateOwner.lineIndex, delegateOwner.chart.getBackgroundDim(), delegateOwner.previewShaders, delegateOwner.shaderEditorSeconds()))
            delegateOwner.previewRenderer.draw(canvas, scene, viewport, area, delegateOwner.blurredBackgroundIllustration,
                    delegateOwner.settings, delegateOwner.lineIndex, delegateOwner.chart.getBackgroundDim());
    }

    void drawPreview(Canvas canvas) {
        RenderScene scene = ChartEvaluator.evaluate(
                delegateOwner.chart, delegateOwner.currentBeat, delegateOwner.settings.highlightSimultaneousNotes,
                delegateOwner.callback == null ? -1L : delegateOwner.callback.audioDurationMs(),
                delegateOwner.useRpe170Speed, delegateOwner.settings.playerWidth / (double) delegateOwner.settings.playerHeight, delegateOwner.previewPlayback);
        float barHeight = 25f * delegateOwner.density;
        RectF previewArea = new RectF(0f, delegateOwner.toolbarHeight(), delegateOwner.getWidth(),
                delegateOwner.getHeight() - barHeight);
        PreviewViewportCalculator.Result fitted = PreviewViewportCalculator.fit(
                previewArea.left, previewArea.top, previewArea.right, previewArea.bottom,
                delegateOwner.settings.playerWidth, delegateOwner.settings.playerHeight);
        RectF viewport = new RectF((float) fitted.left, (float) fitted.top,
                (float) fitted.right, (float) fitted.bottom);
        boolean submitted = delegateOwner.shaderView != null && delegateOwner.shaderView.submit(delegateOwner.previewRenderer, scene,
                viewport, previewArea, delegateOwner.blurredBackgroundIllustration, delegateOwner.settings, delegateOwner.lineIndex,
                delegateOwner.chart.getBackgroundDim(), delegateOwner.previewShaders, delegateOwner.chart.beatToSeconds(delegateOwner.currentBeat));
        if (!submitted) delegateOwner.previewRenderer.draw(canvas, scene, viewport, previewArea,
                delegateOwner.blurredBackgroundIllustration, delegateOwner.settings, delegateOwner.lineIndex, delegateOwner.chart.getBackgroundDim());
        delegateOwner.editorToolbarUi.drawToolbar(canvas);

        delegateOwner.paint.setColor(EditorChromeView.withAlpha(Color.BLACK, 150));
        canvas.drawRect(0f, delegateOwner.getHeight() - barHeight, delegateOwner.getWidth(), delegateOwner.getHeight(), delegateOwner.paint);
        delegateOwner.paint.setColor(Color.WHITE);
        delegateOwner.paint.setTextSize(10f * delegateOwner.density);
        delegateOwner.paint.setTextAlign(Paint.Align.LEFT);
        String status = delegateOwner.getResources().getString(R.string.preview_status,
                delegateOwner.currentBeat, delegateOwner.chart.bpmAt(delegateOwner.currentBeat), delegateOwner.chart.totalNotes(), delegateOwner.chart.totalEvents());
        canvas.drawText(status, 8f * delegateOwner.density, delegateOwner.getHeight() - 8f * delegateOwner.density, delegateOwner.paint);
    }

    void drawEditor(Canvas canvas) {
        float toolbar = delegateOwner.toolbarHeight();
        float bottom = delegateOwner.getHeight() - delegateOwner.bottomBarHeight();
        float split = delegateOwner.editorSplitX();

        delegateOwner.paint.setStyle(Paint.Style.FILL);
        delegateOwner.drawNoteBackground(canvas, 0f, toolbar, split, bottom);
        delegateOwner.paint.setColor(EditorChromeView.PANEL_COLOR);
        canvas.drawRect(split, toolbar, delegateOwner.getWidth(), bottom, delegateOwner.paint);
        delegateOwner.editorPreviewUi.drawEditorGameplayBackground(canvas, 0f, toolbar, split, bottom);

        delegateOwner.drawNoteGrid(canvas, 0f, toolbar, split, bottom);
        delegateOwner.drawNotes(canvas, 0f, toolbar, split, bottom);
        if (delegateOwner.workbenchTab == 1) delegateOwner.storyboardTimeline.draw(canvas, split, toolbar, delegateOwner.getWidth(), bottom);
        else if (delegateOwner.workbenchTab == 0) {
            int save = canvas.save();
            canvas.clipRect(split, toolbar, delegateOwner.getWidth(), bottom);
            delegateOwner.drawEventGrid(canvas, split, toolbar, delegateOwner.getWidth(), bottom);
            delegateOwner.drawEvents(canvas, split, toolbar, delegateOwner.getWidth(), bottom);
            // Keep the cursor visible over event blocks, as in the Storyboard timeline.
            delegateOwner.drawCurrentBeatLine(canvas, split, delegateOwner.getWidth(), bottom);
            delegateOwner.drawEventHeaders(canvas, split, toolbar, delegateOwner.getWidth());
            canvas.restoreToCount(save);
        }
        delegateOwner.drawPlacementPreview(canvas, split, toolbar, bottom);
        delegateOwner.drawSelectionRectangle(canvas);
        delegateOwner.editorToolbarUi.drawToolbar(canvas);
        delegateOwner.drawBottomBar(canvas);
        int save = canvas.save();
        canvas.clipRect(0f, toolbar, split, bottom);
        delegateOwner.editorToolbarUi.drawStatus(canvas, bottom - 7f * delegateOwner.density);
        canvas.restoreToCount(save);
    }

    void drawEditorGameplayBackground(Canvas canvas, float left, float top,
                                              float right, float bottom) {
        RenderScene scene = ChartEvaluator.evaluate(
                delegateOwner.chart, delegateOwner.currentBeat, delegateOwner.settings.highlightSimultaneousNotes,
                delegateOwner.callback == null ? -1L : delegateOwner.callback.audioDurationMs(),
                delegateOwner.useRpe170Speed, delegateOwner.settings.playerWidth / (double) delegateOwner.settings.playerHeight, delegateOwner.previewPlayback);
        PreviewViewportCalculator.Result fitted = PreviewViewportCalculator.fit(
                left, top, right, bottom, delegateOwner.settings.playerWidth, delegateOwner.settings.playerHeight);
        RectF viewport = new RectF((float) fitted.left, (float) fitted.top,
                (float) fitted.right, (float) fitted.bottom);
        int save = canvas.saveLayerAlpha(viewport, EditorChromeView.EDITOR_GAMEPLAY_ALPHA);
        delegateOwner.previewRenderer.drawGameplayOverlay(canvas, scene, viewport, delegateOwner.settings, delegateOwner.lineIndex);
        canvas.restoreToCount(save);
    }
}
