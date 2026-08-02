package com.xpe.mobile.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
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
import com.xpe.mobile.model.NoteType;
import com.xpe.mobile.preview.PreviewRenderer;
import com.xpe.mobile.preview.PreviewTextureDecoder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    protected final String[] easingEntries;

    protected ChartDocument chart;
    protected String projectName = "";
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

    protected EditorSurfaceView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        pixelsPerBeat = 96f * density;
        noteTextures = new NoteTextureSet(getResources());
        previewRenderer = new PreviewRenderer(noteTextures, density,
                getResources().getString(R.string.preview_hud_autoplay));
        easingEntries = getResources().getStringArray(R.array.event_easing_entries);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2f * density);
    }

    protected final void drawNoteBackground(
            Canvas canvas, float left, float top, float right, float bottom) {
        paint.setColor(Color.rgb(10, 14, 18));
        canvas.drawRect(left, top, right, bottom, paint);
        Bitmap illustration = backgroundIllustration;
        if (illustration == null || illustration.isRecycled()) return;
        RectF destination = new RectF(left, top, right, bottom);
        CenterCropCalculator.Crop crop = CenterCropCalculator.calculate(
                illustration.getWidth(), illustration.getHeight(),
                destination.width(), destination.height());
        Rect source = new Rect(crop.left, crop.top, crop.right, crop.bottom);
        int save = canvas.save();
        canvas.clipRect(destination);
        backgroundPaint.setAlpha(settings.backgroundBrightness);
        canvas.drawBitmap(illustration, source, destination, backgroundPaint);
        canvas.restoreToCount(save);
    }

    protected final void drawNoteGrid(
            Canvas canvas, float left, float top, float right, float bottom) {
        float centerY = timelineY(bottom);
        paint.setStrokeWidth(1f * density);
        TimelineGridRange.Range range = TimelineGridRange.visible(
                currentBeat, centerY, top, bottom, pixelsPerBeat, subdivision);
        for (int step = range.firstStep; step <= range.lastStep; step++) {
            double beat = step / (double) subdivision;
            float y = beatToY(beat, centerY);
            if (y < top || y > bottom) continue;
            boolean major = Math.floorMod(step, subdivision) == 0;
            paint.setColor(major ? GRID_MAJOR : GRID_MINOR);
            canvas.drawLine(left, y, right, y, paint);
            if (major) {
                paint.setTextSize(11f * density);
                paint.setColor(Color.LTGRAY);
                canvas.drawText(Integer.toString((int) Math.floor(beat)),
                        5f * density, y - 3f * density, paint);
            }
        }

        for (int index = 0; index < verticalGridLines; index++) {
            float x = VerticalGrid.screenX(index, verticalGridLines, left, right);
            paint.setColor(index == verticalGridLines / 2
                    ? Color.rgb(83, 101, 62) : GRID_MINOR);
            canvas.drawLine(x, top, x, bottom, paint);
        }

        paint.setStrokeWidth((float) Math.max(1.0, settings.lineDefaultWidth) * density);
        paint.setColor(Color.rgb((settings.lineColorRgb >> 16) & 0xff,
                (settings.lineColorRgb >> 8) & 0xff, settings.lineColorRgb & 0xff));
        canvas.drawLine(left, centerY, right, centerY, paint);
    }

    protected final void drawEventGrid(
            Canvas canvas, float left, float top, float right, float bottom) {
        float centerY = timelineY(bottom);
        paint.setStrokeWidth(1f * density);
        TimelineGridRange.Range range = TimelineGridRange.visible(
                currentBeat, centerY, top, bottom, pixelsPerBeat, subdivision);
        for (int step = range.firstStep; step <= range.lastStep; step++) {
            double beat = step / (double) subdivision;
            float y = beatToY(beat, centerY);
            if (y < top || y > bottom) continue;
            boolean major = Math.floorMod(step, subdivision) == 0;
            paint.setColor(major ? GRID_MAJOR : GRID_MINOR);
            canvas.drawLine(left, y, right, y, paint);
        }

        EventType[] types = EventType.values();
        float width = (right - left) / types.length;
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(10.5f * density);
        for (int index = 0; index < types.length; index++) {
            float x = left + index * width;
            paint.setColor(Color.rgb(58, 67, 76));
            canvas.drawLine(x, top, x, bottom, paint);
            paint.setColor(types[index] == selectedEventType && tool == Tool.EVENT
                    ? ACCENT : Color.LTGRAY);
            canvas.drawText(types[index].label,
                    x + width / 2f, top + 17f * density, paint);
        }
        paint.setColor(Color.rgb(58, 67, 76));
        canvas.drawLine(right, top, right, bottom, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    protected final void drawNotes(
            Canvas canvas, float left, float top, float right, float bottom) {
        float centerY = timelineY(bottom);
        Set<Note> multiHitNotes = settings.highlightSimultaneousNotes
                ? NoteMultiHintResolver.resolve(chart) : new LinkedHashSet<>();
        for (Note note : currentLine().notes) {
            boolean useMultiHit = multiHitNotes.contains(note);
            BeatTime visibleStart = visibleNoteStart(note);
            BeatTime visibleEnd = visibleNoteEnd(note);
            float startY = beatToY(visibleStart.toDouble(), centerY);
            float x = chartXToScreen(visibleNoteX(note), left, right);
            float baseWidth = Math.max(20f * density,
                    (float) settings.noteWidthPixels * density * (float) note.size);
            float textureWidth = baseWidth * noteTextures.widthScale(useMultiHit);
            RectF bounds;

            if (note.type == NoteType.HOLD) {
                float endY = beatToY(visibleEnd.toDouble(), centerY);
                bounds = holdBounds(x, startY, endY, textureWidth);
                if (bounds.bottom < top || bounds.top > bottom) continue;
                drawHoldTexture(canvas, x, startY, endY, textureWidth, useMultiHit,
                        clampAlpha(note.alpha));
            } else {
                Bitmap bitmap = noteTextures.bitmap(note.type, useMultiHit);
                float textureHeight = textureWidth * bitmap.getHeight() / bitmap.getWidth();
                bounds = new RectF(x - textureWidth / 2f, startY - textureHeight / 2f,
                        x + textureWidth / 2f, startY + textureHeight / 2f);
                if (bounds.bottom < top || bounds.top > bottom) continue;
                drawBitmap(canvas, bitmap, bounds, clampAlpha(note.alpha));
            }

            if (note.fake) {
                strokePaint.setColor(Color.WHITE);
                strokePaint.setStrokeWidth(1.5f * density);
                canvas.drawRoundRect(bounds, 5f * density, 5f * density, strokePaint);
            }
            if (note == selectedNote || selectedNotes.contains(note)) {
                drawSelection(canvas, bounds);
                if (note == selectedNote && selectionCount() == 1
                        && note.type == NoteType.HOLD) {
                    drawHoldHandles(canvas, x, startY,
                            beatToY(visibleEnd.toDouble(), centerY),
                            noteDragValidation != NoteTouchOperation.Error.NONE);
                }
            }
        }
    }

    protected final RectF holdBounds(float x, float startY, float endY, float width) {
        return new RectF(x - width / 2f, Math.min(startY, endY),
                x + width / 2f, Math.max(startY, endY));
    }

    protected final void drawBitmap(
            Canvas canvas, Bitmap bitmap, RectF destination, int alpha) {
        noteTexturePaint.setAlpha(alpha);
        canvas.drawBitmap(bitmap, null, destination, noteTexturePaint);
    }

    protected final void drawHoldTexture(Canvas canvas, float x, float startY, float endY,
                                         float width, boolean useMultiHit, int alpha) {
        Bitmap bitmap = noteTextures.bitmap(NoteType.HOLD, useMultiHit);
        if (startY < endY) {
            RectF destination = holdBounds(x, startY, endY, width);
            drawBitmap(canvas, bitmap, destination, alpha);
            return;
        }

        int tailPixels = noteTextures.holdTailPixels(useMultiHit);
        int headPixels = noteTextures.holdHeadPixels(useMultiHit);
        int bodyBottom = Math.max(tailPixels + 1, bitmap.getHeight() - headPixels);
        float left = x - width / 2f;
        float right = x + width / 2f;
        float tailHeight = width * tailPixels / bitmap.getWidth();
        float headHeight = width * headPixels / bitmap.getWidth();

        noteTexturePaint.setAlpha(alpha);
        canvas.drawBitmap(bitmap,
                new Rect(0, tailPixels, bitmap.getWidth(), bodyBottom),
                new RectF(left, endY, right, startY), noteTexturePaint);
        canvas.drawBitmap(bitmap,
                new Rect(0, 0, bitmap.getWidth(), tailPixels),
                new RectF(left, endY, right, endY + tailHeight), noteTexturePaint);
        canvas.drawBitmap(bitmap,
                new Rect(0, bitmap.getHeight() - headPixels,
                        bitmap.getWidth(), bitmap.getHeight()),
                new RectF(left, startY - headHeight, right, startY), noteTexturePaint);
    }

    protected final void drawEvents(
            Canvas canvas, float left, float top, float right, float bottom) {
        EventLayer layer = currentLayer();
        EventType[] types = EventType.values();
        float columnWidth = (right - left) / types.length;
        float centerY = timelineY(bottom);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(8.5f * density);

        for (int typeIndex = 0; typeIndex < types.length; typeIndex++) {
            EventType type = types[typeIndex];
            for (LineEvent event : layer.events(type)) {
                boolean draggedPreview = event == draggedEvent || event == draggedEventPair;
                BeatTime visibleStart = draggedPreview && dragCandidateStart != null
                        ? dragCandidateStart : event.startTime;
                BeatTime visibleEnd = draggedPreview && dragCandidateEnd != null
                        ? dragCandidateEnd : event.endTime;
                float startY = beatToY(visibleStart.toDouble(), centerY);
                float endY = beatToY(visibleEnd.toDouble(), centerY);
                float rectTop = Math.min(startY, endY);
                float rectBottom = Math.max(startY, endY);
                if (rectBottom < top || rectTop > bottom) continue;
                float x1 = left + typeIndex * columnWidth + 3f * density;
                float x2 = left + (typeIndex + 1) * columnWidth - 3f * density;
                if (rectBottom - rectTop < 6f * density) rectBottom = rectTop + 6f * density;
                RectF rect = new RectF(x1, rectTop, x2, rectBottom);
                boolean invalidDrag = draggedPreview
                        && (dragValidation != EventDragValidator.Error.NONE
                        || xyDragValidation != XYBindingValidator.Error.NONE);
                paint.setColor(invalidDrag
                        ? INVALID_PREVIEW : eventColor(type, event.linkGroup != 0));
                canvas.drawRoundRect(rect, 4f * density, 4f * density, paint);
                if (settings.drawEventCurves) {
                    drawEventEasingCurve(canvas, event, rect, invalidDrag);
                }
                if (settings.drawEventNumbers && rect.height() > 30f * density) {
                    paint.setColor(Color.WHITE);
                    paint.setTextSize(7.5f * density);
                    canvas.drawText(compactValue(event.end), rect.centerX(),
                            rect.top + 9f * density, paint);
                    canvas.drawText(compactValue(event.start), rect.centerX(),
                            rect.bottom - 3f * density, paint);
                }
                if (event == selectedEvent || selectedEvents.contains(event)) {
                    drawSelection(canvas, rect);
                    if (event == selectedEvent && selectionCount() == 1) {
                        drawEventHandles(canvas, rect.centerX(), startY, endY, invalidDrag);
                    }
                }
            }
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }

    protected final void drawEventEasingCurve(
            Canvas canvas, LineEvent event, RectF rect, boolean invalid) {
        float insetX = Math.min(5f * density, rect.width() * 0.14f);
        float insetY = Math.min(4f * density, rect.height() * 0.12f);
        float curveLeft = rect.left + insetX;
        float curveRight = rect.right - insetX;
        float curveTop = rect.top + insetY;
        float curveBottom = rect.bottom - insetY;
        if (curveRight <= curveLeft || curveBottom <= curveTop) return;

        int samples = Math.max(10, Math.min(72,
                Math.round((curveBottom - curveTop) / Math.max(2f * density, 1f))));
        eventCurvePath.reset();
        for (int index = 0; index <= samples; index++) {
            double t = index / (double) samples;
            double evaluated = event.easedProgressAt(t);
            float progress = Double.isFinite(evaluated)
                    ? clamp((float) evaluated, -2f, 3f) : (float) t;
            float x = curveLeft + progress * (curveRight - curveLeft);
            float y = curveBottom - (float) t * (curveBottom - curveTop);
            if (index == 0) eventCurvePath.moveTo(x, y);
            else eventCurvePath.lineTo(x, y);
        }

        int save = canvas.save();
        canvas.clipRect(rect);
        strokePaint.setColor(invalid ? Color.WHITE : EVENT_EASING_CURVE);
        strokePaint.setStrokeWidth((event == selectedEvent ? 2.6f : 2.1f) * density);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(eventCurvePath, strokePaint);
        canvas.restoreToCount(save);
    }

    protected final void drawEventHandles(
            Canvas canvas, float centerX, float startY, float endY, boolean invalidDrag) {
        float radius = 9f * density;
        paint.setColor(invalidDrag ? INVALID_PREVIEW : Color.WHITE);
        canvas.drawCircle(centerX, startY, radius, paint);
        canvas.drawCircle(centerX, endY, radius, paint);
        paint.setColor(Color.rgb(25, 31, 38));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(8f * density);
        float baselineOffset = -(paint.ascent() + paint.descent()) / 2f;
        canvas.drawText("S", centerX, startY + baselineOffset, paint);
        canvas.drawText("E", centerX, endY + baselineOffset, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    protected final void drawHoldHandles(
            Canvas canvas, float centerX, float startY, float endY, boolean invalidDrag) {
        drawEventHandles(canvas, centerX, startY, endY, invalidDrag);
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
        if (gestureMode != GestureMode.RECT_SELECT || selectionRectangle == null) return;
        RectF bounds = normalized(selectionRectangle);
        paint.setColor(withAlpha(ACCENT, 48));
        canvas.drawRect(bounds, paint);
        strokePaint.setColor(ACCENT);
        strokePaint.setStrokeWidth(2f * density);
        canvas.drawRect(bounds, strokePaint);
    }

    protected final void drawPlacementPreview(
            Canvas canvas, float split, float top, float bottom) {
        if (gestureMode == GestureMode.HOLD_PREVIEW) {
            float centerY = timelineY(bottom);
            float x = chartXToScreen(screenToChartX(gestureStartX, 0f, split), 0f, split);
            float startY = beatToY(previewStartBeat, centerY);
            float endY = beatToY(previewEndBeat, centerY);
            drawHoldTexture(canvas, x, startY, endY, 58f * density, false, 150);
        } else if (gestureMode == GestureMode.EVENT_PREVIEW && previewEventType != null) {
            EventType[] types = EventType.values();
            int index = previewEventType.ordinal();
            float width = (getWidth() - split) / types.length;
            float centerY = timelineY(bottom);
            RectF rect = new RectF(
                    split + index * width + 3f * density,
                    Math.min(beatToY(previewStartBeat, centerY),
                            beatToY(previewEndBeat, centerY)),
                    split + (index + 1) * width - 3f * density,
                    Math.max(beatToY(previewStartBeat, centerY),
                            beatToY(previewEndBeat, centerY)));
            paint.setColor(withAlpha(eventColor(previewEventType, false), 145));
            canvas.drawRoundRect(rect, 4f * density, 4f * density, paint);
        }
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
        return getWidth() * 0.70f;
    }

    protected final float toolbarHeight() {
        return 48f * density;
    }

    protected final float bottomBarHeight() {
        return 96f * density;
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
        return Math.max(0, Math.min(255, alpha));
    }

    protected int eventColor(EventType type, boolean linked) {
        int color;
        switch (type) {
            case MOVE_X: color = Color.rgb(80, 188, 166); break;
            case MOVE_Y: color = Color.rgb(77, 160, 205); break;
            case ROTATE: color = Color.rgb(183, 114, 214); break;
            case ALPHA: color = Color.rgb(224, 186, 83); break;
            case SPEED: color = Color.rgb(225, 111, 104); break;
            default: color = Color.GRAY; break;
        }
        if (!linked) return color;
        return Color.rgb((Color.red(color) + 30) / 2,
                (Color.green(color) + 40) / 2,
                (Color.blue(color) + 90) / 2);
    }

    protected final String eventEasingLabel(LineEvent event) {
        if (event.bezier && event.type != EventType.SPEED) {
            return getResources().getString(R.string.controls_event_ease_custom);
        }
        int type = event.type == EventType.SPEED ? 1 : event.easingType;
        if (type < 1 || type > easingEntries.length) {
            return getResources().getString(R.string.controls_event_ease_unknown, type);
        }
        int index = Math.max(0, Math.min(easingEntries.length - 1, type - 1));
        return easingEntries[index];
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
        switch (type) {
            case ROTATE: return 0.25;
            case SPEED: return 0.1;
            default: return 10.0;
        }
    }

    protected static double normalizeEventValue(EventType type, double value) {
        if (type == EventType.ALPHA) return Math.max(-255.0, Math.min(255.0, value));
        return value;
    }

    protected static void copyNoteFields(Note source, Note target) {
        target.above = source.above;
        target.alpha = source.alpha;
        target.startTime = source.startTime;
        target.endTime = source.endTime;
        target.fake = source.fake;
        target.positionX = source.positionX;
        target.size = source.size;
        target.speed = source.speed;
        target.type = source.type;
        target.visibleTime = source.visibleTime;
        target.yOffset = source.yOffset;
        target.hasTint = source.hasTint;
        target.tintRgb = source.tintRgb;
        target.hasHitEffectTint = source.hasHitEffectTint;
        target.hitEffectTintRgb = source.hitEffectTintRgb;
        target.judgeArea = source.judgeArea;
    }

    protected static void copyEventFields(LineEvent source, LineEvent target) {
        target.type = source.type;
        target.startTime = source.startTime;
        target.endTime = source.endTime;
        target.start = source.start;
        target.end = source.end;
        target.easingType = source.easingType;
        target.easingLeft = source.easingLeft;
        target.easingRight = source.easingRight;
        target.linkGroup = source.linkGroup;
        target.bezier = source.bezier;
        System.arraycopy(source.bezierPoints, 0, target.bezierPoints, 0,
                source.bezierPoints.length);
    }

    protected static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    protected final String ellipsize(String text, float maximumWidth) {
        if (text == null || maximumWidth <= 0f) return "";
        if (paint.measureText(text) <= maximumWidth) return text;
        String suffix = "…";
        int end = text.length();
        while (end > 0
                && paint.measureText(text, 0, end) + paint.measureText(suffix) > maximumWidth) {
            end--;
        }
        return end <= 0 ? suffix : text.substring(0, end) + suffix;
    }

    protected final String ellipsizeFromEnd(String text, float maximumWidth) {
        if (text == null || maximumWidth <= 0f) return "";
        if (paint.measureText(text) <= maximumWidth) return text;
        String prefix = "…";
        int start = 0;
        while (start < text.length()
                && paint.measureText(text, start, text.length())
                + paint.measureText(prefix) > maximumWidth) {
            start++;
        }
        return start >= text.length() ? prefix : prefix + text.substring(start);
    }

    protected static String compactValue(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.US, "%.2f", value);
    }

    protected static String formatMillis(long milliseconds) {
        long safe = Math.max(0L, milliseconds);
        long minutes = safe / 60000L;
        long seconds = (safe / 1000L) % 60L;
        long millis = safe % 1000L;
        return String.format(Locale.US, "%d:%02d.%03d", minutes, seconds, millis);
    }

    protected static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
