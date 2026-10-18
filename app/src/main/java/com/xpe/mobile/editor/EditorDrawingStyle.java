package com.xpe.mobile.editor;

import android.graphics.Color;
import com.xpe.mobile.R;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;
import java.util.Locale;

/** EditorDrawingStyle responsibilities extracted from EditorSurfaceView. */
final class EditorDrawingStyle {
    private final EditorSurfaceView delegateOwner;

    EditorDrawingStyle(EditorSurfaceView delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    static int clampAlpha(int alpha) {
        return Math.max(0, Math.min(255, alpha));
    }

    int eventColor(EventType type, boolean linked) {
        int color;
        switch (type) {
            case MOVE_X: color = Color.rgb(80, 188, 166); break;
            case MOVE_Y: color = Color.rgb(77, 160, 205); break;
            case ROTATE: color = Color.rgb(183, 114, 214); break;
            case ALPHA: color = Color.rgb(224, 186, 83); break;
            case SPEED: color = Color.rgb(225, 111, 104); break;
            default: color = Color.GRAY; break;
        }
        if (!linked) return color;
        return Color.rgb((Color.red(color) + 30) / 2,
                (Color.green(color) + 40) / 2,
                (Color.blue(color) + 90) / 2);
    }

    final String eventEasingLabel(LineEvent event) {
        if (event.bezier && event.type != EventType.SPEED) {
            return delegateOwner.getResources().getString(R.string.controls_event_ease_custom);
        }
        int type = event.type == EventType.SPEED ? 1 : event.easingType;
        if (type < 1 || type > delegateOwner.easingEntries.length) {
            return delegateOwner.getResources().getString(R.string.controls_event_ease_unknown, type);
        }
        int index = Math.max(0, Math.min(delegateOwner.easingEntries.length - 1, type - 1));
        return delegateOwner.easingEntries[index];
    }

    static double eventStep(EventType type) {
        switch (type) {
            case ROTATE: return 0.25;
            case SPEED: return 0.1;
            default: return 10.0;
        }
    }

    static double normalizeEventValue(EventType type, double value) {
        if (type == EventType.ALPHA) return Math.max(-255.0, Math.min(255.0, value));
        return value;
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    final String ellipsize(String text, float maximumWidth) {
        if (text == null || maximumWidth <= 0f) return "";
        if (delegateOwner.paint.measureText(text) <= maximumWidth) return text;
        String suffix = "…";
        int end = text.length();
        while (end > 0
                && delegateOwner.paint.measureText(text, 0, end) + delegateOwner.paint.measureText(suffix) > maximumWidth) {
            end--;
        }
        return end <= 0 ? suffix : text.substring(0, end) + suffix;
    }

    final String ellipsizeFromEnd(String text, float maximumWidth) {
        if (text == null || maximumWidth <= 0f) return "";
        if (delegateOwner.paint.measureText(text) <= maximumWidth) return text;
        String prefix = "…";
        int start = 0;
        while (start < text.length()
                && delegateOwner.paint.measureText(text, start, text.length())
                + delegateOwner.paint.measureText(prefix) > maximumWidth) {
            start++;
        }
        return start >= text.length() ? prefix : prefix + text.substring(start);
    }

    static String compactValue(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.US, "%.2f", value);
    }

    static String formatMillis(long milliseconds) {
        long safe = milliseconds == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(milliseconds);
        long minutes = safe / 60000L;
        long seconds = (safe / 1000L) % 60L;
        long millis = safe % 1000L;
        return (milliseconds < 0 ? "-" : "") + String.format(Locale.US, "%d:%02d.%03d", minutes, seconds, millis);
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
