package com.xpe.mobile.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;

import com.xpe.mobile.R;
import com.xpe.mobile.audio.HitSoundTimeline;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.StoryboardEventType;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.preview.ChartEvaluator;
import com.xpe.mobile.preview.PreviewTextureDecoder;
import com.xpe.mobile.preview.RenderScene;
import com.xpe.mobile.preview.ShaderTimeline;
import com.xpe.mobile.preview.PreviewShaderView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Editor lifecycle, playback and chrome (preview, toolbar, seek bar and bottom controls).
 *
 * <p>Touch gestures and concrete edit mutations are implemented by {@link EditorView}.
 */
abstract class EditorChromeView extends EditorDocumentView {
    public interface Callback {
        void requestOpen();
        void requestSave();
        void requestProjectSave();
        void requestAudio();
        void requestEditMetadata();
        void requestEditBpmList();
        void requestManageLines();
        void requestMainMenu();
        void requestAdvancedBatchEdit();
        void requestEventClone();
        void requestEditNoteProperties(Note note);
        void requestEditEventProperties(LineEvent event);
        default void requestStoryboardProperties(StoryboardEventType type,
                ExtendedLineEvents.TimedEvent event, boolean creating) { }
        void showMessage(String message);
        boolean isAudioReady();
        boolean isAudioPlaying();
        void startAudio(long positionMs, float speed);
        void pauseAudio();
        void seekAudio(long positionMs);
        void playHitSound(HitSoundTimeline.Cue cue);
        default void configureHitSounds(HitSoundTimeline timeline, long chartOffsetMs, long packageOffsetMs) { }
        default boolean schedulesHitSounds() { return false; }
        long audioPositionMs();
        long audioDurationMs();
    }

    private enum BottomPanel {
        CREATE,
        EDIT,
        ARRANGE
    }

    protected final List<ButtonSpec> buttons = new ArrayList<>();

    protected Callback callback;
    private BottomPanel bottomPanel = BottomPanel.CREATE;
    private int bottomActionPage;
    protected int workbenchTab;
    protected final StoryboardTimeline storyboardTimeline;
    private Runnable workbenchListener;
    private ShaderTimeline previewShaders = ShaderTimeline.EMPTY;
    private PreviewShaderView shaderView;

    protected EditorChromeView(Context context) {
        super(context);
        storyboardTimeline = new StoryboardTimeline(this);
    }

    public void setWorkbenchListener(Runnable listener) { workbenchListener = listener; }
    public void setPreviewShaderView(PreviewShaderView view) {
        shaderView = view;
        view.setFrameListener(this::invalidate);
        view.setErrorListener(message -> {
            if (callback != null) callback.showMessage(getResources().getString(R.string.preview_shader_error, message));
        });
    }
    public void setPreviewShaders(ShaderTimeline timeline) {
        previewShaders = timeline == null ? ShaderTimeline.EMPTY : timeline;
        if (shaderView != null) { shaderView.hidePreview(); shaderView.resetFailure(); }
        invalidate();
    }
    public void updatePreviewShaders(ShaderTimeline timeline) {
        previewShaders = timeline == null ? ShaderTimeline.EMPTY : timeline;
        invalidate();
    }
    public double shaderEditorSeconds() { return chart == null ? 0 : chart.beatToSeconds(currentBeat); }
    public void shaderEditorPlay() { if (chart != null) togglePlay(); }
    public void shaderEditorSeek(double seconds) {
        if (chart == null || !Double.isFinite(seconds)) return;
        currentBeat = Math.max(0, chart.secondsToBeat(seconds));
        playbackScrubbing = false; lastFrameMs = SystemClock.elapsedRealtime();
        if (playing) prepareHitSoundPlayback(false);
        if (callback != null && callback.isAudioReady()) {
            long position = chart.beatToAudioMillis(currentBeat, packageOffsetMs);
            if (playing) callback.startAudio(position, playbackSpeed); else callback.seekAudio(position);
        }
        invalidate();
    }
    public void drawShaderEditorPreview(Canvas canvas, RectF area, PreviewShaderView surface,
                                        double loopStart, double loopEnd) {
        if (chart == null || area.isEmpty()) return;
        if (playing) advancePlayback();
        if (playing && !playbackScrubbing && loopEnd > loopStart && shaderEditorSeconds() >= loopEnd) shaderEditorSeek(loopStart);
        RenderScene scene = ChartEvaluator.evaluate(chart, currentBeat, settings.highlightSimultaneousNotes,
                callback == null ? -1L : callback.audioDurationMs(), useRpe170Speed,
                settings.playerWidth / (double) settings.playerHeight);
        PreviewViewportCalculator.Result fitted = PreviewViewportCalculator.fit(area.left, area.top,
                area.right, area.bottom, settings.playerWidth, settings.playerHeight);
        RectF viewport = new RectF((float) fitted.left, (float) fitted.top, (float) fitted.right, (float) fitted.bottom);
        if (!surface.submit(previewRenderer, scene, viewport, area, blurredBackgroundIllustration,
                settings, lineIndex, chart.getBackgroundDim(), previewShaders, shaderEditorSeconds()))
            previewRenderer.draw(canvas, scene, viewport, area, blurredBackgroundIllustration,
                    settings, lineIndex, chart.getBackgroundDim());
    }
    @Override public void invalidate() {
        super.invalidate();
        if (workbenchListener != null) workbenchListener.run();
    }
    public int getWorkbenchTab() { return workbenchTab; }
    public Object getSelectedPropertyTarget() {
        if (selectedNote != null) return selectedNote;
        if (selectedEvent != null) return selectedEvent;
        return workbenchTab == 1 ? storyboardTimeline.selected() : null;
    }
    public boolean isWorkbenchPreview() { return previewMode; }
    public boolean isXYBindingEnabled() { return xyBindingEnabled; }
    public void setWorkbenchTab(int tab) {
        cancelActiveGesture();
        previewMode = false;
        int next = Math.max(0, Math.min(2, tab));
        if (next != workbenchTab) {
            selectedEvent = null;
            selectedEvents.clear();
            if (next != 1) storyboardTimeline.clearSelection();
        }
        workbenchTab = next;
        invalidate();
    }
    public void arrangeLine(int delta) { if (chart != null) changeLine(delta); }
    public void arrangeLayer(int delta) { if (chart != null) changeLayer(delta); }
    public void arrangeBinding() { if (chart != null) toggleXYBinding(); }
    public void arrangeBeatGrid(int delta) { changeSubdivision(delta); }
    public void arrangeXGrid(int delta) { changeVerticalGrid(delta); }
    public String workbenchArrangementSummary() {
        return "Arrange · L" + lineIndex + " · layer " + eventLayerIndex
                + " · beat 1/" + subdivision + " · X grid " + verticalGridLines;
    }
    public void selectStoryboardEvent(StoryboardEventType type, ExtendedLineEvents.TimedEvent event) {
        storyboardTimeline.select(type, event);
        activateEditPanel();
        invalidate();
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void applySettings(EditorSettings value) {
        settings = value == null ? new EditorSettings() : value.copy();
        if (chart != null) {
            xyBindingEnabled = settings.xyBindingEnabled
                    && XYBindingValidator.isChartSynchronized(chart);
        }
        if (playing) prepareHitSoundPlayback(false);
        invalidate();
    }

    /** Empty taps clear every editor grid's selection before considering a seek. */
    public boolean clearTimelineSelectionOnTap() {
        boolean selected = selectedNote != null || selectedEvent != null
                || !selectedNotes.isEmpty() || !selectedEvents.isEmpty()
                || (storyboardTimeline != null && storyboardTimeline.selectionCount() > 0);
        if (selected) { clearSelection(); invalidate(); }
        return selected;
    }

    public boolean isTapToSeekEnabled() { return settings.tapToSeek; }

    public EditorSettings getSettings() {
        return settings.copy();
    }

    public void setChart(ChartDocument chart) {
        setPreviewShaders(ShaderTimeline.EMPTY);
        setBackgroundIllustration(null);
        setPreviewLineTextures(null);
        this.chart = chart;
        if (storyboardTimeline != null) storyboardTimeline.reset();
        workbenchTab = 0;
        lineIndex = 0;
        eventLayerIndex = 0;
        currentBeat = 0.0;
        packageOffsetMs = 0L;
        useRpe170Speed = false;
        clearSelection();
        rectangleSelectionMode = RectangleSelectionMode.NONE;
        noteMoveMode = NoteMoveMode.OFF;
        selectionRectangle = null;
        bottomPanel = BottomPanel.CREATE;
        bottomActionPage = 0;
        verticalGridLines = VerticalGrid.defaultCount();
        gestureMode = GestureMode.NONE;
        clearEventDragState();
        xyBindingEnabled = settings.xyBindingEnabled
                && XYBindingValidator.isChartSynchronized(chart);
        playing = false;
        previewMode = false;
        playbackScrubbing = false;
        clearHitSoundPlayback();
        history.clear();
        chartDirty = false;
        invalidate();
    }

    public ChartDocument getChart() {
        return chart;
    }

    public void setPackageOffsetMs(long packageOffsetMs) {
        this.packageOffsetMs = packageOffsetMs;
        invalidate();
    }

    public long getPackageOffsetMs() {
        return packageOffsetMs;
    }

    public void setUseRpe170Speed(boolean useRpe170Speed) {
        this.useRpe170Speed = useRpe170Speed;
        invalidate();
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName == null ? "" : projectName.trim();
        invalidate();
    }

    public void setBackgroundIllustration(Bitmap illustration) {
        setBackgroundIllustration(illustration, null);
    }

    public void setBackgroundIllustration(Bitmap illustration, Bitmap blurred) {
        Bitmap previous = backgroundIllustration;
        Bitmap previousBlur = blurredBackgroundIllustration;
        backgroundIllustration = illustration;
        blurredBackgroundIllustration = blurred;
        if (previous != null && previous != illustration && previous != blurred
                && !previous.isRecycled()) previous.recycle();
        if (previousBlur != null && previousBlur != illustration && previousBlur != blurred
                && !previousBlur.isRecycled()) previousBlur.recycle();
        invalidate();
    }

    public void setPreviewLineTextures(Map<String, PreviewTextureDecoder.Texture> textures) {
        Map<String, PreviewTextureDecoder.Texture> next = textures == null || textures.isEmpty()
                ? Collections.emptyMap() : new LinkedHashMap<>(textures);
        Map<String, PreviewTextureDecoder.Texture> previous = previewLineTextures;
        previewLineTextures = next;
        previewRenderer.setLineTextures(next);
        if (previous != next) PreviewTextureDecoder.recycleAll(previous);
        invalidate();
    }

    public void chartMetadataChanged() {
        markChartDirty();
    }

    @Override
    public void markChartDirty() {
        chartDirty = true;
        if (chart != null) chart.markEdited();
        invalidate();
    }

    public boolean isChartDirty() {
        return chartDirty;
    }

    public void markChartSaved() {
        chartDirty = false;
    }

    public void audioStateChanged() {
        invalidate();
    }

    public void audioCompleted() {
        playing = false;
        clearHitSoundPlayback();
        invalidate();
    }

    public void stopPlayback() {
        playing = false;
        playbackScrubbing = false;
        clearHitSoundPlayback();
        if (callback != null) callback.pauseAudio();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(10, 14, 18));
        if (chart == null) {
            if (shaderView != null) shaderView.hidePreview();
            paint.setColor(Color.WHITE);
            paint.setTextSize(18f * density);
            canvas.drawText("No chart loaded", 24f * density, 48f * density, paint);
            return;
        }

        if (playing) advancePlayback();
        if (previewMode) drawPreview(canvas);
        else {
            if (shaderView != null) shaderView.hidePreview();
            drawEditor(canvas);
        }
    }

    private void drawPreview(Canvas canvas) {
        RenderScene scene = ChartEvaluator.evaluate(
                chart, currentBeat, settings.highlightSimultaneousNotes,
                callback == null ? -1L : callback.audioDurationMs(),
                useRpe170Speed, settings.playerWidth / (double) settings.playerHeight);
        float barHeight = 25f * density;
        RectF previewArea = new RectF(0f, toolbarHeight(), getWidth(),
                getHeight() - barHeight);
        PreviewViewportCalculator.Result fitted = PreviewViewportCalculator.fit(
                previewArea.left, previewArea.top, previewArea.right, previewArea.bottom,
                settings.playerWidth, settings.playerHeight);
        RectF viewport = new RectF((float) fitted.left, (float) fitted.top,
                (float) fitted.right, (float) fitted.bottom);
        boolean submitted = shaderView != null && shaderView.submit(previewRenderer, scene,
                viewport, previewArea, blurredBackgroundIllustration, settings, lineIndex,
                chart.getBackgroundDim(), previewShaders, chart.beatToSeconds(currentBeat));
        if (!submitted) previewRenderer.draw(canvas, scene, viewport, previewArea,
                blurredBackgroundIllustration, settings, lineIndex, chart.getBackgroundDim());
        drawToolbar(canvas);

        paint.setColor(withAlpha(Color.BLACK, 150));
        canvas.drawRect(0f, getHeight() - barHeight, getWidth(), getHeight(), paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(10f * density);
        paint.setTextAlign(Paint.Align.LEFT);
        String status = getResources().getString(R.string.preview_status,
                currentBeat, chart.bpmAt(currentBeat), chart.totalNotes(), chart.totalEvents());
        canvas.drawText(status, 8f * density, getHeight() - 8f * density, paint);
    }

    private void drawEditor(Canvas canvas) {
        float toolbar = toolbarHeight();
        float bottom = getHeight() - bottomBarHeight();
        float split = editorSplitX();

        paint.setStyle(Paint.Style.FILL);
        drawNoteBackground(canvas, 0f, toolbar, split, bottom);
        paint.setColor(PANEL_COLOR);
        canvas.drawRect(split, toolbar, getWidth(), bottom, paint);
        drawEditorGameplayBackground(canvas, 0f, toolbar, split, bottom);

        drawNoteGrid(canvas, 0f, toolbar, split, bottom);
        drawNotes(canvas, 0f, toolbar, split, bottom);
        if (workbenchTab == 1) storyboardTimeline.draw(canvas, split, toolbar, getWidth(), bottom);
        else if (workbenchTab == 0) {
            int save = canvas.save();
            canvas.clipRect(split, toolbar, getWidth(), bottom);
            drawEventGrid(canvas, split, toolbar, getWidth(), bottom);
            drawEvents(canvas, split, toolbar, getWidth(), bottom);
            // Keep the cursor visible over event blocks, as in the Storyboard timeline.
            drawCurrentBeatLine(canvas, split, getWidth(), bottom);
            drawEventHeaders(canvas, split, toolbar, getWidth());
            canvas.restoreToCount(save);
        }
        drawPlacementPreview(canvas, split, toolbar, bottom);
        drawSelectionRectangle(canvas);
        drawToolbar(canvas);
        drawBottomBar(canvas);
        int save = canvas.save();
        canvas.clipRect(0f, toolbar, split, bottom);
        drawStatus(canvas, bottom - 7f * density);
        canvas.restoreToCount(save);
    }

    private void drawEditorGameplayBackground(Canvas canvas, float left, float top,
                                              float right, float bottom) {
        RenderScene scene = ChartEvaluator.evaluate(
                chart, currentBeat, settings.highlightSimultaneousNotes,
                callback == null ? -1L : callback.audioDurationMs(),
                useRpe170Speed, settings.playerWidth / (double) settings.playerHeight);
        PreviewViewportCalculator.Result fitted = PreviewViewportCalculator.fit(
                left, top, right, bottom, settings.playerWidth, settings.playerHeight);
        RectF viewport = new RectF((float) fitted.left, (float) fitted.top,
                (float) fitted.right, (float) fitted.bottom);
        int save = canvas.saveLayerAlpha(viewport, EDITOR_GAMEPLAY_ALPHA);
        previewRenderer.drawGameplayOverlay(canvas, scene, viewport, settings, lineIndex);
        canvas.restoreToCount(save);
    }

    private void drawToolbar(Canvas canvas) {
        float height = toolbarHeight();
        paint.setColor(TOOLBAR_COLOR);
        canvas.drawRect(0, 0, getWidth(), height, paint);
        buttons.clear();

        float x = 6f * density;
        float y = 5f * density;
        float buttonHeight = height - 10f * density;
        x = addButton(canvas, getResources().getString(playing
                        ? R.string.toolbar_pause : R.string.toolbar_play),
                x, y, 58f * density, buttonHeight, this::togglePlay);
        x = addButton(canvas, String.format(Locale.US, "%.2fx", playbackSpeed), x, y,
                54f * density, buttonHeight, this::cyclePlaybackSpeed);
        x = addButton(canvas, getResources().getString(previewMode
                        ? R.string.toolbar_editor : R.string.toolbar_preview),
                x, y, 64f * density, buttonHeight, this::togglePreviewMode);
        x = addButton(canvas, getResources().getString(R.string.toolbar_save), x, y,
                52f * density, buttonHeight, () -> callback.requestProjectSave());
        x = addButton(canvas, getResources().getString(R.string.toolbar_undo), x, y,
                52f * density, buttonHeight, this::undo, history.canUndo(), history.canUndo());
        x = addButton(canvas, getResources().getString(R.string.toolbar_redo), x, y,
                52f * density, buttonHeight, this::redo, history.canRedo(), history.canRedo());
        if (previewMode) x = addButton(canvas, getResources().getString(R.string.toolbar_menu), x, y,
                58f * density, buttonHeight, () -> callback.requestMainMenu());

        float seekRight = getWidth() - 10f * density;
        float seekLeft = seekRight - playbackSeekTrackWidth(getWidth(), x);

        float titleLeft = x + 8f * density;
        float titleRight = seekLeft - 10f * density;
        if (titleRight > titleLeft + 12f * density) {
            paint.setColor(Color.WHITE);
            paint.setTextSize(12f * density);
            String chartTitle = chart.name == null || chart.name.trim().isEmpty()
                    ? "Untitled" : chart.name.trim();
            String lineMarker = settings.markLineId
                    ? " · L" + lineIndex + "/" + (chart.judgeLines.size() - 1) : "";
            String title = projectName.isEmpty() || projectName.equals(chartTitle)
                    ? chartTitle + lineMarker
                    : projectName + " · " + chartTitle + lineMarker;
            canvas.drawText(ellipsize(title, titleRight - titleLeft), titleLeft,
                    20f * density, paint);

            paint.setColor(Color.LTGRAY);
            paint.setTextSize(10f * density);
            long effectiveOffset = (long) chart.offsetMs + packageOffsetMs;
            String offsetText = packageOffsetMs == 0L
                    ? "offset " + chart.offsetMs + " ms"
                    : "offset " + chart.offsetMs + " + package " + packageOffsetMs
                    + " = " + effectiveOffset + " ms";
            String bpmLine = "BPM " + compactValue(chart.bpmAt(currentBeat)) + " · " + offsetText;
            canvas.drawText(ellipsize(bpmLine, titleRight - titleLeft), titleLeft,
                    38f * density, paint);
        }
        drawPlaybackSeekBar(canvas, seekLeft, seekRight);
    }

    private float playbackSeekTrackWidth(float width, float actionsRight) {
        float preferred = clamp(width * .29f, 190f * density, 360f * density);
        return Math.max(80f * density, Math.min(preferred, width - 10f * density
                - actionsRight - 92f * density));
    }

    /** Same six toolbar actions and track geometry as Events and Storyboard. */
    public final float editorPlaybackSeekWidth(float width) {
        float actionsRight = (6 + 58 + 54 + 64 + 52 + 52 + 52 + 6 * 4) * density;
        return playbackSeekTrackWidth(width, actionsRight);
    }

    /** Reuse the normal audio seek painter and scrub lifecycle in Shaders. */
    public final void drawShaderPlaybackSeek(Canvas canvas, float left, float right) {
        drawPlaybackSeekBar(canvas, left, right);
    }
    public final void shaderPlaybackSeekTouch(int action, float x, float y) {
        if (action == android.view.MotionEvent.ACTION_DOWN) beginPlaybackSeek(x, y);
        else if (action == android.view.MotionEvent.ACTION_MOVE && gestureMode == GestureMode.SEEK_BAR)
            updatePlaybackSeek(x);
        else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
            if (action == android.view.MotionEvent.ACTION_UP && gestureMode == GestureMode.SEEK_BAR)
                updatePlaybackSeek(x);
            finishPlaybackScrub();
            gestureMode = GestureMode.NONE;
        }
        invalidate();
    }
    public final void shaderBeginPan() { beginPanGesture(); }
    public final void shaderPanTo(double seconds) {
        currentBeat = Math.max(0, chart.secondsToBeat(seconds));
        invalidate();
    }
    public final void shaderEndPan() {
        finishPlaybackScrub();
        gestureMode = GestureMode.NONE;
        invalidate();
    }

    private void drawPlaybackSeekBar(Canvas canvas, float left, float right) {
        playbackSeekBounds.set(left, 3f * density, right, toolbarHeight() - 3f * density);
        boolean ready = callback != null && callback.isAudioReady() && callback.audioDurationMs() > 0L;
        long duration = ready ? callback.audioDurationMs() : 0L;
        long position = 0L;
        if (ready) {
            position = !playing || playbackScrubbing || gestureMode == GestureMode.SEEK_BAR
                    ? chart.beatToAudioMillis(currentBeat, packageOffsetMs)
                    : callback.audioPositionMs();
        }
        double fraction = PlaybackScrubMapper.fractionForPosition(position, duration);

        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTextSize(9.5f * density);
        paint.setColor(ready ? Color.LTGRAY : Color.rgb(120, 128, 136));
        String label = ready
                ? getResources().getString(R.string.seek_audio_label,
                formatMillis(position), formatMillis(duration))
                : getResources().getString(R.string.seek_audio_unavailable);
        canvas.drawText(ellipsizeFromEnd(label, right - left), right, 14f * density, paint);
        paint.setTextAlign(Paint.Align.LEFT);

        float trackTop = 25f * density;
        float trackBottom = 31f * density;
        RectF track = new RectF(left, trackTop, right, trackBottom);
        paint.setColor(Color.rgb(66, 75, 84));
        canvas.drawRoundRect(track, 3f * density, 3f * density, paint);
        if (ready) {
            float thumbX = left + (float) fraction * (right - left);
            paint.setColor(ACCENT);
            canvas.drawRoundRect(new RectF(left, trackTop, thumbX, trackBottom),
                    3f * density, 3f * density, paint);
            canvas.drawCircle(thumbX, (trackTop + trackBottom) / 2f, 7f * density, paint);
            strokePaint.setColor(Color.WHITE);
            strokePaint.setStrokeWidth(1.5f * density);
            canvas.drawCircle(thumbX, (trackTop + trackBottom) / 2f, 7f * density, strokePaint);
        }
    }

    private void drawBottomBar(Canvas canvas) {
        float top = getHeight() - bottomBarHeight();
        float right = editorSplitX();
        paint.setColor(TOOLBAR_COLOR);
        canvas.drawRect(0, top, getWidth(), getHeight(), paint);
        int save = canvas.save();
        canvas.clipRect(0, top, right, getHeight());
        float x = drawBottomTab(canvas, R.string.controls_panel_create, BottomPanel.CREATE,
                6f * density, top + 5f * density);
        x = drawBottomTab(canvas, R.string.controls_panel_edit, BottomPanel.EDIT, x, top + 5f * density);
        x = drawBottomTab(canvas, R.string.controls_panel_arrange, BottomPanel.ARRANGE, x, top + 5f * density);
        paint.setColor(Color.LTGRAY);
        paint.setTextSize(10f * density);
        String summary = getResources().getString(R.string.controls_status_summary,
                currentToolLabel(), lineIndex, eventLayerIndex, subdivision, verticalGridLines);
        canvas.drawText(ellipsize(summary, Math.max(0, right - x - 12f * density)),
                x + 4f * density, top + 22f * density, paint);

        List<BottomAction> actions = bottomActions();
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        float total = 0;
        boolean explicitPages = false;
        for (BottomAction action : actions) {
            total += action.width + 4f * density;
            explicitPages |= action.startNewPage;
        }
        float budget = right - 12f * density
                - (explicitPages || total > right - 12f * density ? 84f * density : 0);
        // Fit each explicitly grouped Arrange page before applying overflow pagination.
        // Keep both X grid controls together on page two at every supported density.
        float widthScale = 1f;
        if (bottomPanel == BottomPanel.ARRANGE) {
            float groupWidth = 0, largestGroup = 0;
            int groupCount = 0, largestCount = 0;
            for (BottomAction action : actions) {
                if (action.startNewPage) {
                    if (groupWidth > largestGroup) { largestGroup = groupWidth; largestCount = groupCount; }
                    groupWidth = 0; groupCount = 0;
                }
                groupWidth += action.width; groupCount++;
            }
            if (groupWidth > largestGroup) { largestGroup = groupWidth; largestCount = groupCount; }
            widthScale = Math.min(1f, Math.max(0, budget - largestCount * 4f * density) / largestGroup);
        }
        float used = 0;
        for (int index = 0; index < actions.size(); index++) {
            float size = actions.get(index).width * widthScale + 4f * density;
            if (used > 0 && (actions.get(index).startNewPage || used + size > budget + .01f)) {
                starts.add(index); used = 0;
            }
            used += size;
        }
        int pages = starts.size();
        bottomActionPage = Math.max(0, Math.min(pages - 1, bottomActionPage));
        int first = starts.get(bottomActionPage);
        int last = bottomActionPage + 1 < pages ? starts.get(bottomActionPage + 1) : actions.size();
        float actionY = top + 36f * density;
        x = 6f * density;
        for (int index = first; index < last; index++) {
            BottomAction action = actions.get(index);
            x = addButton(canvas, action.label, x, actionY,
                    Math.min(action.width * widthScale, budget - 4f * density), 34f * density,
                    action.action, action.active, action.enabled);
        }
        if (pages > 1) {
            // Keep arrows after the actions, moving only the unused gap out of the way.
            float arrowsX = Math.min(x + 4f * density, right - 80f * density);
            addButton(canvas, "‹", arrowsX, actionY, 34f * density, 34f * density,
                    () -> changeBottomPage(-1), false, bottomActionPage > 0);
            addButton(canvas, "›", arrowsX + 38f * density, actionY, 34f * density, 34f * density,
                    () -> changeBottomPage(1), false, bottomActionPage + 1 < pages);
        }
        canvas.restoreToCount(save);
    }

    private float drawBottomTab(Canvas canvas, int textResource, BottomPanel panel,
                                float x, float y) {
        return addButton(canvas, getResources().getString(textResource), x, y,
                68f * density, 26f * density, () -> {
                    bottomPanel = panel;
                    bottomActionPage = 0;
                }, bottomPanel == panel);
    }

    private List<BottomAction> bottomActions() {
        List<BottomAction> actions = new ArrayList<>();
        if (workbenchTab == 1 && bottomPanel == BottomPanel.EDIT
                && selectedNote == null && selectedEvent == null) {
            boolean box = rectangleSelectionMode == RectangleSelectionMode.ADD;
            actions.add(bottomAction(box ? R.string.controls_box_add_on : R.string.controls_box_add_off,
                    68, this::toggleRectangleSelection, box));
            if (storyboardTimeline.selectionCount() == 1) {
                actions.add(bottomTextAction("Properties", 76, storyboardTimeline::edit));
                actions.add(bottomTextAction("Split", 54, storyboardTimeline::split));
                actions.add(bottomTextAction("Glue", 54, storyboardTimeline::glue));
            }
            if (storyboardTimeline.selectionCount() > 0) {
                actions.add(bottomTextAction("Delete", 60, storyboardTimeline::delete));
                actions.add(bottomTextAction("Copy", 54, storyboardTimeline::copy));
                actions.add(bottomTextAction("Cut", 50, storyboardTimeline::cut));
            }
            actions.add(bottomAction(R.string.controls_paste, 58, storyboardTimeline::paste,
                    false, storyboardTimeline.hasClipboard()));
            actions.add(bottomAction(R.string.controls_clear, 56, storyboardTimeline::clearSelection,
                    false, storyboardTimeline.selectionCount() > 0));
            return actions;
        }
        switch (bottomPanel) {
            case CREATE:
                actions.add(bottomAction(R.string.controls_select, 58f,
                        () -> selectTool(Tool.SELECT), tool == Tool.SELECT));
                actions.add(bottomAction(R.string.controls_tap, 54f,
                        () -> selectTool(Tool.TAP), tool == Tool.TAP));
                actions.add(bottomAction(R.string.controls_drag, 56f,
                        () -> selectTool(Tool.DRAG), tool == Tool.DRAG));
                actions.add(bottomAction(R.string.controls_flick, 56f,
                        () -> selectTool(Tool.FLICK), tool == Tool.FLICK));
                actions.add(bottomAction(R.string.controls_hold, 56f,
                        () -> selectTool(Tool.HOLD), tool == Tool.HOLD));
                actions.add(bottomAction(R.string.controls_event, 58f,
                        () -> selectTool(Tool.EVENT), tool == Tool.EVENT));
                break;
            case EDIT:
                addEditActions(actions);
                break;
            case ARRANGE:
                actions.add(bottomAction(xyBindingEnabled
                                ? R.string.xy_binding_button_on : R.string.xy_binding_button_off,
                        58f, this::toggleXYBinding, xyBindingEnabled));
                actions.add(bottomAction(R.string.controls_layer_decrease, 66f,
                        () -> changeLayer(-1), false, eventLayerIndex > 0));
                actions.add(bottomAction(R.string.controls_layer_increase, 66f,
                        () -> changeLayer(1), false, eventLayerIndex < 3));
                actions.add(bottomAction(R.string.controls_line_decrease, 64f,
                        () -> changeLine(-1), false, lineIndex > 0));
                actions.add(bottomAction(R.string.controls_line_increase, 64f,
                        () -> changeLine(1), false,
                        chart != null && lineIndex + 1 < chart.judgeLines.size()));
                actions.add(bottomAction(R.string.controls_x_grid_decrease, 70f,
                        () -> changeVerticalGrid(-1)).nextPage());
                actions.add(bottomAction(R.string.controls_x_grid_increase, 70f,
                        () -> changeVerticalGrid(1)));
                actions.add(bottomAction(R.string.controls_beat_grid_decrease, 80f,
                        () -> changeSubdivision(-1)));
                actions.add(bottomAction(R.string.controls_beat_grid_increase, 80f,
                        () -> changeSubdivision(1)));
                break;
        }
        return actions;
    }

    private void addEditActions(List<BottomAction> actions) {
        boolean clipboardReady = clipboard != null && !clipboard.isEmpty();
        boolean boxSelection = rectangleSelectionMode == RectangleSelectionMode.ADD;
        actions.add(bottomAction(boxSelection
                        ? R.string.controls_box_add_on : R.string.controls_box_add_off,
                68f, this::toggleRectangleSelection, boxSelection));
        actions.add(bottomAction(noteMoveModeLabel(), 72f,
                this::cycleNoteMoveMode, noteMoveMode.enabled()));
        if (selectionCount() > 1) {
            boolean hasNotes = !getSelectedNotesForBatch().isEmpty();
            boolean hasEvents = !getSelectedEventsForBatch().isEmpty();
            actions.add(bottomAction(R.string.controls_batch_edit, 74f,
                    () -> callback.requestAdvancedBatchEdit(), false,
                    hasNotes || hasEvents));
            actions.add(bottomAction(R.string.controls_event_clone, 76f,
                    () -> callback.requestEventClone(), false, hasEvents));
            actions.add(bottomAction(R.string.controls_delete, 62f, this::deleteSelection));
            actions.add(bottomAction(R.string.controls_copy, 56f, this::copySelection));
            actions.add(bottomAction(R.string.controls_cut, 50f, this::cutSelection));
            actions.add(bottomAction(R.string.controls_paste, 58f,
                    () -> pasteClipboard(false), false, clipboardReady));
            actions.add(bottomAction(R.string.controls_mirror_paste, 88f,
                    () -> pasteClipboard(true), false, clipboardReady));
            actions.add(bottomAction(R.string.controls_clear, 56f, this::clearSelection));
            return;
        }
        if (selectedNote != null) {
            actions.add(bottomAction(R.string.properties_button, 72f,
                    () -> callback.requestEditNoteProperties(selectedNote)));
            actions.add(bottomAction(R.string.controls_delete, 62f, this::deleteSelection));
            actions.add(bottomAction(R.string.controls_copy, 56f, this::copySelection));
            actions.add(bottomAction(R.string.controls_cut, 50f, this::cutSelection));
            actions.add(bottomAction(R.string.controls_mirror_x, 70f, this::mirrorSelectedNote));
            actions.add(bottomAction(selectedNote.above == 1
                            ? R.string.controls_side_up : R.string.controls_side_down,
                    70f, this::switchSelectedNoteSide));
            actions.add(bottomAction(selectedNote.fake
                            ? R.string.controls_fake_checked : R.string.controls_fake,
                    60f, this::toggleSelectedNoteFake, selectedNote.fake));
            actions.add(bottomAction(R.string.controls_width_decrease, 70f,
                    () -> resizeSelectedNote(-0.1)));
            actions.add(bottomAction(R.string.controls_width_increase, 70f,
                    () -> resizeSelectedNote(0.1)));
            return;
        }
        if (selectedEvent != null) {
            actions.add(bottomAction(R.string.properties_button, 72f,
                    () -> callback.requestEditEventProperties(selectedEvent)));
            actions.add(bottomAction(R.string.event_split_button, 58f, this::splitSelectedEvent));
            actions.add(bottomAction(R.string.event_pass_button, 56f, this::passSelectedEvent));
            actions.add(bottomAction(R.string.event_rand_button, 56f, this::randomizeSelectedEvent));
            actions.add(bottomAction(R.string.event_glue_button, 58f, this::glueSelectedEvent));
            actions.add(bottomAction(R.string.controls_delete, 62f, this::deleteSelection));
            actions.add(bottomAction(R.string.controls_copy, 56f, this::copySelection));
            actions.add(bottomAction(R.string.controls_cut, 50f, this::cutSelection));
            actions.add(bottomAction(R.string.controls_event_start_decrease, 68f,
                    () -> adjustSelectedEventValue(true, -eventStep(selectedEvent.type))));
            actions.add(bottomAction(R.string.controls_event_start_increase, 68f,
                    () -> adjustSelectedEventValue(true, eventStep(selectedEvent.type))));
            actions.add(bottomAction(R.string.controls_event_end_decrease, 62f,
                    () -> adjustSelectedEventValue(false, -eventStep(selectedEvent.type))));
            actions.add(bottomAction(R.string.controls_event_end_increase, 62f,
                    () -> adjustSelectedEventValue(false, eventStep(selectedEvent.type))));
            actions.add(bottomTextAction(getResources().getString(R.string.controls_event_ease,
                            eventEasingLabel(selectedEvent)),
                    112f, () -> callback.requestEditEventProperties(selectedEvent)));
            actions.add(bottomAction(selectedEvent.linkGroup == 0
                            ? R.string.controls_event_link : R.string.controls_event_linked,
                    64f, this::toggleSelectedEventLink, selectedEvent.linkGroup != 0));
            return;
        }
        actions.add(bottomAction(R.string.controls_paste, 58f,
                () -> pasteClipboard(false), false, clipboardReady));
        actions.add(bottomAction(R.string.controls_mirror_paste, 88f,
                () -> pasteClipboard(true), false, clipboardReady));
        actions.add(bottomAction(R.string.controls_clear, 56f,
                this::clearSelection, false, hasSelection()));
    }

    private BottomAction bottomAction(int textResource, float widthDp, Runnable action) {
        return bottomAction(textResource, widthDp, action, false, true);
    }

    private BottomAction bottomAction(int textResource, float widthDp, Runnable action,
                                      boolean active) {
        return bottomAction(textResource, widthDp, action, active, true);
    }

    private BottomAction bottomAction(int textResource, float widthDp, Runnable action,
                                      boolean active, boolean enabled) {
        return new BottomAction(getResources().getString(textResource), widthDp * density,
                action, active, enabled);
    }

    private BottomAction bottomTextAction(String text, float widthDp, Runnable action) {
        return new BottomAction(text, widthDp * density, action, false, true);
    }

    private void changeBottomPage(int delta) {
        bottomActionPage = Math.max(0, bottomActionPage + delta);
        invalidate();
    }

    private void selectTool(Tool value) {
        tool = value;
        if (value != Tool.SELECT) rectangleSelectionMode = RectangleSelectionMode.NONE;
        bottomPanel = BottomPanel.CREATE;
        bottomActionPage = 0;
        invalidate();
    }

    @Override
    protected void activateEditPanel() {
        bottomPanel = BottomPanel.EDIT;
        bottomActionPage = 0;
    }

    private int noteMoveModeLabel() {
        switch (noteMoveMode) {
            case FREE: return R.string.controls_move_xy;
            case X_ONLY: return R.string.controls_move_x;
            case Y_ONLY: return R.string.controls_move_y;
            case OFF:
            default: return R.string.controls_move_off;
        }
    }

    private void cycleNoteMoveMode() {
        cancelActiveGesture();
        noteMoveMode = noteMoveMode.next();
        tool = Tool.SELECT;
        activateEditPanel();
        invalidate();
    }

    private String currentToolLabel() {
        switch (tool) {
            case TAP: return getResources().getString(R.string.controls_tap);
            case DRAG: return getResources().getString(R.string.controls_drag);
            case FLICK: return getResources().getString(R.string.controls_flick);
            case HOLD: return getResources().getString(R.string.controls_hold);
            case EVENT: return getResources().getString(R.string.controls_event);
            default: return getResources().getString(R.string.controls_select);
        }
    }

    private String bottomContextSummary() {
        if (workbenchTab == 1 && storyboardTimeline.selected() != null)
            return storyboardTimeline.selectionSummary();
        if (selectedNote != null) {
            return getResources().getString(R.string.controls_note_summary,
                    selectedNote.type.toString(), selectedNote.startTime.toString(),
                    compactValue(selectedNote.positionX));
        }
        if (selectedEvent != null) {
            return getResources().getString(R.string.controls_event_summary,
                    selectedEvent.type.label, selectedEvent.startTime.toString(),
                    selectedEvent.endTime.toString(), compactValue(selectedEvent.start),
                    compactValue(selectedEvent.end));
        }
        int count = selectionCount();
        return count > 0
                ? getResources().getQuantityString(
                R.plurals.controls_selection_count, count, count)
                : getResources().getString(R.string.controls_no_selection);
    }

    private String bottomPanelHint() {
        switch (bottomPanel) {
            case EDIT: return getResources().getString(R.string.controls_hint_edit);
            case ARRANGE: return getResources().getString(R.string.controls_hint_arrange);
            default: return getResources().getString(R.string.controls_hint_create);
        }
    }

    private void drawStatus(Canvas canvas, float y) {
        paint.setTextSize(10f * density);
        paint.setColor(Color.rgb(185, 195, 204));
        long audioPosition = callback != null && callback.isAudioReady()
                ? (playbackScrubbing || gestureMode == GestureMode.SEEK_BAR
                ? chart.beatToAudioMillis(currentBeat, packageOffsetMs)
                : callback.audioPositionMs())
                : chart.beatToAudioMillis(currentBeat, packageOffsetMs);
        long audioDuration = callback != null && callback.isAudioReady() ? callback.audioDurationMs() : 0L;
        String audioText = audioDuration > 0
                ? formatMillis(audioPosition) + "/" + formatMillis(audioDuration)
                : formatMillis(audioPosition);
        String text = String.format(Locale.US,
                "Beat %.3f  BPM %.2f  Audio %s  Line notes %d  Events %d  Total notes %d  Total events %d",
                currentBeat,
                chart.bpmAt(currentBeat),
                audioText,
                currentLine().notes.size(),
                currentLine().countEvents(),
                chart.totalNotes(),
                chart.totalEvents());
        canvas.drawText(text, 8f * density, y, paint);
    }

    @Override
    protected void togglePlay() {
        if (playing) {
            playing = false;
            playbackScrubbing = false;
            clearHitSoundPlayback();
            if (callback != null) callback.pauseAudio();
        } else {
            playing = true;
            playbackScrubbing = false;
            prepareHitSoundPlayback(true);
            lastFrameMs = SystemClock.elapsedRealtime();
            if (callback != null && callback.isAudioReady()) {
                callback.startAudio(chart.beatToAudioMillis(
                        currentBeat, packageOffsetMs), playbackSpeed);
            }
        }
        invalidate();
    }

    private void togglePreviewMode() {
        cancelActiveGesture();
        previewMode = !previewMode;
        invalidate();
    }

    private void cyclePlaybackSpeed() {
        if (Math.abs(playbackSpeed - 1.0f) < 0.01f) playbackSpeed = 0.75f;
        else if (Math.abs(playbackSpeed - 0.75f) < 0.01f) playbackSpeed = 0.5f;
        else playbackSpeed = 1.0f;
        if (playing && callback != null && callback.isAudioReady()) {
            prepareHitSoundPlayback(false);
            callback.startAudio(chart.beatToAudioMillis(
                    currentBeat, packageOffsetMs), playbackSpeed);
        }
        invalidate();
    }

    private void advancePlayback() {
        long now = SystemClock.elapsedRealtime();
        if (playbackScrubbing) {
            lastFrameMs = now;
            if (playing) postInvalidateOnAnimation();
            return;
        }
        if (callback != null && callback.isAudioReady()) {
            if (!callback.isAudioPlaying()) {
                playing = false;
                clearHitSoundPlayback();
            } else {
                currentBeat = Math.max(0.0, chart.audioMillisToBeat(
                        callback.audioPositionMs(), packageOffsetMs));
                dispatchHitSounds();
            }
        } else {
            long delta = Math.max(0L, now - lastFrameMs);
            currentBeat += delta * playbackSpeed / 60000.0 * chart.bpmAt(currentBeat);
            dispatchHitSounds();
        }
        lastFrameMs = now;
        if (playing) postInvalidateOnAnimation();
    }

    private void seekPlaybackToCurrentBeat() {
        if (callback != null && callback.isAudioReady()) {
            if (playing) prepareHitSoundPlayback(false);
            callback.startAudio(chart.beatToAudioMillis(
                    currentBeat, packageOffsetMs), playbackSpeed);
        }
    }

    protected final void beginPanGesture() {
        gestureMode = GestureMode.PAN;
        playbackScrubbing = playing;
        if (playbackScrubbing && callback != null && callback.isAudioReady()) {
            callback.pauseAudio();
        }
    }

    protected final boolean beginPlaybackSeek(float x, float y) {
        if (callback == null || !callback.isAudioReady() || callback.audioDurationMs() <= 0L) {
            if (callback != null) callback.showMessage(
                    getResources().getString(R.string.seek_audio_unavailable));
            return true;
        }
        gestureStartX = x;
        gestureStartY = y;
        gestureLastY = y;
        gestureMode = GestureMode.SEEK_BAR;
        playbackScrubbing = true;
        if (playing) callback.pauseAudio();
        updatePlaybackSeek(x);
        invalidate();
        return true;
    }

    protected final void updatePlaybackSeek(float x) {
        if (callback == null || !callback.isAudioReady()) return;
        long duration = callback.audioDurationMs();
        double fraction = PlaybackScrubMapper.fractionForX(
                x, playbackSeekBounds.left, playbackSeekBounds.right);
        long position = PlaybackScrubMapper.positionForFraction(fraction, duration);
        currentBeat = Math.max(0.0, chart.audioMillisToBeat(position, packageOffsetMs));
    }

    protected final void finishPlaybackScrub() {
        if (!playbackScrubbing) return;
        playbackScrubbing = false;
        lastFrameMs = SystemClock.elapsedRealtime();
        if (callback != null && callback.isAudioReady()) {
            long position = chart.beatToAudioMillis(currentBeat, packageOffsetMs);
            if (playing) {
                prepareHitSoundPlayback(false);
                callback.startAudio(position, playbackSpeed);
            }
            else callback.seekAudio(position);
        }
    }

    private void prepareHitSoundPlayback(boolean includeZeroBoundary) {
        hitSoundTimeline = HitSoundTimeline.build(chart,
                settings.tapFlickHitsoundOffsetMs, settings.dragHitsoundOffsetMs);
        if (callback != null) callback.configureHitSounds(hitSoundTimeline, chart.offsetMs, packageOffsetMs);
        long currentTimeMs = chart == null ? 0L : chart.beatToMillis(currentBeat);
        lastHitSoundChartTimeMs = includeZeroBoundary && currentTimeMs == 0L
                ? -1L : currentTimeMs;
    }

    private void clearHitSoundPlayback() {
        hitSoundTimeline = HitSoundTimeline.empty();
        lastHitSoundChartTimeMs = 0L;
    }

    private void dispatchHitSounds() {
        if (!playing || chart == null) return;
        if (callback != null && callback.schedulesHitSounds()) return;
        long currentTimeMs = chart.beatToMillis(currentBeat);
        if (currentTimeMs < lastHitSoundChartTimeMs) {
            lastHitSoundChartTimeMs = currentTimeMs;
            return;
        }
        if (callback != null) {
            for (HitSoundTimeline.Cue cue : hitSoundTimeline.cuesBetween(
                    lastHitSoundChartTimeMs, currentTimeMs)) {
                callback.playHitSound(cue);
            }
        }
        lastHitSoundChartTimeMs = currentTimeMs;
    }

    private void changeLine(int delta) {
        cancelActiveGesture();
        lineIndex = Math.max(0, Math.min(chart.judgeLines.size() - 1, lineIndex + delta));
        eventLayerIndex = Math.min(eventLayerIndex, currentLine().eventLayers.size() - 1);
        clearSelection();
        invalidate();
    }

    private void toggleXYBinding() {
        cancelActiveGesture();
        if (xyBindingEnabled) {
            xyBindingEnabled = false;
            settings.xyBindingEnabled = false;
            if (callback != null) callback.showMessage(
                    getResources().getString(R.string.xy_binding_disabled));
        } else if (XYBindingValidator.isChartSynchronized(chart)) {
            xyBindingEnabled = true;
            settings.xyBindingEnabled = true;
            if (callback != null) callback.showMessage(
                    getResources().getString(R.string.xy_binding_enabled));
        } else if (callback != null) {
            callback.showMessage(getResources().getString(R.string.xy_binding_enable_rejected));
        }
        invalidate();
    }

    private void changeLayer(int delta) {
        cancelActiveGesture();
        eventLayerIndex = Math.max(0, Math.min(3, eventLayerIndex + delta));
        currentLine().layer(eventLayerIndex);
        clearSelection();
        invalidate();
    }

    private void changeSubdivision(int delta) {
        int[] values = {1, 2, 3, 4, 6, 8, 12, 16, 24, 32};
        int current = 0;
        for (int index = 0; index < values.length; index++) {
            if (values[index] == subdivision) current = index;
        }
        subdivision = values[Math.max(0, Math.min(values.length - 1, current + delta))];
        invalidate();
    }

    private void changeVerticalGrid(int delta) {
        verticalGridLines = VerticalGrid.changeCount(verticalGridLines, delta);
        invalidate();
    }

    private float addButton(Canvas canvas, String text, float x, float y, float width,
                            float height, Runnable action) {
        return addButton(canvas, text, x, y, width, height, action, false, true);
    }

    private float addButton(Canvas canvas, String text, float x, float y, float width,
                            float height, Runnable action, boolean active) {
        return addButton(canvas, text, x, y, width, height, action, active, true);
    }

    private float addButton(Canvas canvas, String text, float x, float y, float width,
                            float height, Runnable action, boolean active, boolean enabled) {
        RectF bounds = new RectF(x, y, x + width, y + height);
        if (!enabled) paint.setColor(Color.rgb(39, 45, 52));
        else paint.setColor(active ? Color.rgb(45, 132, 116) : Color.rgb(53, 63, 74));
        canvas.drawRect(bounds, paint);
        paint.setColor(enabled ? Color.WHITE : Color.rgb(125, 134, 142));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(10.5f * density);
        float baseline = bounds.centerY() - (paint.ascent() + paint.descent()) / 2f;
        canvas.drawText(text, bounds.centerX(), baseline, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        if (enabled) buttons.add(new ButtonSpec(bounds, action));
        return x + width + 4f * density;
    }

    private void toggleRectangleSelection() {
        cancelActiveGesture();
        boolean enable = rectangleSelectionMode != RectangleSelectionMode.ADD;
        rectangleSelectionMode = enable
                ? RectangleSelectionMode.ADD : RectangleSelectionMode.NONE;
        tool = Tool.SELECT;
        activateEditPanel();
        if (enable && callback != null) callback.showMessage(
                getResources().getString(R.string.selection_box_add_armed));
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (shaderView != null) shaderView.hidePreview();
        setBackgroundIllustration(null);
        setPreviewLineTextures(null);
        super.onDetachedFromWindow();
    }

    protected abstract void clearEventDragState();

    protected abstract void mirrorSelectedNote();

    protected abstract void switchSelectedNoteSide();

    protected abstract void toggleSelectedNoteFake();

    protected abstract void resizeSelectedNote(double delta);

    protected abstract void adjustSelectedEventValue(boolean startValue, double delta);

    protected abstract void toggleSelectedEventLink();

    protected abstract void splitSelectedEvent();

    protected abstract void passSelectedEvent();

    protected abstract void randomizeSelectedEvent();

    protected abstract void glueSelectedEvent();

    private static final class BottomAction {
        final String label;
        final float width;
        final Runnable action;
        final boolean active;
        final boolean enabled;
        boolean startNewPage;

        BottomAction nextPage() { startNewPage = true; return this; }

        BottomAction(String label, float width, Runnable action, boolean active, boolean enabled) {
            this.label = label;
            this.width = width;
            this.action = action;
            this.active = active;
            this.enabled = enabled;
        }
    }

    protected static final class ButtonSpec {
        final RectF bounds;
        final Runnable action;

        ButtonSpec(RectF bounds, Runnable action) {
            this.bounds = bounds;
            this.action = action;
        }
    }
}
