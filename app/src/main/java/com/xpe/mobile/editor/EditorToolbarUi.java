package com.xpe.mobile.editor;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import com.xpe.mobile.R;
import com.xpe.mobile.editor.EditorChromeView.ButtonSpec;
import com.xpe.mobile.editor.EditorSurfaceView.GestureMode;
import java.util.Locale;

/** EditorToolbarUi responsibilities extracted from EditorChromeView. */
final class EditorToolbarUi {
    private final EditorChromeView delegateOwner;

    EditorToolbarUi(EditorChromeView delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void drawToolbar(Canvas canvas) {
        float height = delegateOwner.toolbarHeight();
        delegateOwner.paint.setColor(EditorChromeView.TOOLBAR_COLOR);
        canvas.drawRect(0, 0, delegateOwner.getWidth(), height, delegateOwner.paint);
        delegateOwner.buttons.clear();

        float x = 6f * delegateOwner.density;
        float y = 5f * delegateOwner.density;
        float buttonHeight = height - 10f * delegateOwner.density;
        x = delegateOwner.editorToolbarUi.addButton(canvas, delegateOwner.getResources().getString(delegateOwner.playing
                        ? R.string.toolbar_pause : R.string.toolbar_play),
                x, y, 58f * delegateOwner.density, buttonHeight, delegateOwner::togglePlay);
        x = delegateOwner.editorToolbarUi.addButton(canvas, String.format(Locale.US, "%.2fx", delegateOwner.playbackSpeed), x, y,
                54f * delegateOwner.density, buttonHeight, delegateOwner.editorPlayback::cyclePlaybackSpeed);
        x = delegateOwner.editorToolbarUi.addButton(canvas, delegateOwner.getResources().getString(delegateOwner.previewMode
                        ? R.string.toolbar_editor : R.string.toolbar_preview),
                x, y, 64f * delegateOwner.density, buttonHeight, delegateOwner::togglePreviewMode);
        x = delegateOwner.editorToolbarUi.addButton(canvas, delegateOwner.getResources().getString(R.string.toolbar_save), x, y,
                52f * delegateOwner.density, buttonHeight, () -> delegateOwner.callback.requestProjectSave());
        x = delegateOwner.editorToolbarUi.addButton(canvas, delegateOwner.getResources().getString(R.string.toolbar_undo), x, y,
                52f * delegateOwner.density, buttonHeight, delegateOwner::undo, delegateOwner.history.canUndo(), delegateOwner.history.canUndo());
        x = delegateOwner.editorToolbarUi.addButton(canvas, delegateOwner.getResources().getString(R.string.toolbar_redo), x, y,
                52f * delegateOwner.density, buttonHeight, delegateOwner::redo, delegateOwner.history.canRedo(), delegateOwner.history.canRedo());
        if (delegateOwner.previewMode) x = delegateOwner.editorToolbarUi.addButton(canvas, delegateOwner.getResources().getString(R.string.toolbar_menu), x, y,
                58f * delegateOwner.density, buttonHeight, () -> delegateOwner.callback.requestMainMenu());

        float seekRight = delegateOwner.getWidth() - 10f * delegateOwner.density;
        float seekLeft = seekRight - delegateOwner.editorToolbarUi.playbackSeekTrackWidth(delegateOwner.getWidth(), x);

        float titleLeft = x + 8f * delegateOwner.density;
        float titleRight = seekLeft - 10f * delegateOwner.density;
        if (titleRight > titleLeft + 12f * delegateOwner.density) {
            delegateOwner.paint.setColor(Color.WHITE);
            delegateOwner.paint.setTextSize(12f * delegateOwner.density);
            String chartTitle = delegateOwner.chart.name == null || delegateOwner.chart.name.trim().isEmpty()
                    ? "Untitled" : delegateOwner.chart.name.trim();
            String lineMarker = delegateOwner.settings.markLineId
                    ? " · L" + delegateOwner.lineIndex + "/" + (delegateOwner.chart.judgeLines.size() - 1) : "";
            String title = delegateOwner.projectName.isEmpty() || delegateOwner.projectName.equals(chartTitle)
                    ? chartTitle + lineMarker
                    : delegateOwner.projectName + " · " + chartTitle + lineMarker;
            canvas.drawText(delegateOwner.ellipsize(title, titleRight - titleLeft), titleLeft,
                    20f * delegateOwner.density, delegateOwner.paint);

            delegateOwner.paint.setColor(Color.LTGRAY);
            delegateOwner.paint.setTextSize(10f * delegateOwner.density);
            long effectiveOffset = (long) delegateOwner.chart.offsetMs + delegateOwner.packageOffsetMs;
            String offsetText = delegateOwner.packageOffsetMs == 0L
                    ? "offset " + delegateOwner.chart.offsetMs + " ms"
                    : "offset " + delegateOwner.chart.offsetMs + " + package " + delegateOwner.packageOffsetMs
                    + " = " + effectiveOffset + " ms";
            String bpmLine = "BPM " + EditorChromeView.compactValue(delegateOwner.chart.bpmAt(delegateOwner.currentBeat)) + " · " + offsetText;
            canvas.drawText(delegateOwner.ellipsize(bpmLine, titleRight - titleLeft), titleLeft,
                    38f * delegateOwner.density, delegateOwner.paint);
        }
        delegateOwner.editorToolbarUi.drawPlaybackSeekBar(canvas, seekLeft, seekRight);
    }

    float playbackSeekTrackWidth(float width, float actionsRight) {
        float preferred = EditorChromeView.clamp(width * .29f, 190f * delegateOwner.density, 360f * delegateOwner.density);
        return Math.max(80f * delegateOwner.density, Math.min(preferred, width - 10f * delegateOwner.density
                - actionsRight - 92f * delegateOwner.density));
    }

    final float editorPlaybackSeekWidth(float width) {
        float actionsRight = (6 + 58 + 54 + 64 + 52 + 52 + 52 + 6 * 4) * delegateOwner.density;
        return delegateOwner.editorToolbarUi.playbackSeekTrackWidth(width, actionsRight);
    }

    void drawPlaybackSeekBar(Canvas canvas, float left, float right) {
        delegateOwner.playbackSeekBounds.set(left, 3f * delegateOwner.density, right, delegateOwner.toolbarHeight() - 3f * delegateOwner.density);
        boolean ready = delegateOwner.callback != null && delegateOwner.callback.isAudioReady() && delegateOwner.callback.audioDurationMs() > 0L;
        long duration = ready ? delegateOwner.callback.audioDurationMs() : 0L;
        long position = 0L;
        if (ready) {
            position = !delegateOwner.playing || delegateOwner.playbackScrubbing || delegateOwner.gestureMode == GestureMode.SEEK_BAR
                    ? delegateOwner.chart.beatToAudioMillis(delegateOwner.currentBeat, delegateOwner.packageOffsetMs)
                    : delegateOwner.callback.audioPositionMs();
        }
        double fraction = PlaybackScrubMapper.fractionForPosition(position, duration);

        delegateOwner.paint.setTextAlign(Paint.Align.RIGHT);
        delegateOwner.paint.setTextSize(9.5f * delegateOwner.density);
        delegateOwner.paint.setColor(ready ? Color.LTGRAY : Color.rgb(120, 128, 136));
        String label = ready
                ? delegateOwner.getResources().getString(R.string.seek_audio_label,
                EditorChromeView.formatMillis(position), EditorChromeView.formatMillis(duration))
                : delegateOwner.getResources().getString(R.string.seek_audio_unavailable);
        canvas.drawText(delegateOwner.ellipsizeFromEnd(label, right - left), right, 14f * delegateOwner.density, delegateOwner.paint);
        delegateOwner.paint.setTextAlign(Paint.Align.LEFT);

        float trackTop = 25f * delegateOwner.density;
        float trackBottom = 31f * delegateOwner.density;
        RectF track = new RectF(left, trackTop, right, trackBottom);
        delegateOwner.paint.setColor(Color.rgb(66, 75, 84));
        canvas.drawRoundRect(track, 3f * delegateOwner.density, 3f * delegateOwner.density, delegateOwner.paint);
        if (ready) {
            float thumbX = left + (float) fraction * (right - left);
            delegateOwner.paint.setColor(EditorChromeView.ACCENT);
            canvas.drawRoundRect(new RectF(left, trackTop, thumbX, trackBottom),
                    3f * delegateOwner.density, 3f * delegateOwner.density, delegateOwner.paint);
            canvas.drawCircle(thumbX, (trackTop + trackBottom) / 2f, 7f * delegateOwner.density, delegateOwner.paint);
            delegateOwner.strokePaint.setColor(Color.WHITE);
            delegateOwner.strokePaint.setStrokeWidth(1.5f * delegateOwner.density);
            canvas.drawCircle(thumbX, (trackTop + trackBottom) / 2f, 7f * delegateOwner.density, delegateOwner.strokePaint);
        }
    }

    void drawStatus(Canvas canvas, float y) {
        delegateOwner.paint.setTextSize(10f * delegateOwner.density);
        delegateOwner.paint.setColor(Color.rgb(185, 195, 204));
        long audioPosition = delegateOwner.callback != null && delegateOwner.callback.isAudioReady()
                ? (delegateOwner.playbackScrubbing || delegateOwner.gestureMode == GestureMode.SEEK_BAR
                ? delegateOwner.chart.beatToAudioMillis(delegateOwner.currentBeat, delegateOwner.packageOffsetMs)
                : delegateOwner.callback.audioPositionMs())
                : delegateOwner.chart.beatToAudioMillis(delegateOwner.currentBeat, delegateOwner.packageOffsetMs);
        long audioDuration = delegateOwner.callback != null && delegateOwner.callback.isAudioReady() ? delegateOwner.callback.audioDurationMs() : 0L;
        String audioText = audioDuration > 0
                ? EditorChromeView.formatMillis(audioPosition) + "/" + EditorChromeView.formatMillis(audioDuration)
                : EditorChromeView.formatMillis(audioPosition);
        String text = String.format(Locale.US,
                "Beat %.3f  BPM %.2f  Audio %s  Line notes %d  Events %d  Total notes %d  Total events %d",
                delegateOwner.currentBeat,
                delegateOwner.chart.bpmAt(delegateOwner.currentBeat),
                audioText,
                delegateOwner.currentLine().notes.size(),
                delegateOwner.currentLine().countEvents(),
                delegateOwner.chart.totalNotes(),
                delegateOwner.chart.totalEvents());
        canvas.drawText(text, 8f * delegateOwner.density, y, delegateOwner.paint);
    }

    float addButton(Canvas canvas, String text, float x, float y, float width,
                            float height, Runnable action) {
        return delegateOwner.editorToolbarUi.addButton(canvas, text, x, y, width, height, action, false, true);
    }

    float addButton(Canvas canvas, String text, float x, float y, float width,
                            float height, Runnable action, boolean active) {
        return delegateOwner.editorToolbarUi.addButton(canvas, text, x, y, width, height, action, active, true);
    }

    float addButton(Canvas canvas, String text, float x, float y, float width,
                            float height, Runnable action, boolean active, boolean enabled) {
        RectF bounds = new RectF(x, y, x + width, y + height);
        if (!enabled) delegateOwner.paint.setColor(Color.rgb(39, 45, 52));
        else delegateOwner.paint.setColor(active ? Color.rgb(45, 132, 116) : Color.rgb(53, 63, 74));
        canvas.drawRect(bounds, delegateOwner.paint);
        delegateOwner.paint.setColor(enabled ? Color.WHITE : Color.rgb(125, 134, 142));
        delegateOwner.paint.setTextAlign(Paint.Align.CENTER);
        delegateOwner.paint.setTextSize(10.5f * delegateOwner.density);
        float baseline = bounds.centerY() - (delegateOwner.paint.ascent() + delegateOwner.paint.descent()) / 2f;
        canvas.drawText(text, bounds.centerX(), baseline, delegateOwner.paint);
        delegateOwner.paint.setTextAlign(Paint.Align.LEFT);
        if (enabled) delegateOwner.buttons.add(new ButtonSpec(bounds, action));
        return x + width + 4f * delegateOwner.density;
    }
}
