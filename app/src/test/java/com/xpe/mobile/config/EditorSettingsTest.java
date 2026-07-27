package com.xpe.mobile.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class EditorSettingsTest {
    @Test
    public void defaultsAndCopyAreValidAndIndependent() {
        EditorSettings settings = new EditorSettings();
        assertTrue(settings.isValid());
        EditorSettings copy = settings.copy();
        copy.musicVolume = 0.25;
        copy.splitSnapToGrid = false;
        assertEquals(1.0, settings.musicVolume, 0.0);
        assertEquals(0.25, copy.musicVolume, 0.0);
        assertTrue(settings.splitSnapToGrid);
        assertFalse(copy.splitSnapToGrid);
    }

    @Test
    public void rejectsInvalidRangesAndHotkeys() {
        EditorSettings settings = new EditorSettings();
        settings.backgroundBrightness = 256;
        assertFalse(settings.isValid());
        settings = new EditorSettings();
        settings.shortcutUndo = "CTRL+ALT";
        assertFalse(settings.isValid());
    }

    @Test
    public void parsesManualAndNativeChordSyntax() {
        ShortcutChord chord = ShortcutChord.parse("leftctrl & shift & z");
        assertTrue(chord.control);
        assertTrue(chord.shift);
        assertEquals("Z", chord.key);
        assertTrue(ShortcutChord.isValid("SPACE"));
        assertTrue(ShortcutChord.isValid("ALT+F4"));
        assertFalse(ShortcutChord.isValid("CTRL+LEFTCTRL+Z"));
        assertFalse(ShortcutChord.isValid("ALT+RIGHTALT+F4"));
    }
}
