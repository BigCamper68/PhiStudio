package com.xpe.mobile.editor;

import com.xpe.mobile.i18n.UiText;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.SystemClock;
import com.xpe.mobile.R;
import com.xpe.mobile.audio.HitSoundTimeline;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.StoryboardEventType;
import com.xpe.mobile.preview.PreviewShaderView;
import com.xpe.mobile.preview.PreviewTextureDecoder;
import com.xpe.mobile.preview.PreviewRenderer;
import com.xpe.mobile.preview.ShaderTimeline;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Editor lifecycle, playback and chrome (preview, toolbar, seek bar and bottom controls).
 *
 * <p>Touch gestures and concrete edit mutations are implemented by {@link EditorView}.
 */
abstract class EditorChromeView extends EditorDocumentView {
    private final EditorSessionUi editorSessionUi = new EditorSessionUi(this);

    final EditorBottomBarUi editorBottomBarUi = new EditorBottomBarUi(this);
    final EditorPlayback editorPlayback = new EditorPlayback(this);
    final EditorPreviewUi editorPreviewUi = new EditorPreviewUi(this);
    final EditorToolbarUi editorToolbarUi = new EditorToolbarUi(this);

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
        default double audioPositionSeconds() { return audioPositionMs() / 1000.0; }
        long audioDurationMs();
    }

    enum BottomPanel {
        CREATE,
        EDIT,
        ARRANGE
    }

    protected final List<ButtonSpec> buttons = new ArrayList<>();

    protected Callback callback;
    BottomPanel bottomPanel = BottomPanel.CREATE;
    protected int workbenchTab;
    protected final StoryboardTimeline storyboardTimeline;
    private Runnable workbenchListener;
    ShaderTimeline previewShaders = ShaderTimeline.EMPTY;
    PreviewShaderView shaderView;
    final com.xpe.mobile.preview.PreviewPlaybackState previewPlayback =
            new com.xpe.mobile.preview.PreviewPlaybackState();

    protected EditorChromeView(Context context) {
        super(context);
        storyboardTimeline = new StoryboardTimeline(this);
    }

    public void setWorkbenchListener(Runnable listener) { workbenchListener = listener; }
    public void setPreviewShaderView(PreviewShaderView view) {
        shaderView = view;
        view.setFrameListener(this::invalidate);
        view.setErrorListener(message -> {
            if (callback != null) callback.showMessage(UiText.resources(getContext()).getString(R.string.preview_shader_error, message));
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
    public double shaderEditorSeconds() { return editorPlayback.currentSeconds(); }
    public void shaderEditorPlay() { if (chart != null) togglePlay(); }
    public void shaderEditorSeek(double seconds) {
        previewPlayback.reset();
        if (chart == null || !Double.isFinite(seconds)) return;
        editorPlayback.setCurrentSeconds(seconds);
        playbackScrubbing = false; lastFrameMs = SystemClock.elapsedRealtime();
        if (playing) editorPlayback.prepareHitSoundPlayback(false);
        if (callback != null && callback.isAudioReady()) {
            long position = editorPlayback.audioPositionMillis();
            if (playing) callback.startAudio(position, playbackSpeed); else callback.seekAudio(position);
        }
        invalidate();
    }
    public void drawShaderEditorPreview(Canvas canvas, RectF area, PreviewShaderView surface,
                                        double loopStart, double loopEnd) {
        editorPreviewUi.drawShaderEditorPreview(canvas, area, surface, loopStart, loopEnd);
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
        return getResources().getString(R.string.arrange_summary, lineIndex, eventLayerIndex, subdivision, verticalGridLines);
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
        editorSessionUi.applySettings(value);
    }

    /** Empty taps clear every editor grid's selection before considering a seek. */
    public boolean clearTimelineSelectionOnTap() {
        return editorSessionUi.clearTimelineSelectionOnTap();
    }

    public boolean isTapToSeekEnabled() { return settings.tapToSeek; }

    public EditorSettings getSettings() {
        return settings.copy();
    }

    public double getCurrentBeat() { return currentBeat; }
    public boolean usesRpe170Speed() { return useRpe170Speed; }
    /** Borrowed resources stay owned by the editor for the lifetime of its playtest window. */
    public Bitmap playtestBackground() { return blurredBackgroundIllustration; }
    public ShaderTimeline playtestShaders() { return previewShaders; }
    public Map<String, PreviewTextureDecoder.Texture> playtestLineTextures() { return previewLineTextures; }
    public PreviewRenderer createPlaytestRenderer() {
        PreviewRenderer renderer = new PreviewRenderer(noteTextures, density, "COMBO", getContext().getAssets());
        renderer.setLineTextures(previewLineTextures);
        return renderer;
    }

    public void setChart(ChartDocument chart) {
        editorSessionUi.setChart(chart);
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
        projectNameResource = 0;
        this.projectName = projectName == null ? "" : projectName.trim();
        invalidate();
    }

    public void setProjectNameResource(int resource) {
        projectNameResource = resource;
        projectName = "";
        invalidate();
    }

    public void setBackgroundIllustration(Bitmap illustration) {
        editorSessionUi.setBackgroundIllustration(illustration);
    }

    public void setBackgroundIllustration(Bitmap illustration, Bitmap blurred) {
        editorSessionUi.setBackgroundIllustration(illustration, blurred);
    }

    public void setPreviewLineTextures(Map<String, PreviewTextureDecoder.Texture> textures) {
        editorSessionUi.setPreviewLineTextures(textures);
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
        if (chart != null && callback != null && callback.isAudioReady())
            editorPlayback.setAudioPositionSeconds(callback.audioPositionSeconds());
        playing = false;
        editorPlayback.clearHitSoundPlayback();
        invalidate();
    }

    public void stopPlayback() {
        playing = false;
        playbackScrubbing = false;
        editorPlayback.clearHitSoundPlayback();
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
            canvas.drawText(getResources().getString(R.string.editor_no_chart), 24f * density, 48f * density, paint);
            return;
        }

        if (playing) advancePlayback();
        if (previewMode) editorPreviewUi.drawPreview(canvas);
        else {
            if (shaderView != null) shaderView.hidePreview();
            drawEditor(canvas);
        }
    }

    private void drawEditor(Canvas canvas) {
        editorPreviewUi.drawEditor(canvas);
    }

    /** Same six toolbar actions and track geometry as Events and Storyboard. */
    public final float editorPlaybackSeekWidth(float width) {
        return editorToolbarUi.editorPlaybackSeekWidth(width);
    }

    /** Reuse the normal audio seek painter and scrub lifecycle in Shaders. */
    public final void drawShaderPlaybackSeek(Canvas canvas, float left, float right) {
        editorToolbarUi.drawPlaybackSeekBar(canvas, left, right);
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
        editorPlayback.setCurrentSeconds(seconds);
        invalidate();
    }
    public final void shaderEndPan() {
        finishPlaybackScrub();
        gestureMode = GestureMode.NONE;
        invalidate();
    }

    void drawBottomBar(Canvas canvas) {
        editorBottomBarUi.drawBottomBar(canvas);
    }

    void selectTool(Tool value) {
        tool = value;
        if (value != Tool.SELECT) rectangleSelectionMode = RectangleSelectionMode.NONE;
        bottomPanel = BottomPanel.CREATE;
        editorBottomBarUi.resetScroll();
        invalidate();
    }

    @Override
    protected void activateEditPanel() {
        bottomPanel = BottomPanel.EDIT;
        editorBottomBarUi.resetScroll();
    }

    void cycleNoteMoveMode() {
        cancelActiveGesture();
        noteMoveMode = noteMoveMode.next();
        tool = Tool.SELECT;
        activateEditPanel();
        invalidate();
    }

    @Override
    protected void togglePlay() {
        editorPlayback.togglePlay();
    }

    void togglePreviewMode() {
        cancelActiveGesture();
        previewMode = !previewMode;
        invalidate();
    }

    void advancePlayback() {
        editorPlayback.advancePlayback();
    }

    protected final void beginPanGesture() {
        editorPlayback.beginPanGesture();
    }

    protected final boolean beginPlaybackSeek(float x, float y) {
        return editorPlayback.beginPlaybackSeek(x, y);
    }

    protected final void updatePlaybackSeek(float x) {
        editorPlayback.updatePlaybackSeek(x);
    }

    protected final void finishPlaybackScrub() {
        editorPlayback.finishPlaybackScrub();
    }

    void changeLine(int delta) {
        cancelActiveGesture();
        lineIndex = Math.max(0, Math.min(chart.judgeLines.size() - 1, lineIndex + delta));
        eventLayerIndex = Math.min(eventLayerIndex, currentLine().eventLayers.size() - 1);
        clearSelection();
        invalidate();
    }

    void toggleXYBinding() {
        cancelActiveGesture();
        if (xyBindingEnabled) {
            xyBindingEnabled = false;
            settings.xyBindingEnabled = false;
            if (callback != null) callback.showMessage(
                    UiText.resources(getContext()).getString(R.string.xy_binding_disabled));
        } else if (XYBindingValidator.isChartSynchronized(chart)) {
            xyBindingEnabled = true;
            settings.xyBindingEnabled = true;
            if (callback != null) callback.showMessage(
                    UiText.resources(getContext()).getString(R.string.xy_binding_enabled));
        } else if (callback != null) {
            callback.showMessage(UiText.resources(getContext()).getString(R.string.xy_binding_enable_rejected));
        }
        invalidate();
    }

    void changeLayer(int delta) {
        cancelActiveGesture();
        eventLayerIndex = Math.max(0, Math.min(3, eventLayerIndex + delta));
        currentLine().layer(eventLayerIndex);
        clearSelection();
        invalidate();
    }

    void changeSubdivision(int delta) {
        int[] values = {1, 2, 3, 4, 6, 8, 12, 16, 24, 32};
        int current = 0;
        for (int index = 0; index < values.length; index++) {
            if (values[index] == subdivision) current = index;
        }
        subdivision = values[Math.max(0, Math.min(values.length - 1, current + delta))];
        invalidate();
    }

    void changeVerticalGrid(int delta) {
        verticalGridLines = VerticalGrid.changeCount(verticalGridLines, delta);
        invalidate();
    }

    void toggleRectangleSelection() {
        cancelActiveGesture();
        boolean enable = rectangleSelectionMode != RectangleSelectionMode.ADD;
        rectangleSelectionMode = enable
                ? RectangleSelectionMode.ADD : RectangleSelectionMode.NONE;
        tool = Tool.SELECT;
        activateEditPanel();
        if (enable && callback != null) callback.showMessage(
                UiText.resources(getContext()).getString(R.string.selection_box_add_armed));
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
    protected abstract void subdivideSelectedEvents();

    protected abstract void passSelectedEvent();

    protected abstract void randomizeSelectedEvent();

    protected abstract void glueSelectedEvent();

    static final class BottomAction {
        final String label;
        final float width;
        final Runnable action;
        final boolean active;
        final boolean enabled;
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
