package com.xpe.mobile.model;

/** RPE/prpr reserved names select a built-in sound, independently of note type. */
public final class HitSoundReference {
    public static final String CLICK = "tap.mp3";
    public static final String FLICK = "flick.mp3";
    public static final String DRAG = "drag.mp3";

    private HitSoundReference() { }

    public static NoteType builtinType(String reference) {
        if (CLICK.equals(reference)) return NoteType.TAP;
        if (FLICK.equals(reference)) return NoteType.FLICK;
        if (DRAG.equals(reference)) return NoteType.DRAG;
        return null;
    }

    public static boolean isDefault(String reference) {
        return reference == null || reference.isEmpty();
    }

    public static boolean isCustom(String reference) {
        return !isDefault(reference) && builtinType(reference) == null;
    }

    public static NoteType soundType(NoteType noteType, String reference) {
        NoteType override = builtinType(reference);
        return override == null ? noteType : override;
    }
}
