package com.xpe.mobile.editor;

import com.xpe.mobile.i18n.ModelLabels;

import android.graphics.Canvas;
import android.graphics.Color;
import com.xpe.mobile.R;
import com.xpe.mobile.editor.EditorChromeView.BottomAction;
import com.xpe.mobile.editor.EditorChromeView.BottomPanel;
import com.xpe.mobile.editor.EditorSurfaceView.RectangleSelectionMode;
import com.xpe.mobile.editor.EditorSurfaceView.Tool;
import java.util.ArrayList;
import java.util.List;

/** EditorBottomBarUi responsibilities extracted from EditorChromeView. */
final class EditorBottomBarUi {
    private final EditorChromeView delegateOwner;

    EditorBottomBarUi(EditorChromeView delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void drawBottomBar(Canvas canvas) {
        float top = delegateOwner.getHeight() - delegateOwner.bottomBarHeight();
        float right = delegateOwner.editorSplitX();
        delegateOwner.paint.setColor(EditorChromeView.TOOLBAR_COLOR);
        canvas.drawRect(0, top, delegateOwner.getWidth(), delegateOwner.getHeight(), delegateOwner.paint);
        int save = canvas.save();
        canvas.clipRect(0, top, right, delegateOwner.getHeight());
        float x = delegateOwner.editorBottomBarUi.drawBottomTab(canvas, R.string.controls_panel_create, BottomPanel.CREATE,
                6f * delegateOwner.density, top + 5f * delegateOwner.density);
        x = delegateOwner.editorBottomBarUi.drawBottomTab(canvas, R.string.controls_panel_edit, BottomPanel.EDIT, x, top + 5f * delegateOwner.density);
        x = delegateOwner.editorBottomBarUi.drawBottomTab(canvas, R.string.controls_panel_arrange, BottomPanel.ARRANGE, x, top + 5f * delegateOwner.density);
        delegateOwner.paint.setColor(Color.LTGRAY);
        delegateOwner.paint.setTextSize(10f * delegateOwner.density);
        String summary = delegateOwner.getResources().getString(R.string.controls_status_summary,
                delegateOwner.editorBottomBarUi.currentToolLabel(), delegateOwner.lineIndex, delegateOwner.eventLayerIndex, delegateOwner.subdivision, delegateOwner.verticalGridLines);
        canvas.drawText(delegateOwner.ellipsize(summary, Math.max(0, right - x - 12f * delegateOwner.density)),
                x + 4f * delegateOwner.density, top + 22f * delegateOwner.density, delegateOwner.paint);

        List<BottomAction> actions = delegateOwner.editorBottomBarUi.bottomActions();
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        float total = 0;
        boolean explicitPages = false;
        for (BottomAction action : actions) {
            total += action.width + 4f * delegateOwner.density;
            explicitPages |= action.startNewPage;
        }
        float budget = right - 12f * delegateOwner.density
                - (explicitPages || total > right - 12f * delegateOwner.density ? 84f * delegateOwner.density : 0);
        // Fit each explicitly grouped Arrange page before applying overflow pagination.
        // Keep both X grid controls together on page two at every supported density.
        float widthScale = 1f;
        if (delegateOwner.bottomPanel == BottomPanel.ARRANGE) {
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
            widthScale = Math.min(1f, Math.max(0, budget - largestCount * 4f * delegateOwner.density) / largestGroup);
        }
        float used = 0;
        for (int index = 0; index < actions.size(); index++) {
            float size = actions.get(index).width * widthScale + 4f * delegateOwner.density;
            if (used > 0 && (actions.get(index).startNewPage || used + size > budget + .01f)) {
                starts.add(index); used = 0;
            }
            used += size;
        }
        int pages = starts.size();
        delegateOwner.bottomActionPage = Math.max(0, Math.min(pages - 1, delegateOwner.bottomActionPage));
        int first = starts.get(delegateOwner.bottomActionPage);
        int last = delegateOwner.bottomActionPage + 1 < pages ? starts.get(delegateOwner.bottomActionPage + 1) : actions.size();
        float actionY = top + 36f * delegateOwner.density;
        x = 6f * delegateOwner.density;
        for (int index = first; index < last; index++) {
            BottomAction action = actions.get(index);
            x = delegateOwner.editorToolbarUi.addButton(canvas, action.label, x, actionY,
                    Math.min(action.width * widthScale, budget - 4f * delegateOwner.density), 34f * delegateOwner.density,
                    action.action, action.active, action.enabled);
        }
        if (pages > 1) {
            // Keep arrows after the actions, moving only the unused gap out of the way.
            float arrowsX = Math.min(x + 4f * delegateOwner.density, right - 80f * delegateOwner.density);
            delegateOwner.editorToolbarUi.addButton(canvas, "‹", arrowsX, actionY, 34f * delegateOwner.density, 34f * delegateOwner.density,
                    () -> delegateOwner.changeBottomPage(-1), false, delegateOwner.bottomActionPage > 0);
            delegateOwner.editorToolbarUi.addButton(canvas, "›", arrowsX + 38f * delegateOwner.density, actionY, 34f * delegateOwner.density, 34f * delegateOwner.density,
                    () -> delegateOwner.changeBottomPage(1), false, delegateOwner.bottomActionPage + 1 < pages);
        }
        canvas.restoreToCount(save);
    }

    float drawBottomTab(Canvas canvas, int textResource, BottomPanel panel,
                                float x, float y) {
        return delegateOwner.editorToolbarUi.addButton(canvas, delegateOwner.getResources().getString(textResource), x, y,
                68f * delegateOwner.density, 26f * delegateOwner.density, () -> {
                    delegateOwner.bottomPanel = panel;
                    delegateOwner.bottomActionPage = 0;
                }, delegateOwner.bottomPanel == panel);
    }

    List<BottomAction> bottomActions() {
        List<BottomAction> actions = new ArrayList<>();
        if (delegateOwner.workbenchTab == 1 && delegateOwner.bottomPanel == BottomPanel.EDIT
                && delegateOwner.selectedNote == null && delegateOwner.selectedEvent == null) {
            boolean box = delegateOwner.rectangleSelectionMode == RectangleSelectionMode.ADD;
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(box ? R.string.controls_box_add_on : R.string.controls_box_add_off,
                    68, delegateOwner::toggleRectangleSelection, box));
            if (delegateOwner.storyboardTimeline.selectionCount() == 1) {
                actions.add(delegateOwner.editorBottomBarUi.bottomTextAction("Properties", 76, delegateOwner.storyboardTimeline::edit));
                actions.add(delegateOwner.editorBottomBarUi.bottomTextAction("Split", 54, delegateOwner.storyboardTimeline::split));
                actions.add(delegateOwner.editorBottomBarUi.bottomTextAction("Glue", 54, delegateOwner.storyboardTimeline::glue));
            }
            if (delegateOwner.storyboardTimeline.selectionCount() > 0) {
                actions.add(delegateOwner.editorBottomBarUi.bottomTextAction("Delete", 60, delegateOwner.storyboardTimeline::delete));
                actions.add(delegateOwner.editorBottomBarUi.bottomTextAction("Copy", 54, delegateOwner.storyboardTimeline::copy));
                actions.add(delegateOwner.editorBottomBarUi.bottomTextAction("Cut", 50, delegateOwner.storyboardTimeline::cut));
            }
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_paste, 58, delegateOwner.storyboardTimeline::paste,
                    false, delegateOwner.storyboardTimeline.hasClipboard()));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_clear, 56, delegateOwner.storyboardTimeline::clearSelection,
                    false, delegateOwner.storyboardTimeline.selectionCount() > 0));
            return actions;
        }
        switch (delegateOwner.bottomPanel) {
            case CREATE:
                actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_select, 58f,
                        () -> delegateOwner.selectTool(Tool.SELECT), delegateOwner.tool == Tool.SELECT));
                actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_tap, 54f,
                        () -> delegateOwner.selectTool(Tool.TAP), delegateOwner.tool == Tool.TAP));
                actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_drag, 56f,
                        () -> delegateOwner.selectTool(Tool.DRAG), delegateOwner.tool == Tool.DRAG));
                actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_flick, 56f,
                        () -> delegateOwner.selectTool(Tool.FLICK), delegateOwner.tool == Tool.FLICK));
                actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_hold, 56f,
                        () -> delegateOwner.selectTool(Tool.HOLD), delegateOwner.tool == Tool.HOLD));
                actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_event, 58f,
                        () -> delegateOwner.selectTool(Tool.EVENT), delegateOwner.tool == Tool.EVENT));
                break;
            case EDIT:
                delegateOwner.editorBottomBarUi.addEditActions(actions);
                break;
            case ARRANGE:
                actions.add(delegateOwner.editorBottomBarUi.bottomAction(delegateOwner.xyBindingEnabled
                                ? R.string.xy_binding_button_on : R.string.xy_binding_button_off,
                        58f, delegateOwner::toggleXYBinding, delegateOwner.xyBindingEnabled));
                actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_layer_decrease, 66f,
                        () -> delegateOwner.changeLayer(-1), false, delegateOwner.eventLayerIndex > 0));
                actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_layer_increase, 66f,
                        () -> delegateOwner.changeLayer(1), false, delegateOwner.eventLayerIndex < 3));
                actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_line_decrease, 64f,
                        () -> delegateOwner.changeLine(-1), false, delegateOwner.lineIndex > 0));
                actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_line_increase, 64f,
                        () -> delegateOwner.changeLine(1), false,
                        delegateOwner.chart != null && delegateOwner.lineIndex + 1 < delegateOwner.chart.judgeLines.size()));
                actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_x_grid_decrease, 70f,
                        () -> delegateOwner.changeVerticalGrid(-1)).nextPage());
                actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_x_grid_increase, 70f,
                        () -> delegateOwner.changeVerticalGrid(1)));
                actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_beat_grid_decrease, 80f,
                        () -> delegateOwner.changeSubdivision(-1)));
                actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_beat_grid_increase, 80f,
                        () -> delegateOwner.changeSubdivision(1)));
                break;
        }
        return actions;
    }

    void addEditActions(List<BottomAction> actions) {
        boolean clipboardReady = delegateOwner.clipboard != null && !delegateOwner.clipboard.isEmpty();
        boolean boxSelection = delegateOwner.rectangleSelectionMode == RectangleSelectionMode.ADD;
        actions.add(delegateOwner.editorBottomBarUi.bottomAction(boxSelection
                        ? R.string.controls_box_add_on : R.string.controls_box_add_off,
                68f, delegateOwner::toggleRectangleSelection, boxSelection));
        actions.add(delegateOwner.editorBottomBarUi.bottomAction(delegateOwner.editorBottomBarUi.noteMoveModeLabel(), 72f,
                delegateOwner::cycleNoteMoveMode, delegateOwner.noteMoveMode.enabled()));
        if (delegateOwner.selectionCount() > 1) {
            boolean hasNotes = !delegateOwner.getSelectedNotesForBatch().isEmpty();
            boolean hasEvents = !delegateOwner.getSelectedEventsForBatch().isEmpty();
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_batch_edit, 74f,
                    () -> delegateOwner.callback.requestAdvancedBatchEdit(), false,
                    hasNotes || hasEvents));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_event_clone, 76f,
                    () -> delegateOwner.callback.requestEventClone(), false, hasEvents));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_delete, 62f, delegateOwner::deleteSelection));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_copy, 56f, delegateOwner::copySelection));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_cut, 50f, delegateOwner::cutSelection));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_paste, 58f,
                    () -> delegateOwner.pasteClipboard(false), false, clipboardReady));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_mirror_paste, 88f,
                    () -> delegateOwner.pasteClipboard(true), false, clipboardReady));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_clear, 56f, delegateOwner::clearSelection));
            return;
        }
        if (delegateOwner.selectedNote != null) {
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.properties_button, 72f,
                    () -> delegateOwner.callback.requestEditNoteProperties(delegateOwner.selectedNote)));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_delete, 62f, delegateOwner::deleteSelection));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_copy, 56f, delegateOwner::copySelection));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_cut, 50f, delegateOwner::cutSelection));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_mirror_x, 70f, delegateOwner::mirrorSelectedNote));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(delegateOwner.selectedNote.above == 1
                            ? R.string.controls_side_up : R.string.controls_side_down,
                    70f, delegateOwner::switchSelectedNoteSide));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(delegateOwner.selectedNote.fake
                            ? R.string.controls_fake_checked : R.string.controls_fake,
                    60f, delegateOwner::toggleSelectedNoteFake, delegateOwner.selectedNote.fake));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_width_decrease, 70f,
                    () -> delegateOwner.resizeSelectedNote(-0.1)));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_width_increase, 70f,
                    () -> delegateOwner.resizeSelectedNote(0.1)));
            return;
        }
        if (delegateOwner.selectedEvent != null) {
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.properties_button, 72f,
                    () -> delegateOwner.callback.requestEditEventProperties(delegateOwner.selectedEvent)));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.event_split_button, 58f, delegateOwner::splitSelectedEvent));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.event_pass_button, 56f, delegateOwner::passSelectedEvent));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.event_rand_button, 56f, delegateOwner::randomizeSelectedEvent));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.event_glue_button, 58f, delegateOwner::glueSelectedEvent));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_delete, 62f, delegateOwner::deleteSelection));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_copy, 56f, delegateOwner::copySelection));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_cut, 50f, delegateOwner::cutSelection));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_event_start_decrease, 68f,
                    () -> delegateOwner.adjustSelectedEventValue(true, -EditorChromeView.eventStep(delegateOwner.selectedEvent.type))));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_event_start_increase, 68f,
                    () -> delegateOwner.adjustSelectedEventValue(true, EditorChromeView.eventStep(delegateOwner.selectedEvent.type))));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_event_end_decrease, 62f,
                    () -> delegateOwner.adjustSelectedEventValue(false, -EditorChromeView.eventStep(delegateOwner.selectedEvent.type))));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_event_end_increase, 62f,
                    () -> delegateOwner.adjustSelectedEventValue(false, EditorChromeView.eventStep(delegateOwner.selectedEvent.type))));
            actions.add(delegateOwner.editorBottomBarUi.bottomTextAction(delegateOwner.getResources().getString(R.string.controls_event_ease,
                            delegateOwner.eventEasingLabel(delegateOwner.selectedEvent)),
                    112f, () -> delegateOwner.callback.requestEditEventProperties(delegateOwner.selectedEvent)));
            actions.add(delegateOwner.editorBottomBarUi.bottomAction(delegateOwner.selectedEvent.linkGroup == 0
                            ? R.string.controls_event_link : R.string.controls_event_linked,
                    64f, delegateOwner::toggleSelectedEventLink, delegateOwner.selectedEvent.linkGroup != 0));
            return;
        }
        actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_paste, 58f,
                () -> delegateOwner.pasteClipboard(false), false, clipboardReady));
        actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_mirror_paste, 88f,
                () -> delegateOwner.pasteClipboard(true), false, clipboardReady));
        actions.add(delegateOwner.editorBottomBarUi.bottomAction(R.string.controls_clear, 56f,
                delegateOwner::clearSelection, false, delegateOwner.hasSelection()));
    }

    BottomAction bottomAction(int textResource, float widthDp, Runnable action) {
        return delegateOwner.editorBottomBarUi.bottomAction(textResource, widthDp, action, false, true);
    }

    BottomAction bottomAction(int textResource, float widthDp, Runnable action,
                                      boolean active) {
        return delegateOwner.editorBottomBarUi.bottomAction(textResource, widthDp, action, active, true);
    }

    BottomAction bottomAction(int textResource, float widthDp, Runnable action,
                                      boolean active, boolean enabled) {
        return new BottomAction(delegateOwner.getResources().getString(textResource), widthDp * delegateOwner.density,
                action, active, enabled);
    }

    BottomAction bottomTextAction(String text, float widthDp, Runnable action) {
        return new BottomAction(text, widthDp * delegateOwner.density, action, false, true);
    }

    int noteMoveModeLabel() {
        switch (delegateOwner.noteMoveMode) {
            case FREE: return R.string.controls_move_xy;
            case X_ONLY: return R.string.controls_move_x;
            case Y_ONLY: return R.string.controls_move_y;
            case OFF:
            default: return R.string.controls_move_off;
        }
    }

    String currentToolLabel() {
        switch (delegateOwner.tool) {
            case TAP: return delegateOwner.getResources().getString(R.string.controls_tap);
            case DRAG: return delegateOwner.getResources().getString(R.string.controls_drag);
            case FLICK: return delegateOwner.getResources().getString(R.string.controls_flick);
            case HOLD: return delegateOwner.getResources().getString(R.string.controls_hold);
            case EVENT: return delegateOwner.getResources().getString(R.string.controls_event);
            default: return delegateOwner.getResources().getString(R.string.controls_select);
        }
    }

    String bottomContextSummary() {
        if (delegateOwner.workbenchTab == 1 && delegateOwner.storyboardTimeline.selected() != null)
            return delegateOwner.storyboardTimeline.selectionSummary();
        if (delegateOwner.selectedNote != null) {
            return delegateOwner.getResources().getString(R.string.controls_note_summary,
                    delegateOwner.selectedNote.type.toString(), delegateOwner.selectedNote.startTime.toString(),
                    EditorChromeView.compactValue(delegateOwner.selectedNote.positionX));
        }
        if (delegateOwner.selectedEvent != null) {
            return delegateOwner.getResources().getString(R.string.controls_event_summary,
                    ModelLabels.event(delegateOwner.getContext(), delegateOwner.selectedEvent.type), delegateOwner.selectedEvent.startTime.toString(),
                    delegateOwner.selectedEvent.endTime.toString(), EditorChromeView.compactValue(delegateOwner.selectedEvent.start),
                    EditorChromeView.compactValue(delegateOwner.selectedEvent.end));
        }
        int count = delegateOwner.selectionCount();
        return count > 0
                ? delegateOwner.getResources().getQuantityString(
                R.plurals.controls_selection_count, count, count)
                : delegateOwner.getResources().getString(R.string.controls_no_selection);
    }

    String bottomPanelHint() {
        switch (delegateOwner.bottomPanel) {
            case EDIT: return delegateOwner.getResources().getString(R.string.controls_hint_edit);
            case ARRANGE: return delegateOwner.getResources().getString(R.string.controls_hint_arrange);
            default: return delegateOwner.getResources().getString(R.string.controls_hint_create);
        }
    }
}
