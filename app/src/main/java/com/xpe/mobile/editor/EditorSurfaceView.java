package com.xpe.mobile.editor;

import com.xpe.mobile.i18n.UiText;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import com.xpe.mobile.R;
import com.xpe.mobile.audio.HitSoundTimeline;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.preview.PreviewRenderer;
import com.xpe.mobile.preview.PreviewTextureDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rendering state and canvas primitives shared by the interactive editor view.
 *
 * <p>This class contains no touch dispatch or edit-command orchestration. Keeping those concerns in
 * {@link EditorView} makes both the renderer and the interaction controller small enough to review
 * independently.
 */
abstract class EditorSurfaceView extends View {
    private final EditorDrawingStyle editorDrawingStyle = new EditorDrawingStyle(this);

    private final EditorEventPainter editorEventPainter = new EditorEventPainter(this);
    private final EditorNotePainter editorNotePainter = new EditorNotePainter(this);
    private final EditorTimelinePainter editorTimelinePainter = new EditorTimelinePainter(this);

    private ChartDocument multiHintChart;
    private long multiHintRevision = -1L;
    private Set<Note> multiHintNotes = Collections.emptySet();

    Set<Note> multiHintNotes() {
        if (!settings.highlightSimultaneousNotes) return Collections.emptySet();
        if (multiHintChart != chart || multiHintRevision != chart.revision()) {
            multiHintNotes = NoteMultiHintResolver.resolve(chart);
            multiHintChart = chart;
            multiHintRevision = chart.revision();
        }
        return multiHintNotes;
    }
    protected enum Tool {
        SELECT,
        TAP,
        DRAG,
        FLICK,
        HOLD,
        EVENT
    }

    protected enum GestureMode {
        NONE,
        GRID_TAP,
        PAN,
        HOLD_PREVIEW,
        EVENT_PREVIEW,
        EVENT_DRAG_START,
        EVENT_DRAG_END,
        NOTE_DRAG,
        HOLD_DRAG_START,
        HOLD_DRAG_END,
        RECT_SELECT,
        SEEK_BAR
    }

    protected enum RectangleSelectionMode {
        NONE,
        ADD,
        REMOVE
    }

    protected static final double CHART_X_MIN = -675.0;
    protected static final double CHART_X_MAX = 675.0;
    protected static final int TOOLBAR_COLOR = Color.rgb(24, 30, 37);
    protected static final int PANEL_COLOR = Color.rgb(30, 37, 45);
    protected static final int GRID_MINOR = Color.rgb(39, 65, 53);
    protected static final int GRID_MAJOR = Color.rgb(121, 61, 61);
    protected static final int ACCENT = Color.rgb(91, 211, 172);
    protected static final int INVALID_PREVIEW = Color.rgb(230, 82, 82);
    protected static final int EVENT_EASING_CURVE = Color.rgb(255, 150, 38);
    protected static final int EDITOR_GAMEPLAY_ALPHA = 128;
    protected static final int FREE_SPLIT_DIVISION = 1_000_000;

    protected final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint noteTexturePaint =
            new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    protected final Paint backgroundPaint =
            new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    protected final Path eventCurvePath = new Path();
    protected final NoteTextureSet noteTextures;
    protected final PreviewRenderer previewRenderer;
    protected String[] easingEntries;

    protected ChartDocument chart;
    protected String projectName = "";
    protected int projectNameResource;
    protected Tool tool = Tool.SELECT;
    protected int lineIndex;
    protected int eventLayerIndex;
    protected int subdivision = 4;
    protected double currentBeat;
    protected float pixelsPerBeat;
    protected Note selectedNote;
    protected LineEvent selectedEvent;
    protected final Set<Note> selectedNotes = new LinkedHashSet<>();
    protected final Set<LineEvent> selectedEvents = new LinkedHashSet<>();
    protected ChartClipboard.Snapshot clipboard;
    protected RectangleSelectionMode rectangleSelectionMode = RectangleSelectionMode.NONE;
    protected NoteMoveMode noteMoveMode = NoteMoveMode.OFF;
    protected RectF selectionRectangle;
    protected int verticalGridLines = VerticalGrid.defaultCount();
    protected final RectF playbackSeekBounds = new RectF();
    protected EventType selectedEventType = EventType.MOVE_X;
    protected boolean playing;
    protected boolean chartDirty;
    protected boolean xyBindingEnabled;
    protected long lastFrameMs;
    protected float playbackSpeed = 1.0f;
    protected long packageOffsetMs;
    protected boolean useRpe170Speed;
    protected Bitmap backgroundIllustration;
    protected Map<String, PreviewTextureDecoder.Texture> previewLineTextures =
            Collections.emptyMap();
    protected boolean playbackScrubbing;
    protected boolean previewMode;
    protected HitSoundTimeline hitSoundTimeline = HitSoundTimeline.empty();
    protected long lastHitSoundChartTimeMs;
    protected float density;
    protected EditorSettings settings = new EditorSettings();
    protected Bitmap blurredBackgroundIllustration;

    protected GestureMode gestureMode = GestureMode.NONE;
    protected float gestureStartX;
    protected float gestureStartY;
    protected float gestureLastY;
    protected double previewStartBeat;
    protected double previewEndBeat;
    protected EventType previewEventType;
    protected boolean movedDuringGesture;
    protected LineEvent draggedEvent;
    protected EventLayer draggedEventLayer;
    protected BeatTime dragBeforeStart;
    protected BeatTime dragBeforeEnd;
    protected BeatTime dragCandidateStart;
    protected BeatTime dragCandidateEnd;
    protected EventDragValidator.Error dragValidation = EventDragValidator.Error.NONE;
    protected XYBindingValidator.Error xyDragValidation = XYBindingValidator.Error.NONE;
    protected LineEvent draggedEventPair;
    protected BeatTime dragPairBeforeStart;
    protected BeatTime dragPairBeforeEnd;
    protected boolean eventDragPointerMoved;
    protected final List<Note> draggedNotes = new ArrayList<>();
    protected final List<Note> noteDragBefore = new ArrayList<>();
    protected BeatTime noteDragAnchorBeat;
    protected double noteDragAnchorX;
    protected BeatTime noteDragDeltaBeat = BeatTime.zero();
    protected double noteDragDeltaX;
    protected NoteTouchOperation.Error noteDragValidation = NoteTouchOperation.Error.NONE;
    protected Note resizedHold;
    protected BeatTime holdBeforeStart;
    protected BeatTime holdBeforeEnd;
    protected BeatTime holdCandidateStart;
    protected BeatTime holdCandidateEnd;

    @Override public android.content.res.Resources getResources() {
        return UiText.resources(getContext());
    }

    public void relocalizeUi() {
        easingEntries = UiText.resources(getContext()).getStringArray(R.array.event_easing_entries);
        invalidate();
    }

    protected EditorSurfaceView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        pixelsPerBeat = 96f * density;
        noteTextures = new NoteTextureSet(getResources());
        previewRenderer = new PreviewRenderer(noteTextures, density,
                "AUTOPLAY", context.getAssets());
        easingEntries = UiText.resources(getContext()).getStringArray(R.array.event_easing_entries);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2f * density);
    }

    protected final void drawNoteBackground(
            Canvas canvas, float left, float top, float right, float bottom) {
        editorTimelinePainter.drawNoteBackground(canvas, left, top, right, bottom);
    }

    protected final void prepareTimelineGridPaint() {
        editorTimelinePainter.prepareTimelineGridPaint();
    }

    protected final void drawNoteGrid(
            Canvas canvas, float left, float top, float right, float bottom) {
        editorTimelinePainter.drawNoteGrid(canvas, left, top, right, bottom);
    }

    protected final void drawCurrentBeatLine(Canvas canvas, float left, float right, float bottom) {
        editorTimelinePainter.drawCurrentBeatLine(canvas, left, right, bottom);
    }

    /** Common event-grid painter for Events and Shaders, in the caller's local coordinates. */
    public final void drawTimelineRows(Canvas canvas, float left, float top, float right,
                                      float bottom, double position, float scale, int divisions) {
        editorTimelinePainter.drawTimelineRows(canvas, left, top, right, bottom, position, scale, divisions);
    }
    /** Include text that intersects an edge even while its grid line is outside it. */
    public final void drawTimelineBeatNumbers(Canvas canvas, float left, float top, float right,
                                             float bottom, double position, float scale,
                                             boolean rightAligned) {
        editorTimelinePainter.drawTimelineBeatNumbers(canvas, left, top, right, bottom, position, scale, rightAligned);
    }

    public final void drawTimelineBaseline(Canvas canvas, float right, float bottom) {
        editorTimelinePainter.drawTimelineBaseline(canvas, right, bottom);
    }
    public final void drawTimelineBlock(Canvas canvas, RectF rect, int color) {
        editorTimelinePainter.drawTimelineBlock(canvas, rect, color);
    }
    public final void drawTimelineSelection(Canvas canvas, RectF rect, float startY, float endY) {
        editorTimelinePainter.drawTimelineSelection(canvas, rect, startY, endY);
    }
    public final void drawTimelineKeySelection(Canvas canvas, RectF rect, float timeY) {
        editorTimelinePainter.drawTimelineKeySelection(canvas, rect, timeY);
    }
    public final float timelineScale() { return pixelsPerBeat; }
    public final double timelineScrollMultiplier() { return settings.timelineScrollSpeed; }
    public final double eventScrollMultiplier() { return settings.eventScrollSpeed; }
    public final boolean timelineCurvesVisible() { return settings.drawEventCurves; }
    public final void scaleTimeline(float factor) {
        pixelsPerBeat = clamp(pixelsPerBeat * factor, 38f * density, 280f * density);
        invalidate();
    }

    public final void scaleShaderTimeline(float factor) {
        if (!Float.isFinite(factor) || factor <= 0) return;
        pixelsPerBeat = clamp(pixelsPerBeat * factor, 8f * density, 280f * density);
        invalidate();
    }

    protected final void drawEventGrid(
            Canvas canvas, float left, float top, float right, float bottom) {
        editorTimelinePainter.drawEventGrid(canvas, left, top, right, bottom);
    }

    protected final void drawEventHeaders(Canvas canvas, float left, float top, float right) {
        editorTimelinePainter.drawEventHeaders(canvas, left, top, right);
    }

    protected final void drawNotes(
            Canvas canvas, float left, float top, float right, float bottom) {
        editorNotePainter.drawNotes(canvas, left, top, right, bottom);
    }

    protected final RectF holdBounds(float x, float startY, float endY, float width) {
        return editorNotePainter.holdBounds(x, startY, endY, width);
    }

    protected final void drawBitmap(
            Canvas canvas, Bitmap bitmap, RectF destination, int alpha) {
        editorNotePainter.drawBitmap(canvas, bitmap, destination, alpha);
    }

    protected final void drawHoldTexture(Canvas canvas, float x, float startY, float endY,
                                         float width, float naturalWidth, boolean useMultiHit, int alpha) {
        editorNotePainter.drawHoldTexture(canvas, x, startY, endY, width, naturalWidth, useMultiHit, alpha);
    }

    protected final void drawEvents(
            Canvas canvas, float left, float top, float right, float bottom) {
        editorEventPainter.drawEvents(canvas, left, top, right, bottom);
    }

    protected final void drawEventEasingCurve(
            Canvas canvas, LineEvent event, RectF rect, boolean invalid) {
        editorTimelinePainter.drawEventEasingCurve(canvas, event, rect, invalid);
    }

    public final void drawTimelineEasingCurve(Canvas canvas, RectF rect, boolean invalid,
                                                boolean selected, java.util.function.DoubleUnaryOperator easing) {
        editorTimelinePainter.drawTimelineEasingCurve(canvas, rect, invalid, selected, easing);
    }

    protected final void drawEventHandles(
            Canvas canvas, float centerX, float startY, float endY, boolean invalidDrag) {
        editorEventPainter.drawEventHandles(canvas, centerX, startY, endY, invalidDrag);
    }

    protected final void drawHoldHandles(
            Canvas canvas, float centerX, float startY, float endY, boolean invalidDrag) {
        editorNotePainter.drawHoldHandles(canvas, centerX, startY, endY, invalidDrag);
    }

    protected final BeatTime visibleNoteStart(Note note) {
        if (note == resizedHold && holdCandidateStart != null) return holdCandidateStart;
        int index = draggedNotes.indexOf(note);
        if (index >= 0 && index < noteDragBefore.size()) {
            try {
                return noteDragBefore.get(index).startTime.plus(noteDragDeltaBeat);
            } catch (ArithmeticException ignored) {
                return note.startTime;
            }
        }
        return note.startTime;
    }

    protected final BeatTime visibleNoteEnd(Note note) {
        if (note == resizedHold && holdCandidateEnd != null) return holdCandidateEnd;
        int index = draggedNotes.indexOf(note);
        if (index >= 0 && index < noteDragBefore.size()) {
            try {
                return noteDragBefore.get(index).endTime.plus(noteDragDeltaBeat);
            } catch (ArithmeticException ignored) {
                return note.endTime;
            }
        }
        return note.endTime;
    }

    protected final double visibleNoteX(Note note) {
        int index = draggedNotes.indexOf(note);
        if (index >= 0 && index < noteDragBefore.size()) {
            return noteDragBefore.get(index).positionX + noteDragDeltaX;
        }
        return note.positionX;
    }

    protected final void drawSelectionRectangle(Canvas canvas) {
        editorEventPainter.drawSelectionRectangle(canvas);
    }

    protected final void drawPlacementPreview(
            Canvas canvas, float split, float top, float bottom) {
        editorEventPainter.drawPlacementPreview(canvas, split, top, bottom);
    }

    protected final JudgeLine currentLine() {
        lineIndex = Math.max(0, Math.min(chart.judgeLines.size() - 1, lineIndex));
        return chart.judgeLines.get(lineIndex);
    }

    protected final EventLayer currentLayer() {
        return currentLine().layer(eventLayerIndex);
    }

    protected final EventType eventTypeAtX(float x, float split) {
        float columnWidth = Math.max(1f, (getWidth() - split) / EventType.values().length);
        int column = (int) ((x - split) / columnWidth);
        return EventType.fromColumn(column);
    }

    protected final boolean hasSelection() {
        return selectionCount() > 0;
    }

    protected final int selectionCount() {
        int count = selectedNotes.size() + selectedEvents.size();
        if (selectedNote != null && !selectedNotes.contains(selectedNote)) count++;
        if (selectedEvent != null && !selectedEvents.contains(selectedEvent)) count++;
        return count;
    }

    protected static RectF normalized(RectF value) {
        return new RectF(Math.min(value.left, value.right), Math.min(value.top, value.bottom),
                Math.max(value.left, value.right), Math.max(value.top, value.bottom));
    }

    protected final float editorSplitX() {
        return WorkbenchLayout.split(getWidth(), density);
    }

    protected final float toolbarHeight() {
        return 48f * density;
    }

    protected final float bottomBarHeight() {
        return WorkbenchLayout.footer(density);
    }

    protected final float timelineY(float editorBottom) {
        return EditorTimelineMetrics.baselineY(editorBottom, density);
    }

    protected final boolean isInEditor(float y) {
        return y > toolbarHeight() && y < getHeight() - bottomBarHeight();
    }

    protected final float beatToY(double beat, float centerY) {
        return centerY - (float) ((beat - currentBeat) * pixelsPerBeat);
    }

    protected final double yToBeat(float y, float centerY) {
        return currentBeat + (centerY - y) / pixelsPerBeat;
    }

    protected final double snapBeat(double beat) {
        return Math.max(0.0, Math.round(beat * subdivision) / (double) subdivision);
    }

    protected final double snapChartX(double x) {
        return VerticalGrid.snap(x, CHART_X_MIN, CHART_X_MAX, verticalGridLines);
    }

    protected static float chartXToScreen(double x, float left, float right) {
        return left + (float) ((x - CHART_X_MIN) / (CHART_X_MAX - CHART_X_MIN)
                * (right - left));
    }

    protected static double screenToChartX(float x, float left, float right) {
        double normalized = (x - left) / Math.max(1f, right - left);
        return CHART_X_MIN + normalized * (CHART_X_MAX - CHART_X_MIN);
    }

    protected static int clampAlpha(int alpha) {
        return EditorDrawingStyle.clampAlpha(alpha);
    }

    protected int eventColor(EventType type, boolean linked) {
        return editorDrawingStyle.eventColor(type, linked);
    }

    protected final String eventEasingLabel(LineEvent event) {
        return editorDrawingStyle.eventEasingLabel(event);
    }

    protected final void drawSelection(Canvas canvas, RectF rect) {
        strokePaint.setColor(Color.WHITE);
        strokePaint.setStrokeWidth(2.5f * density);
        canvas.drawRoundRect(new RectF(
                        rect.left - 3f * density, rect.top - 3f * density,
                        rect.right + 3f * density, rect.bottom + 3f * density),
                7f * density, 7f * density, strokePaint);
    }

    protected static double eventStep(EventType type) {
        return EditorDrawingStyle.eventStep(type);
    }

    protected static double normalizeEventValue(EventType type, double value) {
        return EditorDrawingStyle.normalizeEventValue(type, value);
    }

    protected static void copyNoteFields(Note source, Note target) {
        EditorFieldCopies.copyNoteFields(source, target);
    }

    protected static void copyEventFields(LineEvent source, LineEvent target) {
        EditorFieldCopies.copyEventFields(source, target);
    }

    protected static int withAlpha(int color, int alpha) {
        return EditorDrawingStyle.withAlpha(color, alpha);
    }

    protected final String ellipsize(String text, float maximumWidth) {
        return editorDrawingStyle.ellipsize(text, maximumWidth);
    }

    protected final String ellipsizeFromEnd(String text, float maximumWidth) {
        return editorDrawingStyle.ellipsizeFromEnd(text, maximumWidth);
    }

    protected static String compactValue(double value) {
        return EditorDrawingStyle.compactValue(value);
    }

    protected static String formatMillis(long milliseconds) {
        return EditorDrawingStyle.formatMillis(milliseconds);
    }

    protected static float clamp(float value, float min, float max) {
        return EditorDrawingStyle.clamp(value, min, max);
    }
}
