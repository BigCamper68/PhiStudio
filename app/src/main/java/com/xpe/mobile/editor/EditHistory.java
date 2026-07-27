package com.xpe.mobile.editor;

import java.util.ArrayDeque;
import java.util.Deque;

public final class EditHistory {
    public interface Command {
        void apply();
        void revert();
    }

    private final Deque<Command> undo = new ArrayDeque<>();
    private final Deque<Command> redo = new ArrayDeque<>();
    private final int limit;
    private final Runnable mutationListener;

    public EditHistory(int limit) {
        this(limit, null);
    }

    public EditHistory(int limit, Runnable mutationListener) {
        this.limit = Math.max(1, limit);
        this.mutationListener = mutationListener;
    }

    public void execute(Command command) {
        command.apply();
        undo.push(command);
        redo.clear();
        while (undo.size() > limit) undo.removeLast();
        notifyMutation();
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public void undo() {
        if (undo.isEmpty()) return;
        Command command = undo.pop();
        command.revert();
        redo.push(command);
        notifyMutation();
    }

    public void redo() {
        if (redo.isEmpty()) return;
        Command command = redo.pop();
        command.apply();
        undo.push(command);
        notifyMutation();
    }

    public void clear() {
        undo.clear();
        redo.clear();
    }

    private void notifyMutation() {
        if (mutationListener != null) mutationListener.run();
    }
}
