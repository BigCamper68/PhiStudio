package com.xpe.mobile.app;

import android.view.KeyEvent;
import com.xpe.mobile.config.ShortcutChord;

/** MainShortcuts responsibilities extracted from MainActivity. */
final class MainShortcuts {
    private final MainActivity delegateOwner;

    MainShortcuts(MainActivity delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void saveFromShortcut() {
        delegateOwner.requestProjectSave();
    }

    boolean matchesShortcut(KeyEvent event, String text) {
        final ShortcutChord chord;
        try {
            chord = ShortcutChord.parse(text);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return event.isCtrlPressed() == chord.control
                && event.isAltPressed() == chord.alt
                && event.isShiftPressed() == chord.shift
                && event.getKeyCode() == MainShortcuts.shortcutKeyCode(chord.key);
    }

    static int shortcutKeyCode(String key) {
        if (key.length() == 1) {
            char value = key.charAt(0);
            if (value >= 'A' && value <= 'Z') return KeyEvent.KEYCODE_A + value - 'A';
            if (value >= '0' && value <= '9') return KeyEvent.KEYCODE_0 + value - '0';
        }
        switch (key) {
            case "SPACE": return KeyEvent.KEYCODE_SPACE;
            case "DELETE": return KeyEvent.KEYCODE_FORWARD_DEL;
            case "BACKSPACE": return KeyEvent.KEYCODE_DEL;
            case "ENTER": return KeyEvent.KEYCODE_ENTER;
            case "ESC": return KeyEvent.KEYCODE_ESCAPE;
            case "LEFT": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "RIGHT": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "UP": return KeyEvent.KEYCODE_DPAD_UP;
            case "DOWN": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "PAGEUP": return KeyEvent.KEYCODE_PAGE_UP;
            case "PAGEDOWN": return KeyEvent.KEYCODE_PAGE_DOWN;
            case "HOME": return KeyEvent.KEYCODE_MOVE_HOME;
            case "END": return KeyEvent.KEYCODE_MOVE_END;
            default:
                if (key.matches("F(?:[1-9]|1[0-2])")) {
                    return KeyEvent.KEYCODE_F1 + Integer.parseInt(key.substring(1)) - 1;
                }
                return KeyEvent.KEYCODE_UNKNOWN;
        }
    }
}
