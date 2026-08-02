package com.xpe.mobile.config;

/** Persistent native-editor settings adapted from the Re:PhiEdit Settings manual. */
public final class EditorSettings {
    public double musicVolume = 1.0;
    public double soundEffectVolume = 1.0;
    public boolean highlightSimultaneousNotes = true;
    public double noteWidthPixels = 58.0;
    public double lineDefaultWidth = 1.7;
    public boolean markLineId = true;
    public int lineColorRgb = 0x5BD3AC;
    public int backgroundBrightness = 147;
    public int tapFlickHitsoundOffsetMs;
    public int dragHitsoundOffsetMs = -30;

    public boolean autosaveEnabled = true;
    public double autosaveIntervalSeconds = 120.0;
    public double timelineScrollSpeed = 1.0;
    public double eventScrollSpeed = 1.0;
    public double previewBackgroundAlpha = 0.6;
    public int playerWidth = 1920;
    public int playerHeight = 1080;
    public boolean autoApplyPropertyEdits;
    public boolean autoMoveToClipboard;
    public boolean showTips = true;
    public boolean xyBindingEnabled = true;
    public boolean skipWhenUndoRedo;
    public boolean autoStickEvents;
    public boolean splitSnapToGrid = true;
    public double cutDensity = 8.0;
    public boolean drawEventCurves = true;
    public boolean drawEventNumbers = true;

    public double correctionXThreshold = 700.0;
    public double correctionCollisionDistance = 200.0;
    public double correctionReadTimeSeconds = 0.300;
    public double correctionDragWarningSeconds = 0.040;
    public double correctionCombinationSeconds = 0.160;

    public String shortcutPlayPause = "SPACE";
    public String shortcutSave = "CTRL+S";
    public String shortcutUndo = "CTRL+Z";
    public String shortcutRedo = "CTRL+Y";
    public String shortcutCopy = "CTRL+C";
    public String shortcutCut = "CTRL+X";
    public String shortcutPaste = "CTRL+V";
    public String shortcutMirrorPaste = "CTRL+B";
    public String shortcutDelete = "DELETE";

    public EditorSettings copy() {
        EditorSettings copy = new EditorSettings();
        copy.musicVolume = musicVolume;
        copy.soundEffectVolume = soundEffectVolume;
        copy.highlightSimultaneousNotes = highlightSimultaneousNotes;
        copy.noteWidthPixels = noteWidthPixels;
        copy.lineDefaultWidth = lineDefaultWidth;
        copy.markLineId = markLineId;
        copy.lineColorRgb = lineColorRgb;
        copy.backgroundBrightness = backgroundBrightness;
        copy.tapFlickHitsoundOffsetMs = tapFlickHitsoundOffsetMs;
        copy.dragHitsoundOffsetMs = dragHitsoundOffsetMs;
        copy.autosaveEnabled = autosaveEnabled;
        copy.autosaveIntervalSeconds = autosaveIntervalSeconds;
        copy.timelineScrollSpeed = timelineScrollSpeed;
        copy.eventScrollSpeed = eventScrollSpeed;
        copy.previewBackgroundAlpha = previewBackgroundAlpha;
        copy.playerWidth = playerWidth;
        copy.playerHeight = playerHeight;
        copy.autoApplyPropertyEdits = autoApplyPropertyEdits;
        copy.autoMoveToClipboard = autoMoveToClipboard;
        copy.showTips = showTips;
        copy.xyBindingEnabled = xyBindingEnabled;
        copy.skipWhenUndoRedo = skipWhenUndoRedo;
        copy.autoStickEvents = autoStickEvents;
        copy.splitSnapToGrid = splitSnapToGrid;
        copy.cutDensity = cutDensity;
        copy.drawEventCurves = drawEventCurves;
        copy.drawEventNumbers = drawEventNumbers;
        copy.correctionXThreshold = correctionXThreshold;
        copy.correctionCollisionDistance = correctionCollisionDistance;
        copy.correctionReadTimeSeconds = correctionReadTimeSeconds;
        copy.correctionDragWarningSeconds = correctionDragWarningSeconds;
        copy.correctionCombinationSeconds = correctionCombinationSeconds;
        copy.shortcutPlayPause = shortcutPlayPause;
        copy.shortcutSave = shortcutSave;
        copy.shortcutUndo = shortcutUndo;
        copy.shortcutRedo = shortcutRedo;
        copy.shortcutCopy = shortcutCopy;
        copy.shortcutCut = shortcutCut;
        copy.shortcutPaste = shortcutPaste;
        copy.shortcutMirrorPaste = shortcutMirrorPaste;
        copy.shortcutDelete = shortcutDelete;
        return copy;
    }

    public boolean isValid() {
        return inRange(musicVolume, 0.0, 1.0)
                && inRange(soundEffectVolume, 0.0, 1.0)
                && finitePositive(noteWidthPixels)
                && finitePositive(lineDefaultWidth)
                && lineColorRgb >= 0 && lineColorRgb <= 0xFFFFFF
                && backgroundBrightness >= 0 && backgroundBrightness <= 255
                && finitePositive(autosaveIntervalSeconds)
                && finitePositive(timelineScrollSpeed)
                && finitePositive(eventScrollSpeed)
                && inRange(previewBackgroundAlpha, 0.0, 1.0)
                && playerWidth > 0 && playerHeight > 0
                && finitePositive(cutDensity)
                && finiteNonNegative(correctionXThreshold)
                && finiteNonNegative(correctionCollisionDistance)
                && finiteNonNegative(correctionReadTimeSeconds)
                && finiteNonNegative(correctionDragWarningSeconds)
                && finiteNonNegative(correctionCombinationSeconds)
                && ShortcutChord.isValid(shortcutPlayPause)
                && ShortcutChord.isValid(shortcutSave)
                && ShortcutChord.isValid(shortcutUndo)
                && ShortcutChord.isValid(shortcutRedo)
                && ShortcutChord.isValid(shortcutCopy)
                && ShortcutChord.isValid(shortcutCut)
                && ShortcutChord.isValid(shortcutPaste)
                && ShortcutChord.isValid(shortcutMirrorPaste)
                && ShortcutChord.isValid(shortcutDelete);
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    private static boolean inRange(double value, double minimum, double maximum) {
        return Double.isFinite(value) && value >= minimum && value <= maximum;
    }
}
