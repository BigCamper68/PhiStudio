package com.xpe.mobile.config;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Parser for editable hardware-keyboard chords shown in the Settings dialog. */
public final class ShortcutChord {
    public final boolean control;
    public final boolean alt;
    public final boolean shift;
    public final String key;

    private ShortcutChord(boolean control, boolean alt, boolean shift, String key) {
        this.control = control;
        this.alt = alt;
        this.shift = shift;
        this.key = key;
    }

    public static ShortcutChord parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("shortcut is required");
        }
        String normalized = text.toUpperCase(Locale.ROOT)
                .replace('&', '+').replace(" ", "");
        String[] parts = normalized.split("\\+", -1);
        boolean control = false;
        boolean alt = false;
        boolean shift = false;
        String key = null;
        Set<String> seen = new HashSet<>();
        for (String part : parts) {
            if (part.isEmpty() || !seen.add(part)) {
                throw new IllegalArgumentException("invalid shortcut");
            }
            switch (part) {
                case "CTRL":
                case "CONTROL":
                case "LEFTCTRL":
                case "RIGHTCTRL": control = true; break;
                case "ALT":
                case "LEFTALT":
                case "RIGHTALT": alt = true; break;
                case "SHIFT":
                case "LEFTSHIFT":
                case "RIGHTSHIFT": shift = true; break;
                default:
                    if (key != null || !validKey(part)) {
                        throw new IllegalArgumentException("invalid shortcut key");
                    }
                    key = part;
                    break;
            }
        }
        if (key == null) throw new IllegalArgumentException("shortcut key is required");
        return new ShortcutChord(control, alt, shift, key);
    }

    public static boolean isValid(String text) {
        try {
            parse(text);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean validKey(String value) {
        if (value.length() == 1 && Character.isLetterOrDigit(value.charAt(0))) return true;
        switch (value) {
            case "SPACE":
            case "DELETE":
            case "BACKSPACE":
            case "ENTER":
            case "ESC":
            case "LEFT":
            case "RIGHT":
            case "UP":
            case "DOWN":
            case "PAGEUP":
            case "PAGEDOWN":
            case "HOME":
            case "END":
                return true;
            default:
                return value.matches("F(?:[1-9]|1[0-2])");
        }
    }
}
