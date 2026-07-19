package com.xpe.mobile.model;

import java.util.Locale;

/** RPE HUD element controlled by a judge line through {@code attachUI}. */
public enum AttachedUiElement {
    PAUSE,
    COMBO_NUMBER,
    COMBO,
    SCORE,
    BAR,
    NAME,
    LEVEL;

    public static AttachedUiElement fromJsonValue(Object value) {
        if (!(value instanceof String)) return null;
        String normalized = ((String) value).trim().toLowerCase(Locale.ROOT)
                .replace("_", "").replace("-", "").replace(" ", "");
        switch (normalized) {
            case "pause": return PAUSE;
            case "combonumber": return COMBO_NUMBER;
            case "combo": return COMBO;
            case "score": return SCORE;
            case "bar": return BAR;
            case "name": return NAME;
            case "level": return LEVEL;
            default: return null;
        }
    }
}
