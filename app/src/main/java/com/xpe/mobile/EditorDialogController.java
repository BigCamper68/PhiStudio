package com.xpe.mobile;

import com.xpe.mobile.i18n.LanguageController;

import android.app.Activity;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import java.util.List;

final class EditorDialogController extends EditorDialogSection {
    private final BpmListDialog bpmListDialog = new BpmListDialog(this);
    final ChartDiagnosticsDialog chartDiagnosticsDialog = new ChartDiagnosticsDialog(this);
    private final ChartMetadataDialog chartMetadataDialog = new ChartMetadataDialog(this);
    private final EditorSettingsDialog editorSettingsDialog = new EditorSettingsDialog(this);
    private final EventPropertyDialog eventPropertyDialog = new EventPropertyDialog(this);
    final JudgeLineDialogs judgeLineDialogs = new JudgeLineDialogs(this);
    private final NotePropertyDialog notePropertyDialog = new NotePropertyDialog(this);

    interface Host {
        default LanguageController languageController() { return null; }
        WorkbenchView workbench();
        EditorSettings editorSettings();

        void applyEditorSettings(EditorSettings settings);

        long packageOffsetMs();

        long audioDurationMs();

        void onLineAppearanceChanged();
        List<String> hitSoundChoices();
        boolean isHitSoundAvailable(String reference);

        void showMessage(String message);
    }

    private final StoryboardDialogSection storyboardDialogs;
    private final EditorOperationDialogSection operationDialogs;

    EditorDialogController(Activity activity, EditorView editorView, Host host) {
        super(activity, editorView, host);
        storyboardDialogs = new StoryboardDialogSection(activity, editorView, host);
        operationDialogs = new EditorOperationDialogSection(activity, editorView, host);
        host.workbench().setSelectionPropertiesOpener(target -> {
            if (target instanceof Note) showNoteProperties((Note) target);
            else if (target instanceof LineEvent) showEventProperties((LineEvent) target);
            else if (target instanceof com.xpe.mobile.model.ExtendedLineEvents.TimedEvent) {
                for (com.xpe.mobile.model.StoryboardEventType type
                        : com.xpe.mobile.model.StoryboardEventType.editableTypes()) {
                    com.xpe.mobile.model.ExtendedLineEvents.TimedEvent event =
                            (com.xpe.mobile.model.ExtendedLineEvents.TimedEvent) target;
                    if (editorView.getCurrentJudgeLine().extended.contains(type, event)) {
                        showStoryboardProperties(type, event, false);
                        break;
                    }
                }
            }
        });
    }

    public void showMetadata() {
        chartMetadataDialog.showMetadata();
    }

    public void showBpmList() {
        bpmListDialog.showBpmList();
    }

    public void showStoryboardEditor() {
        storyboardDialogs.showStoryboardEditor();
    }

    public void showStoryboardProperties(com.xpe.mobile.model.StoryboardEventType type,
            com.xpe.mobile.model.ExtendedLineEvents.TimedEvent source, boolean creating) {
        storyboardDialogs.showStoryboardEventForm(type, source, creating, editorView::invalidate);
    }

    public void showSettings() {
        editorSettingsDialog.showSettings();
    }

    public void showAdvancedBatchEdit() {
        operationDialogs.showAdvancedBatchEdit();
    }

    public void showEventClone() {
        operationDialogs.showEventClone();
    }

    public void showComplexMove() {
        operationDialogs.showComplexMove();
    }

    public void showCurveNotes() {
        operationDialogs.showCurveNotes();
    }

    public void showChartDiagnostics() {
        chartDiagnosticsDialog.showChartDiagnostics();
    }

    public void showLineManager() {
        judgeLineDialogs.showLineManager();
    }

    public void showNoteProperties(Note note) {
        notePropertyDialog.showNoteProperties(note);
    }

    public void showEventProperties(LineEvent event) {
        eventPropertyDialog.showEventProperties(event);
    }

}
