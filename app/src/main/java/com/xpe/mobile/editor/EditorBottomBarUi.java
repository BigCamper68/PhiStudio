package com.xpe.mobile.editor;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.OverScroller;
import com.xpe.mobile.R;
import com.xpe.mobile.editor.EditorChromeView.BottomAction;
import com.xpe.mobile.editor.EditorChromeView.BottomPanel;
import com.xpe.mobile.editor.EditorChromeView.ButtonSpec;
import com.xpe.mobile.editor.EditorSurfaceView.RectangleSelectionMode;
import com.xpe.mobile.editor.EditorSurfaceView.Tool;
import java.util.ArrayList;
import java.util.List;

/** Fixed tabs above a horizontally scrollable row of editor actions. */
final class EditorBottomBarUi {
    private final EditorChromeView delegateOwner;
    private final OverScroller scroller;
    private final int touchSlop, minimumFling, maximumFling;
    private float scrollOffset, maxScroll, contentWidth;
    private float downX, downY, initialScroll;
    private boolean tracking, dragging, cancelled;
    private VelocityTracker velocity;
    private Runnable pendingAction;
    private RectF pendingBounds;

    EditorBottomBarUi(EditorChromeView delegateOwner) {
        this.delegateOwner = delegateOwner;
        scroller = new OverScroller(delegateOwner.getContext());
        ViewConfiguration config = ViewConfiguration.get(delegateOwner.getContext());
        touchSlop = config.getScaledTouchSlop();
        minimumFling = config.getScaledMinimumFlingVelocity();
        maximumFling = config.getScaledMaximumFlingVelocity();
    }

    void resetScroll() { cancelTouch(); scrollOffset = 0; }

    void cancelTouch() {
        tracking = false;
        pendingAction = null;
        pendingBounds = null;
        if (velocity != null) { velocity.recycle(); velocity = null; }
        scroller.forceFinished(true);
    }

    private float clamp(float value) { return Math.max(0, Math.min(maxScroll, value)); }

    private void updateMetrics(List<BottomAction> actions) {
        contentWidth = 0;
        for (BottomAction action : actions) contentWidth += action.width + 4 * delegateOwner.density;
        if (!actions.isEmpty()) contentWidth -= 4 * delegateOwner.density;
        float nextMax = Math.max(0, contentWidth - Math.max(0,
                delegateOwner.editorSplitX() - 12 * delegateOwner.density));
        if (nextMax != maxScroll) scroller.forceFinished(true);
        maxScroll = nextMax;
        scrollOffset = clamp(scrollOffset);
    }

    private boolean isActionRow(float x, float y) {
        float top = delegateOwner.getHeight() - delegateOwner.bottomBarHeight();
        return !delegateOwner.previewMode && x >= 0 && x < delegateOwner.editorSplitX()
                && y >= top + 32 * delegateOwner.density && y < delegateOwner.getHeight();
    }

    /** Captures the row before the ordinary editor button/grid handlers see the gesture. */
    boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        float x = event.getX(), y = event.getY();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!isActionRow(x, y)) return false;
            boolean wasFlinging = !scroller.isFinished();
            if (scroller.computeScrollOffset()) scrollOffset = clamp(scroller.getCurrX());
            cancelTouch();
            List<BottomAction> actions = bottomActions();
            updateMetrics(actions);
            downX = x; downY = y; initialScroll = scrollOffset;
            tracking = true; dragging = false; cancelled = false;
            delegateOwner.gestureMode = EditorSurfaceView.GestureMode.NONE;
            velocity = VelocityTracker.obtain();
            velocity.addMovement(event);
            float left = 6 * delegateOwner.density - scrollOffset;
            float top = delegateOwner.getHeight() - delegateOwner.bottomBarHeight() + 36 * delegateOwner.density;
            RectF viewport = new RectF(6 * delegateOwner.density, top,
                    delegateOwner.editorSplitX() - 6 * delegateOwner.density, top + 34 * delegateOwner.density);
            for (BottomAction item : actions) {
                RectF bounds = new RectF(left, top, left + item.width, viewport.bottom);
                if (!wasFlinging && item.enabled && bounds.intersect(viewport) && bounds.contains(x, y)) {
                    pendingAction = item.action; pendingBounds = bounds;
                    break;
                }
                left += item.width + 4 * delegateOwner.density;
            }
            return true;
        }
        if (!tracking) return false;
        if (velocity != null) velocity.addMovement(event);
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            cancelled = true; pendingAction = null;
        } else if (action == MotionEvent.ACTION_MOVE && !cancelled) {
            float dx = x - downX, dy = y - downY;
            if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) >= Math.abs(dy)) {
                dragging = true; pendingAction = null;
            } else if (!dragging && Math.abs(dy) > touchSlop) {
                cancelled = true; pendingAction = null;
            }
            if (dragging) {
                scrollOffset = clamp(initialScroll - dx);
                delegateOwner.invalidate();
            }
        } else if (action == MotionEvent.ACTION_UP) {
            Runnable tap = !cancelled && !dragging && Math.abs(x - downX) <= touchSlop
                    && Math.abs(y - downY) <= touchSlop && pendingBounds != null
                    && pendingBounds.contains(x, y) && isActionRow(x, y) ? pendingAction : null;
            if (dragging && !cancelled && velocity != null) {
                scrollOffset = clamp(initialScroll - (x - downX));
                velocity.computeCurrentVelocity(1000, maximumFling);
                int speed = Math.round(-velocity.getXVelocity());
                if (Math.abs(speed) >= minimumFling) {
                    scroller.fling(Math.round(scrollOffset), 0, speed, 0, 0, Math.round(maxScroll), 0, 0);
                }
            }
            tracking = false; pendingAction = null; pendingBounds = null;
            if (velocity != null) { velocity.recycle(); velocity = null; }
            if (tap != null) { tap.run(); delegateOwner.performClick(); }
            delegateOwner.postInvalidateOnAnimation();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            cancelTouch();
            delegateOwner.invalidate();
        }
        return true;
    }

    void drawBottomBar(Canvas canvas) {
        float top = delegateOwner.getHeight() - delegateOwner.bottomBarHeight();
        float right = delegateOwner.editorSplitX();
        delegateOwner.paint.setColor(EditorChromeView.TOOLBAR_COLOR);
        canvas.drawRect(0, top, delegateOwner.getWidth(), delegateOwner.getHeight(), delegateOwner.paint);
        int save = canvas.save();
        canvas.clipRect(0, top, right, delegateOwner.getHeight());
        float x = drawBottomTab(canvas, R.string.controls_panel_create, BottomPanel.CREATE,
                6 * delegateOwner.density, top + 5 * delegateOwner.density);
        x = drawBottomTab(canvas, R.string.controls_panel_edit, BottomPanel.EDIT, x, top + 5 * delegateOwner.density);
        x = drawBottomTab(canvas, R.string.controls_panel_arrange, BottomPanel.ARRANGE, x, top + 5 * delegateOwner.density);
        delegateOwner.paint.setColor(Color.LTGRAY);
        delegateOwner.paint.setTextSize(10 * delegateOwner.density);
        String summary = delegateOwner.getResources().getString(R.string.controls_status_summary,
                currentToolLabel(), delegateOwner.lineIndex, delegateOwner.eventLayerIndex,
                delegateOwner.subdivision, delegateOwner.verticalGridLines);
        canvas.drawText(delegateOwner.ellipsize(summary, Math.max(0, right - x - 12 * delegateOwner.density)),
                x + 4 * delegateOwner.density, top + 22 * delegateOwner.density, delegateOwner.paint);
        List<BottomAction> actions = bottomActions();
        updateMetrics(actions);
        if (!scroller.isFinished()) {
            scroller.computeScrollOffset();
            scrollOffset = clamp(scroller.getCurrX());
            delegateOwner.postInvalidateOnAnimation();
        }
        float actionY = top + 36 * delegateOwner.density;
        RectF viewport = new RectF(6 * delegateOwner.density, actionY,
                right - 6 * delegateOwner.density, actionY + 34 * delegateOwner.density);
        int rowSave = canvas.save();
        canvas.clipRect(viewport);
        x = viewport.left - scrollOffset;
        for (BottomAction action : actions) {
            if (x + action.width > viewport.left && x < viewport.right) {
                int count = delegateOwner.buttons.size();
                delegateOwner.editorToolbarUi.addButton(canvas, action.label, x, actionY, action.width,
                        viewport.height(), action.action, action.active, action.enabled);
                if (delegateOwner.buttons.size() > count) {
                    ButtonSpec button = delegateOwner.buttons.remove(count);
                    RectF clipped = new RectF(button.bounds);
                    if (clipped.intersect(viewport)) delegateOwner.buttons.add(new ButtonSpec(clipped, button.action));
                }
            }
            x += action.width + 4 * delegateOwner.density;
        }
        canvas.restoreToCount(rowSave);
        if (maxScroll > 0 && viewport.width() > 0) {
            float width = Math.max(24 * delegateOwner.density, viewport.width() * viewport.width() / contentWidth);
            float left = viewport.left + (viewport.width() - width) * scrollOffset / maxScroll;
            delegateOwner.paint.setColor(Color.rgb(94, 111, 124));
            canvas.drawRoundRect(new RectF(left, top + 73 * delegateOwner.density,
                    left + width, top + 75 * delegateOwner.density), delegateOwner.density,
                    delegateOwner.density, delegateOwner.paint);
        }
        canvas.restoreToCount(save);
    }

    float drawBottomTab(Canvas canvas, int textResource, BottomPanel panel, float x, float y) {
        return delegateOwner.editorToolbarUi.addButton(canvas, delegateOwner.getResources().getString(textResource),
                x, y, 68 * delegateOwner.density, 26 * delegateOwner.density, () -> {
                    delegateOwner.bottomPanel = panel;
                    resetScroll();
                }, delegateOwner.bottomPanel == panel);
    }

    List<BottomAction> bottomActions() {
        List<BottomAction> actions = new ArrayList<>();
        if (delegateOwner.workbenchTab == 1 && delegateOwner.bottomPanel == BottomPanel.EDIT
                && delegateOwner.selectedNote == null && delegateOwner.selectedEvent == null) {
            int selected = delegateOwner.storyboardTimeline.selectionCount();
            if (selected == 1) actions.add(bottomTextAction("Properties", 76, delegateOwner.storyboardTimeline::edit));
            if (selected > 0) actions.add(bottomTextAction("Copy", 54, delegateOwner.storyboardTimeline::copy));
            actions.add(bottomAction(R.string.controls_paste, 58, delegateOwner.storyboardTimeline::paste,
                    false, delegateOwner.storyboardTimeline.hasClipboard()));
            addBoxAction(actions);
            if (selected == 1) {
                actions.add(bottomTextAction("Split", 54, delegateOwner.storyboardTimeline::split));
                actions.add(bottomTextAction("Glue", 54, delegateOwner.storyboardTimeline::glue));
            }
            if (selected > 0) {
                actions.add(bottomTextAction("Cut", 50, delegateOwner.storyboardTimeline::cut));
                actions.add(bottomTextAction("Delete", 60, delegateOwner.storyboardTimeline::delete));
            }
            actions.add(bottomAction(R.string.controls_clear, 56, delegateOwner.storyboardTimeline::clearSelection,
                    false, selected > 0));
            return actions;
        }
        switch (delegateOwner.bottomPanel) {
            case CREATE:
                addTool(actions, R.string.controls_tap, 54, Tool.TAP);
                addTool(actions, R.string.controls_hold, 56, Tool.HOLD);
                addTool(actions, R.string.controls_drag, 56, Tool.DRAG);
                addTool(actions, R.string.controls_flick, 56, Tool.FLICK);
                addTool(actions, R.string.controls_event, 58, Tool.EVENT);
                addTool(actions, R.string.controls_select, 58, Tool.SELECT);
                break;
            case EDIT:
                addEditActions(actions);
                break;
            case ARRANGE:
                actions.add(bottomAction(R.string.controls_beat_grid_decrease, 80, () -> delegateOwner.changeSubdivision(-1)));
                actions.add(bottomAction(R.string.controls_beat_grid_increase, 80, () -> delegateOwner.changeSubdivision(1)));
                actions.add(bottomAction(R.string.controls_line_decrease, 64, () -> delegateOwner.changeLine(-1),
                        false, delegateOwner.lineIndex > 0));
                actions.add(bottomAction(R.string.controls_line_increase, 64, () -> delegateOwner.changeLine(1),
                        false, delegateOwner.chart != null && delegateOwner.lineIndex + 1 < delegateOwner.chart.judgeLines.size()));
                actions.add(bottomAction(R.string.controls_layer_decrease, 66, () -> delegateOwner.changeLayer(-1),
                        false, delegateOwner.eventLayerIndex > 0));
                actions.add(bottomAction(R.string.controls_layer_increase, 66, () -> delegateOwner.changeLayer(1),
                        false, delegateOwner.eventLayerIndex < 3));
                actions.add(bottomAction(R.string.controls_x_grid_decrease, 70, () -> delegateOwner.changeVerticalGrid(-1)));
                actions.add(bottomAction(R.string.controls_x_grid_increase, 70, () -> delegateOwner.changeVerticalGrid(1)));
                actions.add(bottomAction(delegateOwner.xyBindingEnabled ? R.string.xy_binding_button_on : R.string.xy_binding_button_off,
                        58, delegateOwner::toggleXYBinding, delegateOwner.xyBindingEnabled));
                break;
        }
        return actions;
    }

    private void addTool(List<BottomAction> actions, int label, float width, Tool tool) {
        actions.add(bottomAction(label, width, () -> delegateOwner.selectTool(tool), delegateOwner.tool == tool));
    }

    private void addBoxAction(List<BottomAction> actions) {
        boolean box = delegateOwner.rectangleSelectionMode == RectangleSelectionMode.ADD;
        actions.add(bottomAction(box ? R.string.controls_box_add_on : R.string.controls_box_add_off,
                68, delegateOwner::toggleRectangleSelection, box));
    }

    void addEditActions(List<BottomAction> actions) {
        boolean clipboardReady = delegateOwner.clipboard != null && !delegateOwner.clipboard.isEmpty();
        int count = delegateOwner.selectionCount();
        boolean hasNotes = !delegateOwner.getSelectedNotesForBatch().isEmpty();
        boolean hasEvents = !delegateOwner.getSelectedEventsForBatch().isEmpty();
        if (count == 1 && delegateOwner.selectedNote != null) {
            actions.add(bottomAction(R.string.properties_button, 72,
                    () -> delegateOwner.callback.requestEditNoteProperties(delegateOwner.selectedNote)));
        } else if (count == 1 && delegateOwner.selectedEvent != null) {
            actions.add(bottomAction(R.string.properties_button, 72,
                    () -> delegateOwner.callback.requestEditEventProperties(delegateOwner.selectedEvent)));
        }
        if (count > 0) actions.add(bottomAction(R.string.controls_copy, 56, delegateOwner::copySelection));
        actions.add(bottomAction(R.string.controls_paste, 58, () -> delegateOwner.pasteClipboard(false), false, clipboardReady));
        if (count > 0) {
            actions.add(bottomAction(R.string.controls_batch_edit, 74,
                    () -> delegateOwner.callback.requestAdvancedBatchEdit(), false, hasNotes || hasEvents));
            if (hasEvents) actions.add(bottomAction(R.string.event_subdivide_button, 82, delegateOwner::subdivideSelectedEvents));
        }
        addBoxAction(actions);
        if (hasNotes || count == 0) actions.add(bottomAction(noteMoveModeLabel(), 72,
                delegateOwner::cycleNoteMoveMode, delegateOwner.noteMoveMode.enabled()));
        if (count > 0) actions.add(bottomAction(R.string.controls_event_clone, 76,
                () -> delegateOwner.callback.requestEventClone(), false, hasEvents));
        if (count == 1 && delegateOwner.selectedEvent != null) {
            actions.add(bottomAction(R.string.event_split_button, 72, delegateOwner::splitSelectedEvent));
            actions.add(bottomAction(R.string.event_glue_button, 58, delegateOwner::glueSelectedEvent));
        }
        if (count > 0) {
            actions.add(bottomAction(R.string.controls_cut, 50, delegateOwner::cutSelection));
            actions.add(bottomAction(R.string.controls_delete, 62, delegateOwner::deleteSelection));
        }
        actions.add(bottomAction(R.string.controls_mirror_paste, 88,
                () -> delegateOwner.pasteClipboard(true), false, clipboardReady));
        if (count == 1 && delegateOwner.selectedNote != null) {
            actions.add(bottomAction(R.string.controls_mirror_x, 70, delegateOwner::mirrorSelectedNote));
            actions.add(bottomAction(delegateOwner.selectedNote.above == 1 ? R.string.controls_side_up : R.string.controls_side_down,
                    70, delegateOwner::switchSelectedNoteSide));
            actions.add(bottomAction(delegateOwner.selectedNote.fake ? R.string.controls_fake_checked : R.string.controls_fake,
                    60, delegateOwner::toggleSelectedNoteFake, delegateOwner.selectedNote.fake));
            actions.add(bottomAction(R.string.controls_width_decrease, 70, () -> delegateOwner.resizeSelectedNote(-0.1)));
            actions.add(bottomAction(R.string.controls_width_increase, 70, () -> delegateOwner.resizeSelectedNote(0.1)));
        }
        if (count == 1 && delegateOwner.selectedEvent != null) {
            actions.add(bottomAction(R.string.controls_event_start_decrease, 68,
                    () -> delegateOwner.adjustSelectedEventValue(true, -EditorChromeView.eventStep(delegateOwner.selectedEvent.type))));
            actions.add(bottomAction(R.string.controls_event_start_increase, 68,
                    () -> delegateOwner.adjustSelectedEventValue(true, EditorChromeView.eventStep(delegateOwner.selectedEvent.type))));
            actions.add(bottomAction(R.string.controls_event_end_decrease, 62,
                    () -> delegateOwner.adjustSelectedEventValue(false, -EditorChromeView.eventStep(delegateOwner.selectedEvent.type))));
            actions.add(bottomAction(R.string.controls_event_end_increase, 62,
                    () -> delegateOwner.adjustSelectedEventValue(false, EditorChromeView.eventStep(delegateOwner.selectedEvent.type))));
            actions.add(bottomTextAction(delegateOwner.getResources().getString(R.string.controls_event_ease,
                    delegateOwner.eventEasingLabel(delegateOwner.selectedEvent)), 112,
                    () -> delegateOwner.callback.requestEditEventProperties(delegateOwner.selectedEvent)));
            actions.add(bottomAction(delegateOwner.selectedEvent.linkGroup == 0 ? R.string.controls_event_link : R.string.controls_event_linked,
                    64, delegateOwner::toggleSelectedEventLink, delegateOwner.selectedEvent.linkGroup != 0));
            actions.add(bottomAction(R.string.event_pass_button, 56, delegateOwner::passSelectedEvent));
            actions.add(bottomAction(R.string.event_rand_button, 56, delegateOwner::randomizeSelectedEvent));
        }
        actions.add(bottomAction(R.string.controls_clear, 56, delegateOwner::clearSelection, false, count > 0));
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
                    delegateOwner.selectedEvent.type.label, delegateOwner.selectedEvent.startTime.toString(),
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
