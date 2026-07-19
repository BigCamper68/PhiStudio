package com.xpe.mobile.editor;

import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;

import java.util.ArrayList;
import java.util.List;

/** Atomic history command for all intermediate notes created by Curve Notes. */
public final class CurveNoteCommand {
    private CurveNoteCommand() {
    }

    public static EditHistory.Command add(JudgeLine line, List<Note> generated) {
        List<Note> notes = new ArrayList<>(generated);
        return new EditHistory.Command() {
            @Override
            public void apply() {
                for (Note note : notes) if (!line.notes.contains(note)) line.notes.add(note);
                line.sortNotes();
            }

            @Override
            public void revert() {
                line.notes.removeAll(notes);
            }
        };
    }
}
