package com.xpe.mobile.editor;

import android.graphics.Bitmap;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.EditorChromeView.BottomPanel;
import com.xpe.mobile.editor.EditorSurfaceView.GestureMode;
import com.xpe.mobile.editor.EditorSurfaceView.RectangleSelectionMode;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.preview.PreviewTextureDecoder;
import com.xpe.mobile.preview.ShaderTimeline;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** EditorSessionUi responsibilities extracted from EditorChromeView. */
final class EditorSessionUi {
    private final EditorChromeView delegateOwner;

    EditorSessionUi(EditorChromeView delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void applySettings(EditorSettings value) {
        delegateOwner.settings = value == null ? new EditorSettings() : value.copy();
        if (delegateOwner.chart != null) {
            delegateOwner.xyBindingEnabled = delegateOwner.settings.xyBindingEnabled
                    && XYBindingValidator.isChartSynchronized(delegateOwner.chart);
        }
        if (delegateOwner.playing) delegateOwner.editorPlayback.prepareHitSoundPlayback(false);
        delegateOwner.invalidate();
    }

    boolean clearTimelineSelectionOnTap() {
        boolean selected = delegateOwner.selectedNote != null || delegateOwner.selectedEvent != null
                || !delegateOwner.selectedNotes.isEmpty() || !delegateOwner.selectedEvents.isEmpty()
                || (delegateOwner.storyboardTimeline != null && delegateOwner.storyboardTimeline.selectionCount() > 0);
        if (selected) { delegateOwner.clearSelection(); delegateOwner.invalidate(); }
        return selected;
    }

    void setChart(ChartDocument chart) {
        delegateOwner.previewPlayback.reset();
        delegateOwner.setPreviewShaders(ShaderTimeline.EMPTY);
        delegateOwner.setBackgroundIllustration(null);
        delegateOwner.setPreviewLineTextures(null);
        delegateOwner.chart = chart;
        if (delegateOwner.storyboardTimeline != null) delegateOwner.storyboardTimeline.reset();
        delegateOwner.workbenchTab = 0;
        delegateOwner.lineIndex = 0;
        delegateOwner.eventLayerIndex = 0;
        delegateOwner.currentBeat = 0.0;
        delegateOwner.packageOffsetMs = 0L;
        delegateOwner.useRpe170Speed = false;
        delegateOwner.clearSelection();
        delegateOwner.rectangleSelectionMode = RectangleSelectionMode.NONE;
        delegateOwner.noteMoveMode = NoteMoveMode.OFF;
        delegateOwner.selectionRectangle = null;
        delegateOwner.bottomPanel = BottomPanel.CREATE;
        delegateOwner.editorBottomBarUi.resetScroll();
        delegateOwner.verticalGridLines = VerticalGrid.defaultCount();
        delegateOwner.gestureMode = GestureMode.NONE;
        delegateOwner.clearEventDragState();
        delegateOwner.xyBindingEnabled = delegateOwner.settings.xyBindingEnabled
                && XYBindingValidator.isChartSynchronized(chart);
        delegateOwner.playing = false;
        delegateOwner.previewMode = false;
        delegateOwner.playbackScrubbing = false;
        delegateOwner.editorPlayback.clearHitSoundPlayback();
        delegateOwner.history.clear();
        delegateOwner.chartDirty = false;
        delegateOwner.invalidate();
    }

    void setBackgroundIllustration(Bitmap illustration) {
        delegateOwner.setBackgroundIllustration(illustration, null);
    }

    void setBackgroundIllustration(Bitmap illustration, Bitmap blurred) {
        Bitmap previous = delegateOwner.backgroundIllustration;
        Bitmap previousBlur = delegateOwner.blurredBackgroundIllustration;
        delegateOwner.backgroundIllustration = illustration;
        delegateOwner.blurredBackgroundIllustration = blurred;
        if (previous != null && previous != illustration && previous != blurred
                && !previous.isRecycled()) previous.recycle();
        if (previousBlur != null && previousBlur != illustration && previousBlur != blurred
                && !previousBlur.isRecycled()) previousBlur.recycle();
        delegateOwner.invalidate();
    }

    void setPreviewLineTextures(Map<String, PreviewTextureDecoder.Texture> textures) {
        Map<String, PreviewTextureDecoder.Texture> next = textures == null || textures.isEmpty()
                ? Collections.emptyMap() : new LinkedHashMap<>(textures);
        Map<String, PreviewTextureDecoder.Texture> previous = delegateOwner.previewLineTextures;
        delegateOwner.previewLineTextures = next;
        delegateOwner.previewRenderer.setLineTextures(next);
        if (previous != next) PreviewTextureDecoder.recycleAll(previous);
        delegateOwner.invalidate();
    }
}
