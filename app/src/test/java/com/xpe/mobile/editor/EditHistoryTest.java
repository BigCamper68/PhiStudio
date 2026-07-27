package com.xpe.mobile.editor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class EditHistoryTest {
    @Test
    public void undoRedoAndNewCommandMaintainStackRules() {
        EditHistory history = new EditHistory(2);
        int[] value = {0};
        history.execute(command(value, 1));
        history.execute(command(value, 2));
        assertEquals(2, value[0]);

        history.undo();
        assertEquals(1, value[0]);
        assertTrue(history.canRedo());

        history.execute(command(value, 3));
        assertEquals(3, value[0]);
        assertFalse(history.canRedo());
        history.undo();
        history.undo();
        assertEquals(0, value[0]);
    }

    private static EditHistory.Command command(int[] value, int next) {
        int before = value[0];
        return new EditHistory.Command() {
            @Override
            public void apply() {
                value[0] = next;
            }

            @Override
            public void revert() {
                value[0] = before;
            }
        };
    }
}
