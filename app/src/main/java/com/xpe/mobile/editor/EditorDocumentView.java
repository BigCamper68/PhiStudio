package com.xpe.mobile.editor;

import android.content.Context;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;
import com.xpe.mobile.model.StoryboardEventType;
import java.util.ArrayList;
import java.util.List;

/**
 * Chart lifecycle and undoable document commands shared by the interactive editor.
 *
 * <p>Canvas rendering lives in {@link EditorSurfaceView}; touch handling and editor chrome live in
 * {@link EditorView}. This layer keeps document mutations independent from both.
 */
abstract class EditorDocumentView extends EditorSurfaceView {
    private final EditorGeneratedEdits editorGeneratedEdits = new EditorGeneratedEdits(this);
    private final EditorLineEdits editorLineEdits = new EditorLineEdits(this);
    private final EditorPropertyEdits editorPropertyEdits = new EditorPropertyEdits(this);
    private final EditorStoryboardEdits editorStoryboardEdits = new EditorStoryboardEdits(this);

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
        INVALID_PROPERTIES,
        INVALID_PARENT
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

    protected final EditHistory history;

    protected EditorDocumentView(Context context) {
        super(context);
        history = new EditHistory(300, this::markChartDirty);
    }

    public boolean isPlaying() {
        return playing;
    }

    public com.xpe.mobile.noiseeditor.NoiseFieldDocument createNoiseFieldDocument() {
        com.xpe.mobile.model.ChartDocument scope = chart;
        if (scope == null) throw new IllegalStateException("A chart is required");
        return new com.xpe.mobile.noiseeditor.NoiseFieldDocument(scope, history, () -> chart == scope);
    }

    public BpmApplyResult addBpmChange(BpmChange change) {
        return editorPropertyEdits.addBpmChange(change);
    }

    public BpmApplyResult applyBpmProperties(BpmChange target, BpmChange edited) {
        return editorPropertyEdits.applyBpmProperties(target, edited);
    }

    public BpmApplyResult deleteBpmChange(BpmChange target) {
        return editorPropertyEdits.deleteBpmChange(target);
    }

    public PropertyApplyResult applyNoteProperties(Note target, Note edited) {
        return editorPropertyEdits.applyNoteProperties(target, edited);
    }

    public PropertyApplyResult applyEventProperties(LineEvent target, LineEvent edited) {
        return editorPropertyEdits.applyEventProperties(target, edited);
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
        return editorStoryboardEdits.addStoryboardEvent(type, event);
    }

    public StoryboardApplyResult applyStoryboardEvent(
            StoryboardEventType type, ExtendedLineEvents.TimedEvent target,
            ExtendedLineEvents.TimedEvent edited) {
        return editorStoryboardEdits.applyStoryboardEvent(type, target, edited);
    }

    public StoryboardApplyResult deleteStoryboardEvent(
            StoryboardEventType type, ExtendedLineEvents.TimedEvent target) {
        return editorStoryboardEdits.deleteStoryboardEvent(type, target);
    }

    public StoryboardApplyResult glueStoryboardEvent(
            StoryboardEventType type, ExtendedLineEvents.TimedEvent target) {
        return editorStoryboardEdits.glueStoryboardEvent(type, target);
    }

    public StoryboardApplyResult splitStoryboardEvent(
            StoryboardEventType type, ExtendedLineEvents.TimedEvent target,
            BeatTime splitTime) {
        return editorStoryboardEdits.splitStoryboardEvent(type, target, splitTime);
    }

    public List<Note> getSelectedNotesForCurve() {
        return editorGeneratedEdits.getSelectedNotesForCurve();
    }

    public List<Note> getSelectedNotesForBatch() {
        return editorGeneratedEdits.getSelectedNotesForBatch();
    }

    public List<LineEvent> getSelectedEventsForBatch() {
        return editorGeneratedEdits.getSelectedEventsForBatch();
    }

    public BatchEditOperation.Result applyNoteBatch(BatchEditOperation.NoteField field,
                                                    BatchValueTransform.Spec profile,
                                                    BatchValueTransform.Mode mode) {
        return editorGeneratedEdits.applyNoteBatch(field, profile, mode);
    }

    public BatchEditOperation.Result applyEventBatch(BatchEditOperation.EventField field,
                                                     BatchValueTransform.Spec profile,
                                                     BatchValueTransform.Mode mode,
                                                     EventType eventType) {
        return editorGeneratedEdits.applyEventBatch(field, profile, mode, eventType);
    }

    public BatchEditOperation.Result stickSelectedEvents(EventType eventType) {
        return editorGeneratedEdits.stickSelectedEvents(eventType);
    }

    public EventCloneOperation.Result applyEventClone(EventCloneOperation.Spec spec) {
        return editorGeneratedEdits.applyEventClone(spec);
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
        return editorGeneratedEdits.applyComplexMove(spec);
    }

    public CurveNoteGenerator.Result applyCurveNotes(Note startNote, Note endNote,
                                                      double curveDensity,
                                                      NoteType noteType,
                                                      int easingType) {
        return editorGeneratedEdits.applyCurveNotes(startNote, endNote, curveDensity, noteType, easingType);
    }

    public LineApplyResult selectLine(int index) {
        return editorLineEdits.selectLine(index);
    }

    public boolean navigateToDiagnostic(ChartDiagnostic diagnostic) {
        return editorLineEdits.navigateToDiagnostic(diagnostic);
    }

    public JudgeLine addJudgeLine() {
        return editorLineEdits.addJudgeLine();
    }

    public LineApplyResult deleteActiveLine() {
        return editorLineEdits.deleteActiveLine();
    }

    public LineApplyResult applyJudgeLineProperties(JudgeLine target, JudgeLine edited) {
        return editorLineEdits.applyJudgeLineProperties(target, edited);
    }

    protected static boolean isEditableEventLayer(int layerIndex) {
        return layerIndex >= 0 && layerIndex <= 3;
    }

    public abstract void markChartDirty();

    protected abstract void cancelActiveGesture();

    protected abstract void clearSelection();

    protected abstract void activateEditPanel();

    protected abstract List<Note> selectedNotesForClipboard();

    protected abstract List<LineEvent> selectedEventsForClipboard();

    protected abstract void undo();

    protected abstract void redo();

    protected abstract void copySelection();

    protected abstract void cutSelection();

    protected abstract void pasteClipboard(boolean mirrorNotes);

    protected abstract void deleteSelection();

    protected abstract void togglePlay();

    protected abstract EditHistory.Command withAutoStick(
            EventLayer layer, LineEvent target, LineEvent edited,
            EditHistory.Command baseCommand);
}
