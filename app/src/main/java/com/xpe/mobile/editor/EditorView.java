package com.xpe.mobile.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.xpe.mobile.R;
import com.xpe.mobile.audio.HitSoundTimeline;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;
import com.xpe.mobile.model.StoryboardEventType;
import com.xpe.mobile.preview.ChartEvaluator;
import com.xpe.mobile.preview.PreviewRenderer;
import com.xpe.mobile.preview.RenderScene;
import com.xpe.mobile.preview.PreviewTextureDecoder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class EditorView extends View {
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
        void showMessage(String message);
        boolean isAudioReady();
        boolean isAudioPlaying();
        void startAudio(long positionMs, float speed);
        void pauseAudio();
        void seekAudio(long positionMs);
        void playHitSound(NoteType type);
        long audioPositionMs();
        long audioDurationMs();
    }

    public enum BpmApplyResult {
        APPLIED,
        TARGET_NOT_FOUND,
        INVALID_VALUE,
        DUPLICATE_START_TIME,
        FIRST_ENTRY_LOCKED,
        LAST_ENTRY_REQUIRED
    }

    public enum PropertyApplyResult {
        APPLIED,
        TARGET_NOT_FOUND,
        INVALID_VALUES,
        EVENT_OVERLAP,
        XY_BINDING_INVALID
    }

    public enum LineApplyResult {
        APPLIED,
        TARGET_NOT_FOUND,
        LINE_ZERO_PROTECTED,
        LAST_LINE_REQUIRED,
        INVALID_PROPERTIES
    }

    public enum StoryboardApplyResult {
        APPLIED,
        TARGET_NOT_FOUND,
        INVALID_VALUES,
        EVENT_OVERLAP,
        NO_PREVIOUS,
        NO_CHANGE,
        SPLIT_OUTSIDE
    }

    private enum Tool {
        SELECT,
        TAP,
        DRAG,
        FLICK,
        HOLD,
        EVENT
    }

    private enum GestureMode {
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

    private enum RectangleSelectionMode {
        NONE,
        ADD,
        REMOVE
    }

    private enum BottomPanel {
        CREATE,
        EDIT,
        ARRANGE
    }

    private static final double CHART_X_MIN = -675.0;
    private static final double CHART_X_MAX = 675.0;
    private static final int TOOLBAR_COLOR = Color.rgb(24, 30, 37);
    private static final int PANEL_COLOR = Color.rgb(30, 37, 45);
    private static final int GRID_MINOR = Color.rgb(39, 65, 53);
    private static final int GRID_MAJOR = Color.rgb(121, 61, 61);
    private static final int ACCENT = Color.rgb(91, 211, 172);
    private static final int INVALID_PREVIEW = Color.rgb(230, 82, 82);
    private static final int EVENT_EASING_CURVE = Color.rgb(255, 150, 38);
    private static final int EDITOR_GAMEPLAY_ALPHA = 128;
    private static final int FREE_SPLIT_DIVISION = 1_000_000;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint noteTexturePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path eventCurvePath = new Path();
    private final NoteTextureSet noteTextures;
    private final PreviewRenderer previewRenderer;
    private final String[] easingEntries;
    private final ScaleGestureDetector scaleDetector;
    private final EditHistory history;
    private final List<ButtonSpec> buttons = new ArrayList<>();

    private Callback callback;
    private ChartDocument chart;
    private String projectName = "";
    private Tool tool = Tool.SELECT;
    private int lineIndex;
    private int eventLayerIndex;
    private int subdivision = 4;
    private double currentBeat;
    private float pixelsPerBeat;
    private Note selectedNote;
    private LineEvent selectedEvent;
    private final Set<Note> selectedNotes = new LinkedHashSet<>();
    private final Set<LineEvent> selectedEvents = new LinkedHashSet<>();
    private ChartClipboard.Snapshot clipboard;
    private RectangleSelectionMode rectangleSelectionMode = RectangleSelectionMode.NONE;
    private NoteMoveMode noteMoveMode = NoteMoveMode.OFF;
    private RectF selectionRectangle;
    private BottomPanel bottomPanel = BottomPanel.CREATE;
    private int bottomActionPage;
    private int verticalGridLines = VerticalGrid.defaultCount();
    private final RectF playbackSeekBounds = new RectF();
    private EventType selectedEventType = EventType.MOVE_X;
    private boolean playing;
    private boolean chartDirty;
    private boolean xyBindingEnabled;
    private long lastFrameMs;
    private float playbackSpeed = 1.0f;
    private long packageOffsetMs;
    private boolean useRpe170Speed;
    private Bitmap backgroundIllustration;
    private Map<String, PreviewTextureDecoder.Texture> previewLineTextures =
            Collections.emptyMap();
    private boolean playbackScrubbing;
    private boolean previewMode;
    private HitSoundTimeline hitSoundTimeline = HitSoundTimeline.empty();
    private long lastHitSoundChartTimeMs;
    private float density;
    private EditorSettings settings = new EditorSettings();

    private GestureMode gestureMode = GestureMode.NONE;
    private float gestureStartX;
    private float gestureStartY;
    private float gestureLastY;
    private double previewStartBeat;
    private double previewEndBeat;
    private EventType previewEventType;
    private boolean movedDuringGesture;
    private LineEvent draggedEvent;
    private EventLayer draggedEventLayer;
    private BeatTime dragBeforeStart;
    private BeatTime dragBeforeEnd;
    private BeatTime dragCandidateStart;
    private BeatTime dragCandidateEnd;
    private EventDragValidator.Error dragValidation = EventDragValidator.Error.NONE;
    private XYBindingValidator.Error xyDragValidation = XYBindingValidator.Error.NONE;
    private LineEvent draggedEventPair;
    private BeatTime dragPairBeforeStart;
    private BeatTime dragPairBeforeEnd;
    private boolean eventDragPointerMoved;
    private final List<Note> draggedNotes = new ArrayList<>();
    private final List<Note> noteDragBefore = new ArrayList<>();
    private BeatTime noteDragAnchorBeat;
    private double noteDragAnchorX;
    private BeatTime noteDragDeltaBeat = BeatTime.zero();
    private double noteDragDeltaX;
    private NoteTouchOperation.Error noteDragValidation = NoteTouchOperation.Error.NONE;
    private Note resizedHold;
    private BeatTime holdBeforeStart;
    private BeatTime holdBeforeEnd;
    private BeatTime holdCandidateStart;
    private BeatTime holdCandidateEnd;

    public EditorView(Context context) {
        super(context);
        history = new EditHistory(300, this::markChartDirty);
        density = getResources().getDisplayMetrics().density;
        pixelsPerBeat = 96f * density;
        noteTextures = new NoteTextureSet(getResources());
        previewRenderer = new PreviewRenderer(noteTextures, density,
                getResources().getString(R.string.preview_hud_autoplay));
        easingEntries = getResources().getStringArray(R.array.event_easing_entries);
        setFocusable(true);
        setFocusableInTouchMode(true);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2f * density);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                cancelActiveGesture();
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                pixelsPerBeat = clamp(pixelsPerBeat * detector.getScaleFactor(), 38f * density, 280f * density);
                invalidate();
                return true;
            }
        });
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

    public EditorSettings getSettings() {
        return settings.copy();
    }

    public void setChart(ChartDocument chart) {
        setBackgroundIllustration(null);
        setPreviewLineTextures(null);
        this.chart = chart;
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
        Bitmap previous = backgroundIllustration;
        backgroundIllustration = illustration;
        if (previous != null && previous != illustration && !previous.isRecycled()) previous.recycle();
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

    public boolean isPlaying() {
        return playing;
    }

    public BpmApplyResult addBpmChange(BpmChange change) {
        if (chart == null || change == null) return BpmApplyResult.TARGET_NOT_FOUND;
        BpmListValidator.Error validation = BpmListValidator.validate(chart.bpmChanges, change, null);
        BpmApplyResult result = mapBpmValidation(validation);
        if (result != BpmApplyResult.APPLIED) return result;
        history.execute(BpmEditCommand.add(chart, change));
        invalidate();
        return BpmApplyResult.APPLIED;
    }

    public BpmApplyResult applyBpmProperties(BpmChange target, BpmChange edited) {
        if (chart == null || target == null || edited == null || !chart.bpmChanges.contains(target)) {
            return BpmApplyResult.TARGET_NOT_FOUND;
        }
        BpmListValidator.Error validation = BpmListValidator.validate(chart.bpmChanges, edited, target);
        BpmApplyResult result = mapBpmValidation(validation);
        if (result != BpmApplyResult.APPLIED) return result;
        history.execute(BpmEditCommand.edit(chart, target, target.copy(), edited.copy()));
        invalidate();
        return BpmApplyResult.APPLIED;
    }

    public BpmApplyResult deleteBpmChange(BpmChange target) {
        if (chart == null || target == null || !chart.bpmChanges.contains(target)) {
            return BpmApplyResult.TARGET_NOT_FOUND;
        }
        if (chart.bpmChanges.size() <= 1) return BpmApplyResult.LAST_ENTRY_REQUIRED;
        if (target == chart.bpmChanges.get(0)) return BpmApplyResult.FIRST_ENTRY_LOCKED;
        history.execute(BpmEditCommand.delete(chart, target));
        invalidate();
        return BpmApplyResult.APPLIED;
    }

    private static BpmApplyResult mapBpmValidation(BpmListValidator.Error error) {
        switch (error) {
            case NONE: return BpmApplyResult.APPLIED;
            case DUPLICATE_START_TIME: return BpmApplyResult.DUPLICATE_START_TIME;
            case FIRST_ENTRY_LOCKED: return BpmApplyResult.FIRST_ENTRY_LOCKED;
            default: return BpmApplyResult.INVALID_VALUE;
        }
    }

    public PropertyApplyResult applyNoteProperties(Note target, Note edited) {
        if (chart == null || target == null || edited == null) return PropertyApplyResult.TARGET_NOT_FOUND;
        JudgeLine line = currentLine();
        if (!line.notes.contains(target)) return PropertyApplyResult.TARGET_NOT_FOUND;
        if (PropertyValidator.validate(edited) != PropertyValidator.Error.NONE) {
            return PropertyApplyResult.INVALID_VALUES;
        }

        history.execute(PropertyEditCommand.note(line, target, target.copy(), edited.copy()));
        selectedNote = target;
        selectedEvent = null;
        invalidate();
        return PropertyApplyResult.APPLIED;
    }

    public PropertyApplyResult applyEventProperties(LineEvent target, LineEvent edited) {
        if (chart == null || target == null || edited == null) return PropertyApplyResult.TARGET_NOT_FOUND;
        if (!isEditableEventLayer(eventLayerIndex)) return PropertyApplyResult.INVALID_VALUES;
        EventLayer layer = currentLayer();
        if (target.type != edited.type || !layer.events(target.type).contains(target)) {
            return PropertyApplyResult.TARGET_NOT_FOUND;
        }
        if (PropertyValidator.validate(edited) != PropertyValidator.Error.NONE) {
            return PropertyApplyResult.INVALID_VALUES;
        }

        boolean timeChanged = !target.startTime.equals(edited.startTime)
                || !target.endTime.equals(edited.endTime);
        EditHistory.Command command;
        if (xyBindingEnabled && XYBindingValidator.isMoveType(target.type) && timeChanged) {
            XYBindingValidator.PairLookup lookup = XYBindingValidator.findPair(layer, target);
            if (lookup.error != XYBindingValidator.Error.NONE || lookup.event == null) {
                return PropertyApplyResult.XY_BINDING_INVALID;
            }
            XYBindingValidator.Error validation = XYBindingValidator.validatePairedTimes(
                    layer, eventLayerIndex, target, lookup.event,
                    edited.startTime, edited.endTime);
            if (validation == XYBindingValidator.Error.EVENT_OVERLAP) {
                return PropertyApplyResult.EVENT_OVERLAP;
            }
            if (validation != XYBindingValidator.Error.NONE) {
                return PropertyApplyResult.XY_BINDING_INVALID;
            }
            command = XYBindingCommand.editWithPairedTimes(
                    layer, target, target.copy(), edited.copy(), lookup.event,
                    lookup.event.startTime, lookup.event.endTime);
        } else {
            if (layer.overlaps(edited, target)) return PropertyApplyResult.EVENT_OVERLAP;
            command = PropertyEditCommand.event(layer, target, target.copy(), edited.copy());
        }
        history.execute(withAutoStick(layer, target, edited, command));
        selectedEvent = target;
        selectedNote = null;
        invalidate();
        return PropertyApplyResult.APPLIED;
    }

    public int getLineIndex() {
        return lineIndex;
    }

    public int getEventLayerIndex() {
        return eventLayerIndex;
    }

    public int getSubdivision() {
        return subdivision;
    }

    public BeatTime getCurrentBeatTime() {
        return BeatTime.fromDouble(Math.max(0.0, snapBeat(currentBeat)), subdivision);
    }

    public List<Note> getCurrentLineNotes() {
        if (chart == null) return new ArrayList<>();
        return new ArrayList<>(currentLine().notes);
    }

    public JudgeLine getCurrentJudgeLine() {
        return chart == null ? null : currentLine();
    }

    public StoryboardApplyResult addStoryboardEvent(
            StoryboardEventType type, ExtendedLineEvents.TimedEvent event) {
        JudgeLine line = getCurrentJudgeLine();
        if (line == null || type == null || event == null) {
            return StoryboardApplyResult.TARGET_NOT_FOUND;
        }
        if (line.extended == null) line.extended = new ExtendedLineEvents();
        StoryboardEventValidator.Error validation = StoryboardEventValidator.validate(
                line.extended, type, event, null);
        StoryboardApplyResult result = mapStoryboardValidation(validation);
        if (result != StoryboardApplyResult.APPLIED) return result;
        history.execute(StoryboardEditCommand.add(line.extended, type, event));
        invalidate();
        return StoryboardApplyResult.APPLIED;
    }

    public StoryboardApplyResult applyStoryboardEvent(
            StoryboardEventType type, ExtendedLineEvents.TimedEvent target,
            ExtendedLineEvents.TimedEvent edited) {
        JudgeLine line = getCurrentJudgeLine();
        if (line == null || line.extended == null || type == null
                || target == null || edited == null
                || !line.extended.contains(type, target)) {
            return StoryboardApplyResult.TARGET_NOT_FOUND;
        }
        StoryboardEventValidator.Error validation = StoryboardEventValidator.validate(
                line.extended, type, edited, target);
        StoryboardApplyResult result = mapStoryboardValidation(validation);
        if (result != StoryboardApplyResult.APPLIED) return result;
        history.execute(StoryboardEditCommand.edit(line.extended, type, target,
                target.copy(), edited.copy()));
        invalidate();
        return StoryboardApplyResult.APPLIED;
    }

    public StoryboardApplyResult deleteStoryboardEvent(
            StoryboardEventType type, ExtendedLineEvents.TimedEvent target) {
        JudgeLine line = getCurrentJudgeLine();
        if (line == null || line.extended == null || type == null || target == null
                || !line.extended.contains(type, target)) {
            return StoryboardApplyResult.TARGET_NOT_FOUND;
        }
        history.execute(StoryboardEditCommand.delete(line.extended, type, target));
        invalidate();
        return StoryboardApplyResult.APPLIED;
    }

    public StoryboardApplyResult glueStoryboardEvent(
            StoryboardEventType type, ExtendedLineEvents.TimedEvent target) {
        JudgeLine line = getCurrentJudgeLine();
        if (line == null || line.extended == null || type == null || target == null) {
            return StoryboardApplyResult.TARGET_NOT_FOUND;
        }
        List<? extends ExtendedLineEvents.TimedEvent> events = line.extended.events(type);
        int index = events.indexOf(target);
        if (index < 0) return StoryboardApplyResult.TARGET_NOT_FOUND;
        if (index == 0) return StoryboardApplyResult.NO_PREVIOUS;
        ExtendedLineEvents.TimedEvent previous = events.get(index - 1);
        ExtendedLineEvents.TimedEvent edited = target.copy();
        boolean changed = copyStoryboardEndToStart(previous, edited);
        if (!changed) return StoryboardApplyResult.NO_CHANGE;
        return applyStoryboardEvent(type, target, edited);
    }

    public StoryboardApplyResult splitStoryboardEvent(
            StoryboardEventType type, ExtendedLineEvents.TimedEvent target,
            BeatTime splitTime) {
        JudgeLine line = getCurrentJudgeLine();
        if (line == null || line.extended == null || type == null || target == null
                || splitTime == null || !line.extended.contains(type, target)) {
            return StoryboardApplyResult.TARGET_NOT_FOUND;
        }
        if (splitTime.compareTo(target.startTime) <= 0
                || splitTime.compareTo(target.endTime) >= 0) {
            return StoryboardApplyResult.SPLIT_OUTSIDE;
        }
        double splitBeat = splitTime.toDouble();
        ExtendedLineEvents.TimedEvent left = target.copy();
        ExtendedLineEvents.TimedEvent right = target.copy();
        left.endTime = splitTime;
        right.startTime = splitTime;
        setStoryboardSplitValue(target, left, right, splitBeat);
        StoryboardApplyResult leftResult = mapStoryboardValidation(
                StoryboardEventValidator.validate(line.extended, type, left, target));
        StoryboardApplyResult rightResult = mapStoryboardValidation(
                StoryboardEventValidator.validate(line.extended, type, right, target));
        if (leftResult != StoryboardApplyResult.APPLIED) return leftResult;
        if (rightResult != StoryboardApplyResult.APPLIED) return rightResult;
        EditHistory.Command edit = StoryboardEditCommand.edit(
                line.extended, type, target, target.copy(), left);
        EditHistory.Command add = StoryboardEditCommand.add(line.extended, type, right);
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                edit.apply();
                add.apply();
            }

            @Override
            public void revert() {
                add.revert();
                edit.revert();
            }
        });
        invalidate();
        return StoryboardApplyResult.APPLIED;
    }

    private static StoryboardApplyResult mapStoryboardValidation(
            StoryboardEventValidator.Error error) {
        if (error == StoryboardEventValidator.Error.NONE) {
            return StoryboardApplyResult.APPLIED;
        }
        if (error == StoryboardEventValidator.Error.EVENT_OVERLAP) {
            return StoryboardApplyResult.EVENT_OVERLAP;
        }
        return StoryboardApplyResult.INVALID_VALUES;
    }

    private static boolean copyStoryboardEndToStart(
            ExtendedLineEvents.TimedEvent source,
            ExtendedLineEvents.TimedEvent target) {
        if (source instanceof ExtendedLineEvents.NumericEvent
                && target instanceof ExtendedLineEvents.NumericEvent) {
            ExtendedLineEvents.NumericEvent from = (ExtendedLineEvents.NumericEvent) source;
            ExtendedLineEvents.NumericEvent to = (ExtendedLineEvents.NumericEvent) target;
            boolean changed = Double.compare(to.start, from.end) != 0;
            to.start = from.end;
            return changed;
        }
        if (source instanceof ExtendedLineEvents.ColorEvent
                && target instanceof ExtendedLineEvents.ColorEvent) {
            ExtendedLineEvents.ColorEvent from = (ExtendedLineEvents.ColorEvent) source;
            ExtendedLineEvents.ColorEvent to = (ExtendedLineEvents.ColorEvent) target;
            boolean changed = to.startRgb != from.endRgb;
            to.startRgb = from.endRgb;
            return changed;
        }
        if (source instanceof ExtendedLineEvents.TextEvent
                && target instanceof ExtendedLineEvents.TextEvent) {
            ExtendedLineEvents.TextEvent from = (ExtendedLineEvents.TextEvent) source;
            ExtendedLineEvents.TextEvent to = (ExtendedLineEvents.TextEvent) target;
            boolean changed = !to.start.equals(from.end);
            to.start = from.end;
            return changed;
        }
        return false;
    }

    private static void setStoryboardSplitValue(
            ExtendedLineEvents.TimedEvent source,
            ExtendedLineEvents.TimedEvent left,
            ExtendedLineEvents.TimedEvent right, double splitBeat) {
        if (source instanceof ExtendedLineEvents.NumericEvent) {
            double value = ((ExtendedLineEvents.NumericEvent) source).valueAt(splitBeat);
            ((ExtendedLineEvents.NumericEvent) left).end = value;
            ((ExtendedLineEvents.NumericEvent) right).start = value;
        } else if (source instanceof ExtendedLineEvents.ColorEvent) {
            int value = ((ExtendedLineEvents.ColorEvent) source).valueAt(splitBeat);
            ((ExtendedLineEvents.ColorEvent) left).endRgb = value;
            ((ExtendedLineEvents.ColorEvent) right).startRgb = value;
        } else if (source instanceof ExtendedLineEvents.TextEvent) {
            String value = ((ExtendedLineEvents.TextEvent) source).valueAt(splitBeat);
            ((ExtendedLineEvents.TextEvent) left).end = value;
            ((ExtendedLineEvents.TextEvent) right).start = value;
        }
    }

    public List<Note> getSelectedNotesForCurve() {
        if (chart == null) return new ArrayList<>();
        List<Note> result = selectedNotesForClipboard();
        result.sort((first, second) -> first.startTime.compareTo(second.startTime));
        return result;
    }

    public List<Note> getSelectedNotesForBatch() {
        if (chart == null) return new ArrayList<>();
        List<Note> result = selectedNotesForClipboard();
        result.sort((first, second) -> first.startTime.compareTo(second.startTime));
        return result;
    }

    public List<LineEvent> getSelectedEventsForBatch() {
        if (chart == null) return new ArrayList<>();
        List<LineEvent> result = selectedEventsForClipboard();
        result.sort((first, second) -> first.startTime.compareTo(second.startTime));
        return result;
    }

    public BatchEditOperation.Result applyNoteBatch(BatchEditOperation.NoteField field,
                                                    BatchValueTransform.Spec profile,
                                                    BatchValueTransform.Mode mode) {
        BatchEditOperation.Result result = BatchEditOperation.notes(
                currentLine(), getSelectedNotesForBatch(), field, profile, mode);
        if (result.error != BatchEditOperation.Error.NONE) return result;
        history.execute(result.command);
        clearSelection();
        selectedNotes.addAll(result.notes);
        selectedNote = result.notes.isEmpty() ? null : result.notes.get(0);
        activateEditPanel();
        invalidate();
        return result;
    }

    public BatchEditOperation.Result applyEventBatch(BatchEditOperation.EventField field,
                                                     BatchValueTransform.Spec profile,
                                                     BatchValueTransform.Mode mode,
                                                     EventType eventType) {
        List<LineEvent> filtered = new ArrayList<>();
        for (LineEvent event : getSelectedEventsForBatch()) {
            if (event.type == eventType) filtered.add(event);
        }
        BatchEditOperation.Result result = BatchEditOperation.events(
                currentLayer(), filtered, field, profile, mode);
        if (result.error != BatchEditOperation.Error.NONE) return result;
        history.execute(result.command);
        clearSelection();
        selectedEvents.addAll(result.events);
        selectedEvent = result.events.isEmpty() ? null : result.events.get(0);
        activateEditPanel();
        invalidate();
        return result;
    }

    public BatchEditOperation.Result stickSelectedEvents(EventType eventType) {
        List<LineEvent> filtered = new ArrayList<>();
        for (LineEvent event : getSelectedEventsForBatch()) {
            if (event.type == eventType) filtered.add(event);
        }
        BatchEditOperation.Result result = BatchEditOperation.stick(currentLayer(), filtered);
        if (result.error != BatchEditOperation.Error.NONE) return result;
        history.execute(result.command);
        clearSelection();
        selectedEvents.addAll(result.events);
        selectedEvent = result.events.isEmpty() ? null : result.events.get(0);
        activateEditPanel();
        invalidate();
        return result;
    }

    public EventCloneOperation.Result applyEventClone(EventCloneOperation.Spec spec) {
        EventCloneOperation.Result result = EventCloneOperation.prepare(
                chart, currentLine(), eventLayerIndex, getSelectedEventsForBatch(), spec);
        if (result.error != EventCloneOperation.Error.NONE) return result;
        int sourceLineIndex = lineIndex;
        int targetLineIndex = spec.lineSequence[0];
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                result.command.apply();
                lineIndex = targetLineIndex;
                clearSelection();
                EventLayer visibleLayer = currentLayer();
                for (LineEvent event : result.events) {
                    if (visibleLayer.events(event.type).contains(event)) selectedEvents.add(event);
                }
                selectedEvent = selectedEvents.isEmpty()
                        ? null : selectedEvents.iterator().next();
                activateEditPanel();
            }

            @Override
            public void revert() {
                result.command.revert();
                lineIndex = sourceLineIndex;
                clearSelection();
            }
        });
        invalidate();
        return result;
    }

    public void performUndo() {
        undo();
    }

    public void performRedo() {
        redo();
    }

    public void performCopy() {
        copySelection();
    }

    public void performCut() {
        cutSelection();
    }

    public void performPaste(boolean mirrorNotes) {
        pasteClipboard(mirrorNotes);
    }

    public void performDeleteSelection() {
        deleteSelection();
    }

    public void performTogglePlay() {
        togglePlay();
    }

    public ComplexMoveGenerator.Result applyComplexMove(ComplexMoveGenerator.Spec spec) {
        if (chart == null) {
            return ComplexMoveGenerator.Result.error(
                    ComplexMoveGenerator.Error.RESERVED_LAYER, "");
        }
        EventLayer layer = currentLayer();
        ComplexMoveGenerator.Result result = ComplexMoveGenerator.generate(
                layer, eventLayerIndex, spec);
        if (result.error != ComplexMoveGenerator.Error.NONE) return result;
        EditHistory.Command command = ComplexMoveCommand.add(
                layer, result.moveXEvents, result.moveYEvents);
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                command.apply();
                clearSelection();
                selectedEvents.addAll(result.moveXEvents);
                selectedEvents.addAll(result.moveYEvents);
                selectedEvent = result.moveXEvents.isEmpty()
                        ? null : result.moveXEvents.get(0);
                activateEditPanel();
            }

            @Override
            public void revert() {
                command.revert();
                clearSelection();
            }
        });
        invalidate();
        return result;
    }

    public CurveNoteGenerator.Result applyCurveNotes(Note startNote, Note endNote,
                                                      double curveDensity,
                                                      NoteType noteType,
                                                      int easingType) {
        if (chart == null) {
            return CurveNoteGenerator.Result.error(
                    CurveNoteGenerator.Error.TARGET_NOT_FOUND);
        }
        JudgeLine line = currentLine();
        CurveNoteGenerator.Result result = CurveNoteGenerator.generate(
                line, startNote, endNote, curveDensity, subdivision, noteType, easingType);
        if (result.error != CurveNoteGenerator.Error.NONE) return result;
        EditHistory.Command command = CurveNoteCommand.add(line, result.notes);
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                command.apply();
                clearSelection();
                selectedNotes.addAll(result.notes);
                selectedNote = result.notes.isEmpty() ? null : result.notes.get(0);
                activateEditPanel();
            }

            @Override
            public void revert() {
                command.revert();
                clearSelection();
            }
        });
        invalidate();
        return result;
    }

    public LineApplyResult selectLine(int index) {
        if (chart == null || index < 0 || index >= chart.judgeLines.size()) {
            return LineApplyResult.TARGET_NOT_FOUND;
        }
        cancelActiveGesture();
        lineIndex = index;
        eventLayerIndex = Math.min(eventLayerIndex, currentLine().eventLayers.size() - 1);
        clearSelection();
        invalidate();
        return LineApplyResult.APPLIED;
    }

    public boolean navigateToDiagnostic(ChartDiagnostic diagnostic) {
        if (chart == null || diagnostic == null) return false;
        cancelActiveGesture();
        clearSelection();
        if (diagnostic.lineIndex >= 0) {
            if (diagnostic.lineIndex >= chart.judgeLines.size()) return false;
            lineIndex = diagnostic.lineIndex;
        }
        JudgeLine line = currentLine();
        if (diagnostic.layerIndex >= 0 && diagnostic.layerIndex < 4) {
            eventLayerIndex = diagnostic.layerIndex;
        }
        currentBeat = Math.max(0.0, diagnostic.beat.toDouble());
        if (diagnostic.note != null && line.notes.contains(diagnostic.note)) {
            selectedNote = diagnostic.note;
            selectedNotes.add(diagnostic.note);
        } else if (diagnostic.event != null
                && diagnostic.layerIndex >= 0
                && diagnostic.layerIndex < line.eventLayers.size()) {
            EventLayer layer = line.eventLayers.get(diagnostic.layerIndex);
            if (layer.events(diagnostic.event.type).contains(diagnostic.event)) {
                selectedEvent = diagnostic.event;
                selectedEvents.add(diagnostic.event);
            }
        }
        invalidate();
        return true;
    }

    public JudgeLine addJudgeLine() {
        if (chart == null) return null;
        JudgeLine line = new JudgeLine();
        line.name = "Line " + chart.judgeLines.size();
        int index = chart.judgeLines.size();
        EditHistory.Command command = JudgeLineCommand.add(chart, line, index);
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                command.apply();
                lineIndex = chart.judgeLines.indexOf(line);
                eventLayerIndex = 0;
                clearSelection();
            }

            @Override
            public void revert() {
                command.revert();
                lineIndex = Math.max(0, Math.min(index - 1, chart.judgeLines.size() - 1));
                clearSelection();
            }
        });
        invalidate();
        return line;
    }

    public LineApplyResult deleteActiveLine() {
        JudgeLineValidator.Error validation = JudgeLineValidator.validateDelete(chart, lineIndex);
        if (validation != JudgeLineValidator.Error.NONE) return mapLineValidation(validation);
        JudgeLine target = currentLine();
        int index = lineIndex;
        EditHistory.Command command = JudgeLineCommand.delete(chart, index);
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                command.apply();
                lineIndex = Math.max(0, Math.min(index - 1, chart.judgeLines.size() - 1));
                eventLayerIndex = Math.min(eventLayerIndex, currentLine().eventLayers.size() - 1);
                clearSelection();
            }

            @Override
            public void revert() {
                command.revert();
                lineIndex = chart.judgeLines.indexOf(target);
                clearSelection();
            }
        });
        invalidate();
        return LineApplyResult.APPLIED;
    }

    public LineApplyResult applyJudgeLineProperties(JudgeLine target, JudgeLine edited) {
        if (chart == null || target == null || edited == null || !chart.judgeLines.contains(target)) {
            return LineApplyResult.TARGET_NOT_FOUND;
        }
        JudgeLineValidator.Error validation = JudgeLineValidator.validateProperties(edited);
        if (validation != JudgeLineValidator.Error.NONE) return mapLineValidation(validation);
        history.execute(JudgeLineCommand.edit(target, target.copyProperties(), edited.copyProperties()));
        invalidate();
        return LineApplyResult.APPLIED;
    }

    private static LineApplyResult mapLineValidation(JudgeLineValidator.Error error) {
        switch (error) {
            case NONE: return LineApplyResult.APPLIED;
            case LINE_ZERO_PROTECTED: return LineApplyResult.LINE_ZERO_PROTECTED;
            case LAST_LINE_REQUIRED: return LineApplyResult.LAST_LINE_REQUIRED;
            case TARGET_NOT_FOUND: return LineApplyResult.TARGET_NOT_FOUND;
            default: return LineApplyResult.INVALID_PROPERTIES;
        }
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
            paint.setColor(Color.WHITE);
            paint.setTextSize(18f * density);
            canvas.drawText("No chart loaded", 24f * density, 48f * density, paint);
            return;
        }

        if (playing) advancePlayback();
        if (previewMode) drawPreview(canvas);
        else drawEditor(canvas);
    }

    private void drawPreview(Canvas canvas) {
        RenderScene scene = ChartEvaluator.evaluate(
                chart, currentBeat, settings.highlightSimultaneousNotes,
                callback == null ? -1L : callback.audioDurationMs(),
                useRpe170Speed);
        float barHeight = 25f * density;
        PreviewViewportCalculator.Result fitted = PreviewViewportCalculator.fit(
                0.0, toolbarHeight(), getWidth(), getHeight() - barHeight,
                settings.playerWidth, settings.playerHeight);
        RectF viewport = new RectF((float) fitted.left, (float) fitted.top,
                (float) fitted.right, (float) fitted.bottom);
        previewRenderer.draw(canvas, scene, viewport, backgroundIllustration, settings,
                lineIndex);
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
        drawEventGrid(canvas, split, toolbar, getWidth(), bottom);
        drawNotes(canvas, 0f, toolbar, split, bottom);
        drawEvents(canvas, split, toolbar, getWidth(), bottom);
        drawPlacementPreview(canvas, split, toolbar, bottom);
        drawSelectionRectangle(canvas);
        drawToolbar(canvas);
        drawBottomBar(canvas);
        drawStatus(canvas, bottom - 7f * density);
    }

    private void drawNoteBackground(Canvas canvas, float left, float top, float right, float bottom) {
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

    private void drawEditorGameplayBackground(Canvas canvas, float left, float top,
                                              float right, float bottom) {
        RenderScene scene = ChartEvaluator.evaluate(
                chart, currentBeat, settings.highlightSimultaneousNotes,
                callback == null ? -1L : callback.audioDurationMs(),
                useRpe170Speed);
        PreviewViewportCalculator.Result fitted = PreviewViewportCalculator.fit(
                left, top, right, bottom, settings.playerWidth, settings.playerHeight);
        RectF viewport = new RectF((float) fitted.left, (float) fitted.top,
                (float) fitted.right, (float) fitted.bottom);
        int save = canvas.saveLayerAlpha(viewport, EDITOR_GAMEPLAY_ALPHA);
        previewRenderer.drawGameplayOverlay(canvas, scene, viewport, settings, lineIndex);
        canvas.restoreToCount(save);
    }

    private void drawNoteGrid(Canvas canvas, float left, float top, float right, float bottom) {
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
                canvas.drawText(Integer.toString((int) Math.floor(beat)), 5f * density, y - 3f * density, paint);
            }
        }

        for (int index = 0; index < verticalGridLines; index++) {
            float x = VerticalGrid.screenX(index, verticalGridLines, left, right);
            paint.setColor(index == verticalGridLines / 2 ? Color.rgb(83, 101, 62) : GRID_MINOR);
            canvas.drawLine(x, top, x, bottom, paint);
        }

        paint.setStrokeWidth((float) Math.max(1.0, settings.lineDefaultWidth) * density);
        paint.setColor(Color.rgb((settings.lineColorRgb >> 16) & 0xff,
                (settings.lineColorRgb >> 8) & 0xff, settings.lineColorRgb & 0xff));
        canvas.drawLine(left, centerY, right, centerY, paint);
    }

    private void drawEventGrid(Canvas canvas, float left, float top, float right, float bottom) {
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
            paint.setColor(types[index] == selectedEventType && tool == Tool.EVENT ? ACCENT : Color.LTGRAY);
            canvas.drawText(types[index].label, x + width / 2f, top + 17f * density, paint);
        }
        paint.setColor(Color.rgb(58, 67, 76));
        canvas.drawLine(right, top, right, bottom, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawNotes(Canvas canvas, float left, float top, float right, float bottom) {
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

    private RectF holdBounds(float x, float startY, float endY, float width) {
        return new RectF(x - width / 2f, Math.min(startY, endY),
                x + width / 2f, Math.max(startY, endY));
    }

    private void drawBitmap(Canvas canvas, Bitmap bitmap, RectF destination, int alpha) {
        noteTexturePaint.setAlpha(alpha);
        canvas.drawBitmap(bitmap, null, destination, noteTexturePaint);
    }

    private void drawHoldTexture(Canvas canvas, float x, float startY, float endY,
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
                new Rect(0, bitmap.getHeight() - headPixels, bitmap.getWidth(), bitmap.getHeight()),
                new RectF(left, startY - headHeight, right, startY), noteTexturePaint);
    }

    private void drawEvents(Canvas canvas, float left, float top, float right, float bottom) {
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
                paint.setColor(invalidDrag ? INVALID_PREVIEW : eventColor(type, event.linkGroup != 0));
                canvas.drawRoundRect(rect, 4f * density, 4f * density, paint);
                if (settings.drawEventCurves) drawEventEasingCurve(canvas, event, rect, invalidDrag);
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

    private void drawEventEasingCurve(Canvas canvas, LineEvent event, RectF rect,
                                      boolean invalid) {
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

    private void drawEventHandles(Canvas canvas, float centerX, float startY, float endY,
                                  boolean invalidDrag) {
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

    private void drawHoldHandles(Canvas canvas, float centerX, float startY, float endY,
                                 boolean invalidDrag) {
        drawEventHandles(canvas, centerX, startY, endY, invalidDrag);
    }

    private BeatTime visibleNoteStart(Note note) {
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

    private BeatTime visibleNoteEnd(Note note) {
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

    private double visibleNoteX(Note note) {
        int index = draggedNotes.indexOf(note);
        if (index >= 0 && index < noteDragBefore.size()) {
            return noteDragBefore.get(index).positionX + noteDragDeltaX;
        }
        return note.positionX;
    }

    private void drawSelectionRectangle(Canvas canvas) {
        if (gestureMode != GestureMode.RECT_SELECT || selectionRectangle == null) return;
        RectF bounds = normalized(selectionRectangle);
        paint.setColor(withAlpha(ACCENT, 48));
        canvas.drawRect(bounds, paint);
        strokePaint.setColor(ACCENT);
        strokePaint.setStrokeWidth(2f * density);
        canvas.drawRect(bounds, strokePaint);
    }

    private void drawPlacementPreview(Canvas canvas, float split, float top, float bottom) {
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
                    Math.min(beatToY(previewStartBeat, centerY), beatToY(previewEndBeat, centerY)),
                    split + (index + 1) * width - 3f * density,
                    Math.max(beatToY(previewStartBeat, centerY), beatToY(previewEndBeat, centerY)));
            paint.setColor(withAlpha(eventColor(previewEventType, false), 145));
            canvas.drawRoundRect(rect, 4f * density, 4f * density, paint);
        }
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
        x = addButton(canvas, getResources().getString(R.string.toolbar_menu), x, y,
                58f * density, buttonHeight, () -> callback.requestMainMenu());

        float seekRight = getWidth() - 10f * density;
        float preferredSeekWidth = clamp(getWidth() * 0.29f,
                190f * density, 360f * density);
        float seekLeft = Math.max(x + 92f * density, seekRight - preferredSeekWidth);
        if (seekLeft > seekRight - 80f * density) seekLeft = seekRight - 80f * density;

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

    private void drawPlaybackSeekBar(Canvas canvas, float left, float right) {
        playbackSeekBounds.set(left, 3f * density, right, toolbarHeight() - 3f * density);
        boolean ready = callback != null && callback.isAudioReady() && callback.audioDurationMs() > 0L;
        long duration = ready ? callback.audioDurationMs() : 0L;
        long position = 0L;
        if (ready) {
            position = playbackScrubbing || gestureMode == GestureMode.SEEK_BAR
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
        paint.setColor(TOOLBAR_COLOR);
        canvas.drawRect(0, top, getWidth(), getHeight(), paint);

        float tabY = top + 5f * density;
        float x = 6f * density;
        x = drawBottomTab(canvas, R.string.controls_panel_create, BottomPanel.CREATE, x, tabY);
        x = drawBottomTab(canvas, R.string.controls_panel_edit, BottomPanel.EDIT, x, tabY);
        x = drawBottomTab(canvas, R.string.controls_panel_arrange, BottomPanel.ARRANGE, x, tabY);

        List<BottomAction> actions = bottomActions();
        int pageSize = bottomPanel == BottomPanel.CREATE ? 6
                : bottomPanel == BottomPanel.EDIT ? 7 : 5;
        int pageCount = Math.max(1, (actions.size() + pageSize - 1) / pageSize);
        bottomActionPage = Math.max(0, Math.min(pageCount - 1, bottomActionPage));

        paint.setColor(Color.LTGRAY);
        paint.setTextSize(10f * density);
        float pageReserve = pageCount > 1 ? 52f * density : 0f;
        float summaryRight = getWidth() - 10f * density - pageReserve;
        String summary = getResources().getString(R.string.controls_status_summary,
                currentToolLabel(), lineIndex, eventLayerIndex, subdivision, verticalGridLines);
        if (summaryRight > x + 8f * density) {
            canvas.drawText(ellipsize(summary, summaryRight - x - 8f * density),
                    x + 8f * density, top + 22f * density, paint);
        }
        if (pageCount > 1) {
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(getResources().getString(R.string.controls_page_indicator,
                            bottomActionPage + 1, pageCount),
                    getWidth() - 10f * density, top + 22f * density, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        float actionY = top + 36f * density;
        int first = bottomActionPage * pageSize;
        int last = Math.min(actions.size(), first + pageSize);
        x = 6f * density;
        for (int index = first; index < last; index++) {
            BottomAction action = actions.get(index);
            x = addButton(canvas, action.label, x, actionY, action.width,
                    31f * density, action.action, action.active, action.enabled);
        }
        if (pageCount > 1) {
            float previousX = getWidth() - 86f * density;
            addButton(canvas, getResources().getString(R.string.controls_page_previous),
                    previousX, actionY, 36f * density, 31f * density,
                    () -> changeBottomPage(-1), false, bottomActionPage > 0);
            addButton(canvas, getResources().getString(R.string.controls_page_next),
                    getWidth() - 46f * density, actionY, 36f * density, 31f * density,
                    () -> changeBottomPage(1), false, bottomActionPage + 1 < pageCount);
        }

        paint.setColor(Color.rgb(180, 190, 200));
        paint.setTextSize(9.5f * density);
        String context = bottomContextSummary();
        String hint = settings.showTips ? bottomPanelHint() : "";
        String footer = hint.isEmpty() ? context : context + " · " + hint;
        canvas.drawText(ellipsize(footer, getWidth() - 16f * density),
                8f * density, top + 86f * density, paint);
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
                        () -> changeVerticalGrid(-1)));
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

    private void activateEditPanel() {
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
    public boolean onTouchEvent(MotionEvent event) {
        boolean boxSelection = rectangleSelectionMode == RectangleSelectionMode.ADD;
        if (!boxSelection) scaleDetector.onTouchEvent(event);
        if (event.getPointerCount() > 1) {
            cancelActiveGesture();
            return true;
        }
        if (!boxSelection && scaleDetector.isInProgress()) return true;
        if (chart == null) return true;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                requestFocus();
                return handleDown(event.getX(), event.getY());
            case MotionEvent.ACTION_MOVE:
                return handleMove(event.getX(), event.getY());
            case MotionEvent.ACTION_UP: {
                boolean click = !movedDuringGesture;
                boolean handled = handleUp(event.getX(), event.getY());
                if (click) performClick();
                return handled;
            }
            case MotionEvent.ACTION_CANCEL:
                cancelActiveGesture();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private boolean handleDown(float x, float y) {
        movedDuringGesture = false;
        if (playbackSeekBounds.contains(x, y)) {
            return beginPlaybackSeek(x, y);
        }
        for (int index = buttons.size() - 1; index >= 0; index--) {
            ButtonSpec button = buttons.get(index);
            if (button.bounds.contains(x, y)) {
                button.action.run();
                invalidate();
                return true;
            }
        }

        if (previewMode) {
            if (y > toolbarHeight()) {
                gestureStartX = x;
                gestureStartY = y;
                gestureLastY = y;
                beginPanGesture();
            }
            return true;
        }

        if (!isInEditor(y)) return true;
        gestureStartX = x;
        gestureStartY = y;
        gestureLastY = y;
        float split = editorSplitX();
        float bottom = getHeight() - bottomBarHeight();

        if (tool == Tool.SELECT && rectangleSelectionMode == RectangleSelectionMode.ADD) {
            gestureMode = GestureMode.RECT_SELECT;
            selectionRectangle = new RectF(x, y, x, y);
            return true;
        }

        if (tool == Tool.HOLD && x < split) {
            gestureMode = GestureMode.HOLD_PREVIEW;
            previewStartBeat = snapBeat(yToBeat(y, timelineY(bottom)));
            previewEndBeat = previewStartBeat + 1.0 / subdivision;
            return true;
        }

        if (tool == Tool.EVENT && x >= split) {
            gestureMode = GestureMode.EVENT_PREVIEW;
            previewEventType = eventTypeAtX(x, split);
            selectedEventType = previewEventType;
            previewStartBeat = snapBeat(yToBeat(y, timelineY(bottom)));
            previewEndBeat = previewStartBeat + 1.0 / subdivision;
            return true;
        }

        if (tool == Tool.TAP || tool == Tool.DRAG || tool == Tool.FLICK) {
            if (x < split) addSimpleNoteAt(x, y, split, bottom);
            return true;
        }

        if (tool == Tool.SELECT) {
            if (x < split) {
                Note note = findNearestNote(x, y, split, bottom);
                if (note != null) {
                    boolean alreadySelected = selectedNotes.contains(note)
                            || selectedNote == note;
                    HoldHandleHit holdHandle = alreadySelected
                            && selectionCount() == 1 && note.type == NoteType.HOLD
                            ? findHoldHandleAt(note, x, y, split, bottom) : null;
                    if (!alreadySelected) clearSelection();
                    selectedNote = note;
                    selectedNotes.add(note);
                    selectedEvent = null;
                    activateEditPanel();
                    if (holdHandle != null) beginHoldResize(note, holdHandle.startHandle);
                    else if (noteMoveMode.enabled()) beginNoteDrag(note, x, y, split, bottom);
                    else gestureMode = GestureMode.NONE;
                } else {
                    beginPanGesture();
                }
            } else {
                EventHandleHit handle = findEventHandleAt(x, y, split, bottom);
                if (handle != null) {
                    clearSelection();
                    selectedEvent = handle.event;
                    selectedEvents.add(handle.event);
                    selectedNote = null;
                    selectedEventType = handle.event.type;
                    activateEditPanel();
                    beginEventDrag(handle.event, handle.startHandle);
                } else {
                    LineEvent event = findEventAt(x, y, split, bottom);
                    if (event != null) {
                        clearSelection();
                        selectedEvent = event;
                        selectedEvents.add(event);
                        selectedNote = null;
                        selectedEventType = event.type;
                        gestureMode = GestureMode.NONE;
                        activateEditPanel();
                    } else {
                        beginPanGesture();
                    }
                }
            }
            invalidate();
        }
        return true;
    }

    private void cancelActiveGesture() {
        finishPlaybackScrub();
        gestureMode = GestureMode.NONE;
        previewEventType = null;
        clearEventDragState();
        clearNoteTouchState();
        selectionRectangle = null;
        movedDuringGesture = true;
        invalidate();
    }

    private boolean handleMove(float x, float y) {
        float bottom = getHeight() - bottomBarHeight();
        if (Math.abs(y - gestureStartY) > 3f * density || Math.abs(x - gestureStartX) > 3f * density) movedDuringGesture = true;
        if (gestureMode == GestureMode.SEEK_BAR) {
            updatePlaybackSeek(x);
            invalidate();
        } else if (gestureMode == GestureMode.PAN) {
            currentBeat = Math.max(0.0, currentBeat + (y - gestureLastY)
                    / pixelsPerBeat * settings.timelineScrollSpeed);
            gestureLastY = y;
            invalidate();
        } else if (gestureMode == GestureMode.HOLD_PREVIEW || gestureMode == GestureMode.EVENT_PREVIEW) {
            previewEndBeat = snapBeat(yToBeat(y, timelineY(bottom)));
            if (previewEndBeat <= previewStartBeat) previewEndBeat = previewStartBeat + 1.0 / subdivision;
            invalidate();
        } else if (isEventDragGesture()) {
            if (Math.abs(y - gestureStartY) >= 0.5f * density) {
                eventDragPointerMoved = true;
                updateEventDragCandidate(y, bottom);
                invalidate();
            }
        } else if (gestureMode == GestureMode.NOTE_DRAG) {
            if (movedDuringGesture) {
                updateNoteDragCandidate(x, y, editorSplitX(), bottom);
                invalidate();
            }
        } else if (isHoldResizeGesture()) {
            if (movedDuringGesture) {
                updateHoldResizeCandidate(y, bottom);
                invalidate();
            }
        } else if (gestureMode == GestureMode.RECT_SELECT && selectionRectangle != null) {
            selectionRectangle.right = x;
            selectionRectangle.bottom = y;
            invalidate();
        }
        return true;
    }

    private boolean handleUp(float x, float y) {
        float split = editorSplitX();
        float bottom = getHeight() - bottomBarHeight();
        if (gestureMode == GestureMode.SEEK_BAR) {
            updatePlaybackSeek(x);
            finishPlaybackScrub();
        } else if (gestureMode == GestureMode.HOLD_PREVIEW) {
            previewEndBeat = snapBeat(yToBeat(y, timelineY(bottom)));
            if (previewEndBeat <= previewStartBeat) previewEndBeat = previewStartBeat + 1.0 / subdivision;
            addHoldAt(gestureStartX, previewStartBeat, previewEndBeat, split);
        } else if (gestureMode == GestureMode.EVENT_PREVIEW) {
            previewEndBeat = snapBeat(yToBeat(y, timelineY(bottom)));
            if (previewEndBeat <= previewStartBeat) previewEndBeat = previewStartBeat + 1.0 / subdivision;
            addEvent(previewEventType, previewStartBeat, previewEndBeat);
        } else if (isEventDragGesture()) {
            if (eventDragPointerMoved) {
                updateEventDragCandidate(y, bottom);
                finishEventDrag();
            } else {
                clearEventDragState();
            }
        } else if (gestureMode == GestureMode.NOTE_DRAG) {
            if (movedDuringGesture) {
                updateNoteDragCandidate(x, y, split, bottom);
                finishNoteDrag();
            } else {
                clearNoteTouchState();
            }
        } else if (isHoldResizeGesture()) {
            if (movedDuringGesture) {
                updateHoldResizeCandidate(y, bottom);
                finishHoldResize();
            } else {
                clearNoteTouchState();
            }
        } else if (gestureMode == GestureMode.RECT_SELECT) {
            if (selectionRectangle != null) {
                selectionRectangle.right = x;
                selectionRectangle.bottom = y;
                if (movedDuringGesture) {
                    applyRectangleSelection(selectionRectangle, split, bottom);
                    maybeAutoCopySelection();
                } else if (addSelectionAt(x, y, split, bottom)) {
                    maybeAutoCopySelection();
                } else {
                    clearSelection();
                }
            }
            selectionRectangle = null;
        } else if (gestureMode == GestureMode.PAN) {
            if (!movedDuringGesture && !previewMode) {
                currentBeat = Math.max(0.0, snapBeat(yToBeat(y, timelineY(bottom))));
                clearSelection();
            }
            finishPlaybackScrub();
        }
        gestureMode = GestureMode.NONE;
        invalidate();
        return true;
    }

    private void addSimpleNoteAt(float x, float y, float right, float bottom) {
        Note note = new Note();
        note.startTime = BeatTime.fromDouble(Math.max(0.0, snapBeat(yToBeat(y, timelineY(bottom)))), subdivision);
        note.endTime = note.startTime;
        note.positionX = snapChartX(screenToChartX(x, 0f, right));
        switch (tool) {
            case TAP: note.type = NoteType.TAP; break;
            case DRAG: note.type = NoteType.DRAG; break;
            case FLICK: note.type = NoteType.FLICK; break;
            default: return;
        }
        addNoteCommand(note);
    }

    private void addHoldAt(float x, double startBeat, double endBeat, float right) {
        Note note = new Note();
        note.type = NoteType.HOLD;
        note.startTime = BeatTime.fromDouble(Math.max(0.0, startBeat), subdivision);
        note.endTime = BeatTime.fromDouble(Math.max(startBeat + 1.0 / subdivision, endBeat), subdivision);
        note.positionX = snapChartX(screenToChartX(x, 0f, right));
        addNoteCommand(note);
    }

    private void addNoteCommand(Note note) {
        JudgeLine line = currentLine();
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                if (!line.notes.contains(note)) line.notes.add(note);
                line.sortNotes();
                clearSelection();
                selectedNote = note;
                selectedNotes.add(note);
                activateEditPanel();
            }

            @Override
            public void revert() {
                line.notes.remove(note);
                selectedNotes.remove(note);
                if (selectedNote == note) selectedNote = null;
            }
        });
        invalidate();
    }

    private void addEvent(EventType type, double startBeat, double endBeat) {
        if (type == null) return;
        if (!isEditableEventLayer(eventLayerIndex)) {
            if (callback != null) callback.showMessage(
                    getResources().getString(R.string.validation_event_reserved_layer));
            return;
        }
        EventLayer layer = currentLayer();
        LineEvent event = createEvent(layer, type, startBeat, endBeat);

        if (xyBindingEnabled && XYBindingValidator.isMoveType(type)) {
            LineEvent pair = createEvent(layer, XYBindingValidator.pairedType(type),
                    startBeat, endBeat);
            XYBindingValidator.Error validation = XYBindingValidator.validatePlacement(
                    layer, eventLayerIndex, event, pair);
            if (validation != XYBindingValidator.Error.NONE) {
                if (callback != null) callback.showMessage(xyBindingErrorMessage(validation));
                return;
            }
            history.execute(new EditHistory.Command() {
                private final EditHistory.Command command = XYBindingCommand.add(layer, event, pair);

                @Override
                public void apply() {
                    command.apply();
                    clearSelection();
                    selectedEvent = event;
                    selectedEvents.add(event);
                    activateEditPanel();
                }

                @Override
                public void revert() {
                    command.revert();
                    selectedEvents.remove(event);
                    selectedEvents.remove(pair);
                    if (selectedEvent == event || selectedEvent == pair) selectedEvent = null;
                }
            });
        } else {
            if (layer.overlaps(event, null)) {
                if (callback != null) callback.showMessage(
                        getResources().getString(R.string.validation_event_overlap));
                return;
            }
            history.execute(new EditHistory.Command() {
                @Override
                public void apply() {
                    if (!layer.events(type).contains(event)) layer.events(type).add(event);
                    layer.events(type).sort((first, second) ->
                            first.startTime.compareTo(second.startTime));
                    clearSelection();
                    selectedEvent = event;
                    selectedEvents.add(event);
                    activateEditPanel();
                }

                @Override
                public void revert() {
                    layer.events(type).remove(event);
                    selectedEvents.remove(event);
                    if (selectedEvent == event) selectedEvent = null;
                }
            });
        }
        invalidate();
    }

    private LineEvent createEvent(EventLayer layer, EventType type,
                                  double startBeat, double endBeat) {
        LineEvent event = new LineEvent();
        event.type = type;
        event.startTime = BeatTime.fromDouble(Math.max(0.0, startBeat), subdivision);
        event.endTime = BeatTime.fromDouble(
                Math.max(startBeat + 1.0 / subdivision, endBeat), subdivision);
        LineEvent previous = previousEvent(layer, type, event.startTime.toDouble());
        event.start = previous == null ? LineEvent.defaultValue(type) : previous.end;
        event.end = event.start;
        event.easingType = type == EventType.SPEED || previous == null ? 1 : previous.easingType;
        return event;
    }

    private static LineEvent previousEvent(EventLayer layer, EventType type, double beat) {
        LineEvent previous = null;
        for (LineEvent event : layer.events(type)) {
            if (event.endTime.toDouble() <= beat) previous = event;
            else break;
        }
        return previous;
    }

    private Note findNearestNote(float x, float y, float right, float bottom) {
        float centerY = timelineY(bottom);
        Note nearest = null;
        float nearestDistance = 42f * density;
        for (Note note : currentLine().notes) {
            float noteX = chartXToScreen(note.positionX, 0f, right);
            float startY = beatToY(note.startTime.toDouble(), centerY);
            float noteY = startY;
            if (note.type == NoteType.HOLD) {
                float endY = beatToY(note.endTime.toDouble(), centerY);
                noteY = clamp(y, Math.min(startY, endY), Math.max(startY, endY));
            }
            float dx = noteX - x;
            float dy = noteY - y;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = note;
            }
        }
        return nearest;
    }

    private HoldHandleHit findHoldHandleAt(Note note, float x, float y,
                                           float right, float bottom) {
        if (note == null || note.type != NoteType.HOLD) return null;
        float centerY = timelineY(bottom);
        float handleX = chartXToScreen(note.positionX, 0f, right);
        float startY = beatToY(note.startTime.toDouble(), centerY);
        float endY = beatToY(note.endTime.toDouble(), centerY);
        float radius = 22f * density;
        float startDistance = distance(x, y, handleX, startY);
        float endDistance = distance(x, y, handleX, endY);
        float best = Math.min(startDistance, endDistance);
        return best <= radius ? new HoldHandleHit(startDistance <= endDistance) : null;
    }

    private void beginNoteDrag(Note primary, float x, float y,
                               float right, float bottom) {
        clearNoteTouchState();
        draggedNotes.addAll(selectedNotesForClipboard());
        if (draggedNotes.isEmpty() && primary != null) draggedNotes.add(primary);
        for (Note note : draggedNotes) noteDragBefore.add(note.copy());
        noteDragAnchorBeat = BeatTime.fromDouble(
                snapBeat(yToBeat(y, timelineY(bottom))), subdivision);
        noteDragAnchorX = screenToChartX(x, 0f, right);
        noteDragDeltaBeat = BeatTime.zero();
        noteDragDeltaX = 0.0;
        noteDragValidation = NoteTouchOperation.Error.NONE;
        gestureMode = GestureMode.NOTE_DRAG;
    }

    private void updateNoteDragCandidate(float x, float y, float right, float bottom) {
        if (draggedNotes.isEmpty() || noteDragAnchorBeat == null) return;
        BeatTime pointerBeat = BeatTime.fromDouble(
                snapBeat(yToBeat(y, timelineY(bottom))), subdivision);
        noteDragDeltaBeat = noteMoveMode.constrainBeatDelta(
                pointerBeat.minus(noteDragAnchorBeat));
        int primaryIndex = Math.max(0, draggedNotes.indexOf(selectedNote));
        Note primaryBefore = noteDragBefore.get(primaryIndex);
        double rawDelta = screenToChartX(x, 0f, right) - noteDragAnchorX;
        double targetX = snapChartX(primaryBefore.positionX + rawDelta);
        noteDragDeltaX = noteMoveMode.constrainXDelta(
                targetX - primaryBefore.positionX);
        noteDragValidation = NoteTouchOperation.move(currentLine(), draggedNotes,
                noteDragDeltaBeat, noteDragDeltaX).error;
    }

    private void finishNoteDrag() {
        NoteTouchOperation.Result result = NoteTouchOperation.move(
                currentLine(), draggedNotes, noteDragDeltaBeat, noteDragDeltaX);
        noteDragValidation = result.error;
        if (result.error == NoteTouchOperation.Error.NONE) {
            history.execute(result.command);
            clearSelection();
            selectedNotes.addAll(result.notes);
            selectedNote = result.notes.isEmpty() ? null : result.notes.get(0);
            activateEditPanel();
        } else if (callback != null) {
            callback.showMessage(noteTouchErrorMessage(result.error));
        }
        clearNoteTouchState();
    }

    private void beginHoldResize(Note note, boolean startHandle) {
        clearNoteTouchState();
        resizedHold = note;
        holdBeforeStart = note.startTime;
        holdBeforeEnd = note.endTime;
        holdCandidateStart = note.startTime;
        holdCandidateEnd = note.endTime;
        noteDragValidation = NoteTouchOperation.Error.NONE;
        gestureMode = startHandle ? GestureMode.HOLD_DRAG_START : GestureMode.HOLD_DRAG_END;
    }

    private void updateHoldResizeCandidate(float y, float bottom) {
        if (resizedHold == null) return;
        BeatTime candidate = BeatTime.fromDouble(
                snapBeat(yToBeat(y, timelineY(bottom))), subdivision);
        if (gestureMode == GestureMode.HOLD_DRAG_START) holdCandidateStart = candidate;
        else holdCandidateEnd = candidate;
        noteDragValidation = NoteTouchOperation.resizeHold(currentLine(), resizedHold,
                holdCandidateStart, holdCandidateEnd).error;
    }

    private void finishHoldResize() {
        NoteTouchOperation.Result result = NoteTouchOperation.resizeHold(
                currentLine(), resizedHold, holdCandidateStart, holdCandidateEnd);
        noteDragValidation = result.error;
        if (result.error == NoteTouchOperation.Error.NONE) {
            history.execute(result.command);
            clearSelection();
            selectedNotes.addAll(result.notes);
            selectedNote = result.notes.isEmpty() ? null : result.notes.get(0);
            activateEditPanel();
        } else if (callback != null) {
            callback.showMessage(noteTouchErrorMessage(result.error));
        }
        clearNoteTouchState();
    }

    private boolean isHoldResizeGesture() {
        return gestureMode == GestureMode.HOLD_DRAG_START
                || gestureMode == GestureMode.HOLD_DRAG_END;
    }

    private void clearNoteTouchState() {
        draggedNotes.clear();
        noteDragBefore.clear();
        noteDragAnchorBeat = null;
        noteDragAnchorX = 0.0;
        noteDragDeltaBeat = BeatTime.zero();
        noteDragDeltaX = 0.0;
        noteDragValidation = NoteTouchOperation.Error.NONE;
        resizedHold = null;
        holdBeforeStart = null;
        holdBeforeEnd = null;
        holdCandidateStart = null;
        holdCandidateEnd = null;
    }

    private String noteTouchErrorMessage(NoteTouchOperation.Error error) {
        switch (error) {
            case NEGATIVE_START_TIME:
                return getResources().getString(R.string.validation_negative_start);
            case END_TIME_NOT_AFTER_START:
                return getResources().getString(R.string.validation_end_after_start);
            case X_OUT_OF_RANGE:
                return getResources().getString(R.string.validation_note_x);
            case TARGET_NOT_FOUND:
                return getResources().getString(R.string.validation_target_changed);
            default:
                return getResources().getString(R.string.note_touch_invalid);
        }
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private LineEvent findEventAt(float x, float y, float split, float bottom) {
        EventType type = eventTypeAtX(x, split);
        float centerY = timelineY(bottom);
        float beat = (float) yToBeat(y, centerY);
        LineEvent nearest = null;
        double bestDistance = 0.18;
        for (LineEvent event : currentLayer().events(type)) {
            double start = event.startTime.toDouble();
            double end = event.endTime.toDouble();
            if (beat >= start && beat <= end) return event;
            double distance = Math.min(Math.abs(beat - start), Math.abs(beat - end));
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = event;
            }
        }
        return nearest;
    }

    private EventHandleHit findEventHandleAt(float x, float y, float split, float bottom) {
        if (!isEditableEventLayer(eventLayerIndex)) return null;
        EventType type = eventTypeAtX(x, split);
        float centerY = timelineY(bottom);
        float columnWidth = Math.max(1f, (getWidth() - split) / EventType.values().length);
        float handleX = split + (type.ordinal() + 0.5f) * columnWidth;
        float touchRadius = Math.min(26f * density, columnWidth * 0.48f);
        EventHandleHit nearest = null;
        float bestDistance = touchRadius;
        for (LineEvent event : currentLayer().events(type)) {
            float startY = beatToY(event.startTime.toDouble(), centerY);
            float endY = beatToY(event.endTime.toDouble(), centerY);
            float dx = x - handleX;
            float startDy = y - startY;
            float endDy = y - endY;
            float startDistance = (float) Math.sqrt(dx * dx + startDy * startDy);
            float endDistance = (float) Math.sqrt(dx * dx + endDy * endDy);
            boolean startHandle = startDistance <= endDistance;
            float distance = startHandle ? startDistance : endDistance;
            if (event == selectedEvent) distance -= 0.5f * density;
            if (distance <= bestDistance) {
                bestDistance = distance;
                nearest = new EventHandleHit(event, startHandle);
            }
        }
        return nearest;
    }

    private void beginEventDrag(LineEvent event, boolean startHandle) {
        EventLayer layer = currentLayer();
        if (!isEditableEventLayer(eventLayerIndex)
                || event == null || !layer.events(event.type).contains(event)) {
            if (callback != null) callback.showMessage(getResources().getString(R.string.validation_target_changed));
            gestureMode = GestureMode.NONE;
            return;
        }
        LineEvent pair = null;
        if (xyBindingEnabled && XYBindingValidator.isMoveType(event.type)) {
            XYBindingValidator.PairLookup lookup = XYBindingValidator.findPair(layer, event);
            if (lookup.error != XYBindingValidator.Error.NONE || lookup.event == null) {
                if (callback != null) callback.showMessage(xyBindingErrorMessage(lookup.error));
                gestureMode = GestureMode.NONE;
                return;
            }
            pair = lookup.event;
        }
        draggedEvent = event;
        draggedEventPair = pair;
        draggedEventLayer = layer;
        dragBeforeStart = event.startTime;
        dragBeforeEnd = event.endTime;
        dragPairBeforeStart = pair == null ? null : pair.startTime;
        dragPairBeforeEnd = pair == null ? null : pair.endTime;
        dragCandidateStart = event.startTime;
        dragCandidateEnd = event.endTime;
        dragValidation = EventDragValidator.Error.NONE;
        xyDragValidation = XYBindingValidator.Error.NONE;
        eventDragPointerMoved = false;
        gestureMode = startHandle ? GestureMode.EVENT_DRAG_START : GestureMode.EVENT_DRAG_END;
    }

    private void updateEventDragCandidate(float y, float bottom) {
        if (draggedEvent == null || draggedEventLayer == null) return;
        double baseBeat = gestureMode == GestureMode.EVENT_DRAG_START
                ? dragBeforeStart.toDouble() : dragBeforeEnd.toDouble();
        double adjustedBeat = baseBeat - (y - gestureStartY) / pixelsPerBeat
                * settings.eventScrollSpeed;
        BeatTime snapped = BeatTime.fromDouble(snapBeat(adjustedBeat), subdivision);
        if (gestureMode == GestureMode.EVENT_DRAG_START) dragCandidateStart = snapped;
        else if (gestureMode == GestureMode.EVENT_DRAG_END) dragCandidateEnd = snapped;
        if (draggedEventPair == null) {
            dragValidation = EventDragValidator.validate(draggedEventLayer, eventLayerIndex,
                    draggedEvent, dragCandidateStart, dragCandidateEnd);
            xyDragValidation = XYBindingValidator.Error.NONE;
        } else {
            dragValidation = EventDragValidator.Error.NONE;
            xyDragValidation = XYBindingValidator.validatePairedTimes(
                    draggedEventLayer, eventLayerIndex, draggedEvent, draggedEventPair,
                    dragCandidateStart, dragCandidateEnd);
        }
    }

    private void finishEventDrag() {
        if (draggedEvent == null || draggedEventLayer == null
                || dragCandidateStart == null || dragCandidateEnd == null) {
            clearEventDragState();
            return;
        }
        boolean changed = !dragBeforeStart.equals(dragCandidateStart)
                || !dragBeforeEnd.equals(dragCandidateEnd);
        if (draggedEventPair == null) {
            EventDragValidator.Error validation = EventDragValidator.validate(
                    draggedEventLayer, eventLayerIndex, draggedEvent,
                    dragCandidateStart, dragCandidateEnd);
            dragValidation = validation;
            if (validation == EventDragValidator.Error.NONE && changed) {
                history.execute(EventDragCommand.move(draggedEventLayer, draggedEvent,
                        dragBeforeStart, dragBeforeEnd,
                        dragCandidateStart, dragCandidateEnd));
                selectedEvent = draggedEvent;
                selectedNote = null;
            } else if (validation != EventDragValidator.Error.NONE && callback != null) {
                callback.showMessage(eventDragErrorMessage(validation));
            }
        } else {
            XYBindingValidator.Error validation = XYBindingValidator.validatePairedTimes(
                    draggedEventLayer, eventLayerIndex, draggedEvent, draggedEventPair,
                    dragCandidateStart, dragCandidateEnd);
            xyDragValidation = validation;
            if (validation == XYBindingValidator.Error.NONE && changed) {
                history.execute(XYBindingCommand.move(
                        draggedEventLayer,
                        draggedEvent, dragBeforeStart, dragBeforeEnd,
                        draggedEventPair, dragPairBeforeStart, dragPairBeforeEnd,
                        dragCandidateStart, dragCandidateEnd));
                selectedEvent = draggedEvent;
                selectedNote = null;
            } else if (validation != XYBindingValidator.Error.NONE && callback != null) {
                callback.showMessage(xyBindingErrorMessage(validation));
            }
        }
        clearEventDragState();
    }

    private String eventDragErrorMessage(EventDragValidator.Error error) {
        switch (error) {
            case NEGATIVE_START_TIME:
                return getResources().getString(R.string.validation_negative_start);
            case END_TIME_NOT_AFTER_START:
                return getResources().getString(R.string.validation_end_after_start);
            case EVENT_OVERLAP:
                return getResources().getString(R.string.validation_event_overlap);
            case RESERVED_LAYER:
                return getResources().getString(R.string.validation_event_reserved_layer);
            case TARGET_NOT_FOUND:
            default:
                return getResources().getString(R.string.validation_target_changed);
        }
    }

    private boolean isEventDragGesture() {
        return gestureMode == GestureMode.EVENT_DRAG_START
                || gestureMode == GestureMode.EVENT_DRAG_END;
    }

    private void clearEventDragState() {
        draggedEvent = null;
        draggedEventPair = null;
        draggedEventLayer = null;
        dragBeforeStart = null;
        dragBeforeEnd = null;
        dragPairBeforeStart = null;
        dragPairBeforeEnd = null;
        dragCandidateStart = null;
        dragCandidateEnd = null;
        dragValidation = EventDragValidator.Error.NONE;
        xyDragValidation = XYBindingValidator.Error.NONE;
        eventDragPointerMoved = false;
    }

    private static boolean isEditableEventLayer(int layerIndex) {
        return layerIndex >= 0 && layerIndex <= 3;
    }

    private void deleteSelection() {
        if (selectionCount() > 1) {
            deleteMultipleSelection();
            return;
        }
        if (selectedNote != null) {
            JudgeLine line = currentLine();
            Note note = selectedNote;
            int originalIndex = line.notes.indexOf(note);
            history.execute(new EditHistory.Command() {
                @Override
                public void apply() {
                    line.notes.remove(note);
                    selectedNotes.remove(note);
                    if (selectedNote == note) selectedNote = null;
                }

                @Override
                public void revert() {
                    int index = Math.max(0, Math.min(originalIndex, line.notes.size()));
                    line.notes.add(index, note);
                    selectedNote = note;
                    selectedNotes.add(note);
                    selectedEvent = null;
                }
            });
        } else if (selectedEvent != null) {
            EventLayer layer = currentLayer();
            LineEvent event = selectedEvent;
            if (!isEditableEventLayer(eventLayerIndex)) {
                if (callback != null) callback.showMessage(
                        getResources().getString(R.string.validation_event_reserved_layer));
                return;
            }
            if (xyBindingEnabled && XYBindingValidator.isMoveType(event.type)) {
                XYBindingValidator.PairLookup lookup = XYBindingValidator.findPair(layer, event);
                if (lookup.error != XYBindingValidator.Error.NONE || lookup.event == null) {
                    if (callback != null) callback.showMessage(xyBindingErrorMessage(lookup.error));
                    return;
                }
                LineEvent pair = lookup.event;
                history.execute(new EditHistory.Command() {
                    private final EditHistory.Command command =
                            XYBindingCommand.delete(layer, event, pair);

                    @Override
                    public void apply() {
                        command.apply();
                        selectedEvents.remove(event);
                        selectedEvents.remove(pair);
                        if (selectedEvent == event || selectedEvent == pair) selectedEvent = null;
                    }

                    @Override
                    public void revert() {
                        command.revert();
                        selectedEvent = event;
                        selectedEvents.add(event);
                        selectedEvents.add(pair);
                        selectedNote = null;
                    }
                });
            } else {
                List<LineEvent> list = layer.events(event.type);
                int originalIndex = list.indexOf(event);
                history.execute(new EditHistory.Command() {
                    @Override
                    public void apply() {
                        list.remove(event);
                        selectedEvents.remove(event);
                        if (selectedEvent == event) selectedEvent = null;
                    }

                    @Override
                    public void revert() {
                        int index = Math.max(0, Math.min(originalIndex, list.size()));
                        list.add(index, event);
                        selectedEvent = event;
                        selectedEvents.add(event);
                        selectedNote = null;
                    }
                });
            }
        }
        invalidate();
    }

    private void mirrorSelectedNote() {
        if (selectedNote == null) return;
        mutateSelectedNote(note -> note.positionX = -note.positionX);
    }

    private void switchSelectedNoteSide() {
        if (selectedNote == null) return;
        mutateSelectedNote(note -> note.above = note.above == 1 ? 0 : 1);
    }

    private void toggleSelectedNoteFake() {
        if (selectedNote == null) return;
        mutateSelectedNote(note -> note.fake = !note.fake);
    }

    private void resizeSelectedNote(double delta) {
        if (selectedNote == null) return;
        mutateSelectedNote(note -> note.size = Math.max(0.1, Math.min(8.0, note.size + delta)));
    }

    private void mutateSelectedNote(NoteMutation mutation) {
        Note target = selectedNote;
        Note before = target.copy();
        Note after = target.copy();
        mutation.apply(after);
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                copyNoteFields(after, target);
                selectedNote = target;
            }

            @Override
            public void revert() {
                copyNoteFields(before, target);
                selectedNote = target;
            }
        });
        invalidate();
    }

    private void adjustSelectedEventValue(boolean startValue, double delta) {
        if (selectedEvent == null) return;
        mutateSelectedEvent(event -> {
            if (startValue) event.start = normalizeEventValue(event.type, event.start + delta);
            else event.end = normalizeEventValue(event.type, event.end + delta);
        });
    }

    private void toggleSelectedEventLink() {
        if (selectedEvent == null) return;
        mutateSelectedEvent(event -> event.linkGroup = event.linkGroup == 0 ? 1 : 0);
    }

    private void splitSelectedEvent() {
        if (selectedEvent == null) return;
        EventLayer layer = currentLayer();
        LineEvent target = selectedEvent;
        boolean snapped = settings.splitSnapToGrid;
        BeatTime cutTime = BeatTime.fromDouble(
                snapped ? snapBeat(currentBeat) : Math.max(0.0, currentBeat),
                snapped ? subdivision : FREE_SPLIT_DIVISION);
        EventCutGlueValidator.Error validation = EventCutGlueValidator.validateCut(
                layer, eventLayerIndex, target, cutTime);
        if (validation != EventCutGlueValidator.Error.NONE) {
            if (callback != null) callback.showMessage(eventCutGlueErrorMessage(validation));
            return;
        }

        EventCutCommand.CutOperation operation = EventCutCommand.cut(layer, target, cutTime);
        EventCutCommand.CutOperation pairOperation = null;
        if (xyBindingEnabled && XYBindingValidator.isMoveType(target.type)) {
            XYBindingValidator.PairLookup lookup = XYBindingValidator.findPair(layer, target);
            if (lookup.error != XYBindingValidator.Error.NONE || lookup.event == null) {
                if (callback != null) callback.showMessage(xyBindingErrorMessage(lookup.error));
                return;
            }
            EventCutGlueValidator.Error pairValidation = EventCutGlueValidator.validateCut(
                    layer, eventLayerIndex, lookup.event, cutTime);
            if (pairValidation != EventCutGlueValidator.Error.NONE) {
                if (callback != null) callback.showMessage(eventCutGlueErrorMessage(pairValidation));
                return;
            }
            pairOperation = EventCutCommand.cut(layer, lookup.event, cutTime);
        }

        EventCutCommand.CutOperation finalPairOperation = pairOperation;
        EditHistory.Command command = finalPairOperation == null
                ? operation
                : XYBindingCommand.cut(operation, finalPairOperation);
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                command.apply();
                selectedNotes.clear();
                selectedEvents.clear();
                selectedEvents.add(operation.rightEvent());
                selectedEvent = operation.rightEvent();
                selectedNote = null;
            }

            @Override
            public void revert() {
                command.revert();
                selectedNotes.clear();
                selectedEvents.clear();
                selectedEvents.add(target);
                selectedEvent = target;
                selectedNote = null;
            }
        });
        if (callback != null) {
            int message;
            if (finalPairOperation == null) {
                message = snapped ? R.string.event_split_applied_grid
                        : R.string.event_split_applied_exact;
            } else {
                message = snapped ? R.string.event_split_xy_applied_grid
                        : R.string.event_split_xy_applied_exact;
            }
            callback.showMessage(getResources().getString(message));
        }
        invalidate();
    }

    private void passSelectedEvent() {
        if (selectedEvent == null) return;
        LineEvent target = selectedEvent;
        EventAdvancedOperation.Result result = EventAdvancedOperation.pass(currentLayer(), target);
        if (result.error != EventAdvancedOperation.Error.NONE) {
            if (callback != null) callback.showMessage(getResources().getString(
                    result.error == EventAdvancedOperation.Error.NOT_ENOUGH_PREVIOUS_EVENTS
                            ? R.string.event_pass_needs_two
                            : result.error == EventAdvancedOperation.Error.INVALID_RESULT
                            ? R.string.event_pass_invalid : R.string.validation_target_changed));
            return;
        }
        history.execute(result.command);
        selectedEvent = target;
        selectedEvents.add(target);
        if (callback != null) callback.showMessage(getResources().getString(
                R.string.event_pass_applied, compactValue(target.start), compactValue(target.end)));
        invalidate();
    }

    private void randomizeSelectedEvent() {
        if (selectedEvent == null) return;
        LineEvent target = selectedEvent;
        EventAdvancedOperation.Result result = EventAdvancedOperation.randomize(
                currentLayer(), target, Math.random());
        if (result.error != EventAdvancedOperation.Error.NONE) {
            if (callback != null) callback.showMessage(getResources().getString(
                    result.error == EventAdvancedOperation.Error.INVALID_RESULT
                            ? R.string.event_rand_invalid : R.string.validation_target_changed));
            return;
        }
        history.execute(result.command);
        selectedEvent = target;
        selectedEvents.add(target);
        if (callback != null) callback.showMessage(getResources().getString(
                R.string.event_rand_applied, compactValue(result.endValue)));
        invalidate();
    }

    private void glueSelectedEvent() {
        if (selectedEvent == null) return;
        EventLayer layer = currentLayer();
        LineEvent target = selectedEvent;
        EventCutGlueValidator.Error validation = EventCutGlueValidator.validateGlue(
                layer, eventLayerIndex, target);
        if (validation != EventCutGlueValidator.Error.NONE) {
            if (callback != null) callback.showMessage(eventCutGlueErrorMessage(validation));
            return;
        }
        LineEvent previous = EventCutGlueValidator.previousSameType(layer, target);
        if (previous == null) {
            if (callback != null) callback.showMessage(
                    getResources().getString(R.string.validation_event_no_previous));
            return;
        }
        if (Double.compare(previous.end, target.start) == 0) {
            if (callback != null) callback.showMessage(
                    getResources().getString(R.string.event_glue_no_change));
            return;
        }
        history.execute(EventGlueCommand.glue(previous, target));
        selectedEvent = target;
        selectedNote = null;
        if (callback != null) callback.showMessage(
                getResources().getString(R.string.event_glue_applied));
        invalidate();
    }

    private String xyBindingErrorMessage(XYBindingValidator.Error error) {
        switch (error) {
            case RESERVED_LAYER:
                return getResources().getString(R.string.validation_event_reserved_layer);
            case NEGATIVE_START_TIME:
                return getResources().getString(R.string.validation_negative_start);
            case END_TIME_NOT_AFTER_START:
                return getResources().getString(R.string.validation_end_after_start);
            case EVENT_OVERLAP:
                return getResources().getString(R.string.validation_event_overlap);
            case PAIR_NOT_FOUND:
            case AMBIGUOUS_PAIR:
            case LAYER_NOT_SYNCHRONIZED:
                return getResources().getString(R.string.validation_xy_binding_pair);
            case NOT_MOVE_EVENT:
            case TARGET_NOT_FOUND:
            default:
                return getResources().getString(R.string.validation_target_changed);
        }
    }

    private String eventCutGlueErrorMessage(EventCutGlueValidator.Error error) {
        switch (error) {
            case RESERVED_LAYER:
                return getResources().getString(R.string.validation_event_reserved_layer);
            case INVALID_EVENT_TIME:
                return getResources().getString(R.string.validation_event_invalid_time);
            case CUT_NOT_INSIDE_EVENT:
                return getResources().getString(R.string.validation_event_cut_inside);
            case EVENT_OVERLAP:
                return getResources().getString(R.string.validation_event_overlap);
            case NO_PREVIOUS_EVENT:
                return getResources().getString(R.string.validation_event_no_previous);
            case TARGET_NOT_FOUND:
            default:
                return getResources().getString(R.string.validation_target_changed);
        }
    }

    private void mutateSelectedEvent(EventMutation mutation) {
        LineEvent target = selectedEvent;
        LineEvent before = target.copy();
        LineEvent after = target.copy();
        mutation.apply(after);
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                copyEventFields(after, target);
                selectedEvent = target;
            }

            @Override
            public void revert() {
                copyEventFields(before, target);
                selectedEvent = target;
            }
        });
        invalidate();
    }

    private void undo() {
        history.undo();
        if (settings.skipWhenUndoRedo) focusCurrentSelection();
        invalidate();
    }

    private EditHistory.Command withAutoStick(EventLayer layer, LineEvent target,
                                              LineEvent edited,
                                              EditHistory.Command command) {
        if (!settings.autoStickEvents) return command;
        LineEvent next = null;
        for (LineEvent candidate : layer.events(target.type)) {
            if (candidate == target) continue;
            if (candidate.startTime.compareTo(edited.endTime) >= 0
                    && (next == null
                    || candidate.startTime.compareTo(next.startTime) < 0)) {
                next = candidate;
            }
        }
        if (next == null) return command;
        LineEvent bonded = next;
        double beforeStart = bonded.start;
        double afterStart = edited.end;
        return new EditHistory.Command() {
            @Override
            public void apply() {
                command.apply();
                bonded.start = afterStart;
            }

            @Override
            public void revert() {
                bonded.start = beforeStart;
                command.revert();
            }
        };
    }

    private void focusCurrentSelection() {
        if (selectedNote != null) currentBeat = Math.max(0.0, selectedNote.startTime.toDouble());
        else if (selectedEvent != null) currentBeat = Math.max(0.0, selectedEvent.startTime.toDouble());
    }

    private void redo() {
        history.redo();
        if (settings.skipWhenUndoRedo) focusCurrentSelection();
        invalidate();
    }

    private void togglePlay() {
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

    private void beginPanGesture() {
        gestureMode = GestureMode.PAN;
        playbackScrubbing = playing;
        if (playbackScrubbing && callback != null && callback.isAudioReady()) {
            callback.pauseAudio();
        }
    }

    private boolean beginPlaybackSeek(float x, float y) {
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

    private void updatePlaybackSeek(float x) {
        if (callback == null || !callback.isAudioReady()) return;
        long duration = callback.audioDurationMs();
        double fraction = PlaybackScrubMapper.fractionForX(
                x, playbackSeekBounds.left, playbackSeekBounds.right);
        long position = PlaybackScrubMapper.positionForFraction(fraction, duration);
        currentBeat = Math.max(0.0, chart.audioMillisToBeat(position, packageOffsetMs));
    }

    private void finishPlaybackScrub() {
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
        long currentTimeMs = chart.beatToMillis(currentBeat);
        if (currentTimeMs < lastHitSoundChartTimeMs) {
            lastHitSoundChartTimeMs = currentTimeMs;
            return;
        }
        if (callback != null) {
            for (NoteType type : hitSoundTimeline.between(
                    lastHitSoundChartTimeMs, currentTimeMs)) {
                callback.playHitSound(type);
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

    private JudgeLine currentLine() {
        lineIndex = Math.max(0, Math.min(chart.judgeLines.size() - 1, lineIndex));
        return chart.judgeLines.get(lineIndex);
    }

    private EventLayer currentLayer() {
        return currentLine().layer(eventLayerIndex);
    }

    private EventType eventTypeAtX(float x, float split) {
        float columnWidth = Math.max(1f, (getWidth() - split) / EventType.values().length);
        int column = (int) ((x - split) / columnWidth);
        return EventType.fromColumn(column);
    }

    private boolean hasSelection() {
        return selectionCount() > 0;
    }

    private void clearSelection() {
        selectedNote = null;
        selectedEvent = null;
        selectedNotes.clear();
        selectedEvents.clear();
    }

    private int selectionCount() {
        int count = selectedNotes.size() + selectedEvents.size();
        if (selectedNote != null && !selectedNotes.contains(selectedNote)) count++;
        if (selectedEvent != null && !selectedEvents.contains(selectedEvent)) count++;
        return count;
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

    private boolean addSelectionAt(float x, float y, float split, float bottom) {
        if (x < split) {
            Note note = findNearestNote(x, y, split, bottom);
            if (note == null) return false;
            selectedNotes.add(note);
        } else {
            LineEvent event = findEventAt(x, y, split, bottom);
            if (event == null) return false;
            updateRectangleEventSelection(event, true);
        }
        selectedNote = selectedNotes.isEmpty() ? null : selectedNotes.iterator().next();
        selectedEvent = selectedEvents.isEmpty() ? null : selectedEvents.iterator().next();
        if (selectedNote != null) selectedEvent = null;
        activateEditPanel();
        return true;
    }

    private void applyRectangleSelection(RectF rawBounds, float split, float bottom) {
        RectF bounds = normalized(rawBounds);
        float centerY = timelineY(bottom);
        boolean add = rectangleSelectionMode == RectangleSelectionMode.ADD;
        if (bounds.left < split) {
            for (Note note : currentLine().notes) {
                float x = chartXToScreen(note.positionX, 0f, split);
                float startY = beatToY(note.startTime.toDouble(), centerY);
                float endY = note.type == NoteType.HOLD
                        ? beatToY(note.endTime.toDouble(), centerY) : startY;
                if (x >= bounds.left && x <= bounds.right
                        && Math.max(Math.min(startY, endY), bounds.top)
                        <= Math.min(Math.max(startY, endY), bounds.bottom)) {
                    if (add) selectedNotes.add(note); else selectedNotes.remove(note);
                }
            }
        }
        if (bounds.right >= split) {
            float columnWidth = Math.max(1f, (getWidth() - split) / EventType.values().length);
            for (EventType type : EventType.values()) {
                float left = split + type.ordinal() * columnWidth;
                float right = left + columnWidth;
                if (right < bounds.left || left > bounds.right) continue;
                for (LineEvent event : currentLayer().events(type)) {
                    float startY = beatToY(event.startTime.toDouble(), centerY);
                    float endY = beatToY(event.endTime.toDouble(), centerY);
                    if (Math.max(Math.min(startY, endY), bounds.top)
                            <= Math.min(Math.max(startY, endY), bounds.bottom)) {
                        updateRectangleEventSelection(event, add);
                    }
                }
            }
        }
        selectedNote = selectedNotes.isEmpty() ? null : selectedNotes.iterator().next();
        selectedEvent = selectedEvents.isEmpty() ? null : selectedEvents.iterator().next();
        if (selectedNote != null) selectedEvent = null;
    }

    private void updateRectangleEventSelection(LineEvent event, boolean add) {
        if (add) selectedEvents.add(event); else selectedEvents.remove(event);
        if (!xyBindingEnabled || !XYBindingValidator.isMoveType(event.type)) return;
        XYBindingValidator.PairLookup pair = XYBindingValidator.findPair(currentLayer(), event);
        if (pair.error == XYBindingValidator.Error.NONE && pair.event != null) {
            if (add) selectedEvents.add(pair.event); else selectedEvents.remove(pair.event);
        }
    }

    private void copySelection() {
        clipboard = ChartClipboard.copy(selectedNotesForClipboard(), selectedEventsForClipboard());
        if (clipboard.isEmpty()) {
            if (callback != null) callback.showMessage(getResources().getString(R.string.clipboard_empty_selection));
            return;
        }
        if (callback != null) callback.showMessage(getResources().getString(
                R.string.clipboard_copied, clipboard.noteCount(), clipboard.eventCount()));
        invalidate();
    }

    private void maybeAutoCopySelection() {
        if (!settings.autoMoveToClipboard || selectionCount() <= 1) return;
        ChartClipboard.Snapshot snapshot = ChartClipboard.copy(
                selectedNotesForClipboard(), selectedEventsForClipboard());
        if (!snapshot.isEmpty()) clipboard = snapshot;
    }

    private void cutSelection() {
        List<Note> notes = selectedNotesForClipboard();
        List<LineEvent> events = selectedEventsForClipboard();
        ChartClipboard.Snapshot snapshot = ChartClipboard.copy(notes, events);
        ChartClipboard.Operation operation = ChartClipboard.prepareCut(
                currentLine(), currentLayer(), notes, events, xyBindingEnabled);
        if (operation.error != ChartClipboard.Error.NONE) {
            showClipboardError(operation.error);
            return;
        }
        clipboard = snapshot;
        executeRemovalOperation(operation);
        if (callback != null) callback.showMessage(getResources().getString(R.string.clipboard_cut));
    }

    private void deleteMultipleSelection() {
        ChartClipboard.Operation operation = ChartClipboard.prepareCut(currentLine(), currentLayer(),
                selectedNotesForClipboard(), selectedEventsForClipboard(), xyBindingEnabled);
        if (operation.error != ChartClipboard.Error.NONE) {
            showClipboardError(operation.error);
            return;
        }
        executeRemovalOperation(operation);
    }

    private void executeRemovalOperation(ChartClipboard.Operation operation) {
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                operation.command.apply();
                clearSelection();
            }

            @Override
            public void revert() {
                operation.command.revert();
                selectedNotes.addAll(operation.notes);
                selectedEvents.addAll(operation.events);
                selectedNote = selectedNotes.isEmpty() ? null : selectedNotes.iterator().next();
                selectedEvent = selectedNote == null && !selectedEvents.isEmpty()
                        ? selectedEvents.iterator().next() : null;
            }
        });
        invalidate();
    }

    private void pasteClipboard(boolean mirrorNotes) {
        BeatTime anchor = BeatTime.fromDouble(Math.max(0.0, snapBeat(currentBeat)), subdivision);
        ChartClipboard.Operation operation = ChartClipboard.preparePaste(clipboard,
                currentLine(), currentLayer(), eventLayerIndex, anchor, mirrorNotes, xyBindingEnabled);
        if (operation.error != ChartClipboard.Error.NONE) {
            showClipboardError(operation.error);
            return;
        }
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                operation.command.apply();
                clearSelection();
                selectedNotes.addAll(operation.notes);
                selectedEvents.addAll(operation.events);
                selectedNote = selectedNotes.isEmpty() ? null : selectedNotes.iterator().next();
                selectedEvent = selectedNote == null && !selectedEvents.isEmpty()
                        ? selectedEvents.iterator().next() : null;
            }

            @Override
            public void revert() {
                operation.command.revert();
                clearSelection();
            }
        });
        if (callback != null) callback.showMessage(getResources().getString(R.string.clipboard_pasted));
        invalidate();
    }

    private List<Note> selectedNotesForClipboard() {
        List<Note> result = new ArrayList<>();
        for (Note note : selectedNotes) if (currentLine().notes.contains(note)) result.add(note);
        if (selectedNote != null && currentLine().notes.contains(selectedNote)
                && !result.contains(selectedNote)) result.add(selectedNote);
        return result;
    }

    private List<LineEvent> selectedEventsForClipboard() {
        List<LineEvent> result = new ArrayList<>();
        for (LineEvent event : selectedEvents) {
            if (currentLayer().events(event.type).contains(event)) result.add(event);
        }
        if (selectedEvent != null && currentLayer().events(selectedEvent.type).contains(selectedEvent)
                && !result.contains(selectedEvent)) result.add(selectedEvent);
        if (xyBindingEnabled) {
            List<LineEvent> initiallySelected = new ArrayList<>(result);
            for (LineEvent event : initiallySelected) {
                if (!XYBindingValidator.isMoveType(event.type)) continue;
                XYBindingValidator.PairLookup pair = XYBindingValidator.findPair(currentLayer(), event);
                if (pair.error == XYBindingValidator.Error.NONE && pair.event != null
                        && !result.contains(pair.event)) result.add(pair.event);
            }
        }
        return result;
    }

    private void showClipboardError(ChartClipboard.Error error) {
        int message;
        switch (error) {
            case EVENT_OVERLAP: message = R.string.validation_event_overlap; break;
            case RESERVED_LAYER: message = R.string.validation_event_reserved_layer; break;
            case XY_PAIR_REQUIRED: message = R.string.clipboard_xy_pair_required; break;
            case EMPTY: message = R.string.clipboard_empty_selection; break;
            default: message = R.string.clipboard_invalid; break;
        }
        if (callback != null) callback.showMessage(getResources().getString(message));
    }

    private static RectF normalized(RectF value) {
        return new RectF(Math.min(value.left, value.right), Math.min(value.top, value.bottom),
                Math.max(value.left, value.right), Math.max(value.top, value.bottom));
    }


    @Override
    protected void onDetachedFromWindow() {
        setBackgroundIllustration(null);
        setPreviewLineTextures(null);
        super.onDetachedFromWindow();
    }

    private float editorSplitX() {
        return getWidth() * 0.70f;
    }

    private float toolbarHeight() {
        return 48f * density;
    }

    private float bottomBarHeight() {
        return 96f * density;
    }

    private float timelineY(float editorBottom) {
        return editorBottom - 72f * density;
    }

    private boolean isInEditor(float y) {
        return y > toolbarHeight() && y < getHeight() - bottomBarHeight();
    }

    private float beatToY(double beat, float centerY) {
        return centerY - (float) ((beat - currentBeat) * pixelsPerBeat);
    }

    private double yToBeat(float y, float centerY) {
        return currentBeat + (centerY - y) / pixelsPerBeat;
    }

    private double snapBeat(double beat) {
        return Math.max(0.0, Math.round(beat * subdivision) / (double) subdivision);
    }

    private double snapChartX(double x) {
        return VerticalGrid.snap(x, CHART_X_MIN, CHART_X_MAX, verticalGridLines);
    }

    private static float chartXToScreen(double x, float left, float right) {
        return left + (float) ((x - CHART_X_MIN) / (CHART_X_MAX - CHART_X_MIN) * (right - left));
    }

    private static double screenToChartX(float x, float left, float right) {
        double normalized = (x - left) / Math.max(1f, right - left);
        return CHART_X_MIN + normalized * (CHART_X_MAX - CHART_X_MIN);
    }

    private static int clampAlpha(int alpha) {
        return Math.max(0, Math.min(255, alpha));
    }

    private int eventColor(EventType type, boolean linked) {
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
        return Color.rgb((Color.red(color) + 30) / 2, (Color.green(color) + 40) / 2, (Color.blue(color) + 90) / 2);
    }

    private String eventEasingLabel(LineEvent event) {
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

    private void drawSelection(Canvas canvas, RectF rect) {
        strokePaint.setColor(Color.WHITE);
        strokePaint.setStrokeWidth(2.5f * density);
        canvas.drawRoundRect(new RectF(rect.left - 3f * density, rect.top - 3f * density,
                rect.right + 3f * density, rect.bottom + 3f * density), 7f * density, 7f * density, strokePaint);
    }

    private static double eventStep(EventType type) {
        switch (type) {
            case ROTATE: return 0.25;
            case SPEED: return 0.1;
            default: return 10.0;
        }
    }

    private static double normalizeEventValue(EventType type, double value) {
        if (type == EventType.ALPHA) return Math.max(-255.0, Math.min(255.0, value));
        return value;
    }

    private static void copyNoteFields(Note source, Note target) {
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

    private static void copyEventFields(LineEvent source, LineEvent target) {
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
        System.arraycopy(source.bezierPoints, 0, target.bezierPoints, 0, source.bezierPoints.length);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color), Color.green(color), Color.blue(color));
    }

    private String ellipsize(String text, float maximumWidth) {
        if (text == null || maximumWidth <= 0f) return "";
        if (paint.measureText(text) <= maximumWidth) return text;
        String suffix = "…";
        int end = text.length();
        while (end > 0 && paint.measureText(text, 0, end) + paint.measureText(suffix) > maximumWidth) {
            end--;
        }
        return end <= 0 ? suffix : text.substring(0, end) + suffix;
    }

    private String ellipsizeFromEnd(String text, float maximumWidth) {
        if (text == null || maximumWidth <= 0f) return "";
        if (paint.measureText(text) <= maximumWidth) return text;
        String prefix = "…";
        int start = 0;
        while (start < text.length()
                && paint.measureText(text, start, text.length()) + paint.measureText(prefix) > maximumWidth) {
            start++;
        }
        return start >= text.length() ? prefix : prefix + text.substring(start);
    }

    private static String compactValue(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) return Long.toString(Math.round(value));
        return String.format(Locale.US, "%.2f", value);
    }

    private static String formatMillis(long milliseconds) {
        long safe = Math.max(0L, milliseconds);
        long minutes = safe / 60000L;
        long seconds = (safe / 1000L) % 60L;
        long millis = safe % 1000L;
        return String.format(Locale.US, "%d:%02d.%03d", minutes, seconds, millis);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private interface NoteMutation {
        void apply(Note note);
    }

    private interface EventMutation {
        void apply(LineEvent event);
    }

    private static final class EventHandleHit {
        final LineEvent event;
        final boolean startHandle;

        EventHandleHit(LineEvent event, boolean startHandle) {
            this.event = event;
            this.startHandle = startHandle;
        }
    }

    private static final class HoldHandleHit {
        final boolean startHandle;

        HoldHandleHit(boolean startHandle) {
            this.startHandle = startHandle;
        }
    }

    private static final class BottomAction {
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

    private static final class ButtonSpec {
        final RectF bounds;
        final Runnable action;

        ButtonSpec(RectF bounds, Runnable action) {
            this.bounds = bounds;
            this.action = action;
        }
    }
}
