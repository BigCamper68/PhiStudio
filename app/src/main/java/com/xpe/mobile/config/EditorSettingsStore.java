package com.xpe.mobile.config;

import android.content.SharedPreferences;

/** SharedPreferences persistence for the full Settings dialog. */
public final class EditorSettingsStore {
    private static final String PREFIX = "editor_settings.";

    private EditorSettingsStore() {
    }

    public static EditorSettings load(SharedPreferences preferences) {
        EditorSettings defaults = new EditorSettings();
        EditorSettings result = new EditorSettings();
        result.musicVolume = getDouble(preferences, "musicVolume", defaults.musicVolume);
        result.soundEffectVolume = getDouble(preferences, "soundEffectVolume", defaults.soundEffectVolume);
        result.highlightSimultaneousNotes = preferences.getBoolean(key("highlightSimultaneousNotes"), defaults.highlightSimultaneousNotes);
        result.noteWidthPixels = getDouble(preferences, "noteWidthPixels", defaults.noteWidthPixels);
        result.lineDefaultWidth = getDouble(preferences, "lineDefaultWidth", defaults.lineDefaultWidth);
        result.markLineId = preferences.getBoolean(key("markLineId"), defaults.markLineId);
        result.lineColorRgb = preferences.getInt(key("lineColorRgb"), defaults.lineColorRgb);
        result.backgroundBrightness = preferences.getInt(key("backgroundBrightness"), defaults.backgroundBrightness);
        result.tapFlickHitsoundOffsetMs = preferences.getInt(key("tapFlickHitsoundOffsetMs"), defaults.tapFlickHitsoundOffsetMs);
        result.dragHitsoundOffsetMs = preferences.getInt(key("dragHitsoundOffsetMs"), defaults.dragHitsoundOffsetMs);
        result.autosaveEnabled = preferences.getBoolean(key("autosaveEnabled"), defaults.autosaveEnabled);
        result.autosaveIntervalSeconds = getDouble(preferences, "autosaveIntervalSeconds", defaults.autosaveIntervalSeconds);
        result.timelineScrollSpeed = getDouble(preferences, "timelineScrollSpeed", defaults.timelineScrollSpeed);
        result.eventScrollSpeed = getDouble(preferences, "eventScrollSpeed", defaults.eventScrollSpeed);
        result.previewBackgroundAlpha = getDouble(preferences, "previewBackgroundAlpha", defaults.previewBackgroundAlpha);
        result.playerWidth = preferences.getInt(key("playerWidth"), defaults.playerWidth);
        result.playerHeight = preferences.getInt(key("playerHeight"), defaults.playerHeight);
        result.autoApplyPropertyEdits = preferences.getBoolean(key("autoApplyPropertyEdits"), defaults.autoApplyPropertyEdits);
        result.autoMoveToClipboard = preferences.getBoolean(key("autoMoveToClipboard"), defaults.autoMoveToClipboard);
        result.showTips = preferences.getBoolean(key("showTips"), defaults.showTips);
        result.xyBindingEnabled = preferences.getBoolean(key("xyBindingEnabled"), defaults.xyBindingEnabled);
        result.skipWhenUndoRedo = preferences.getBoolean(key("skipWhenUndoRedo"), defaults.skipWhenUndoRedo);
        result.autoStickEvents = preferences.getBoolean(key("autoStickEvents"), defaults.autoStickEvents);
        result.splitSnapToGrid = preferences.getBoolean(key("splitSnapToGrid"), defaults.splitSnapToGrid);
        result.cutDensity = getDouble(preferences, "cutDensity", defaults.cutDensity);
        result.drawEventCurves = preferences.getBoolean(key("drawEventCurves"), defaults.drawEventCurves);
        result.drawEventNumbers = preferences.getBoolean(key("drawEventNumbers"), defaults.drawEventNumbers);
        result.correctionXThreshold = getDouble(preferences, "correctionXThreshold", defaults.correctionXThreshold);
        result.correctionCollisionDistance = getDouble(preferences, "correctionCollisionDistance", defaults.correctionCollisionDistance);
        result.correctionReadTimeSeconds = getDouble(preferences, "correctionReadTimeSeconds", defaults.correctionReadTimeSeconds);
        result.correctionDragWarningSeconds = getDouble(preferences, "correctionDragWarningSeconds", defaults.correctionDragWarningSeconds);
        result.correctionCombinationSeconds = getDouble(preferences, "correctionCombinationSeconds", defaults.correctionCombinationSeconds);
        result.shortcutPlayPause = preferences.getString(key("shortcutPlayPause"), defaults.shortcutPlayPause);
        result.shortcutSave = preferences.getString(key("shortcutSave"), defaults.shortcutSave);
        result.shortcutUndo = preferences.getString(key("shortcutUndo"), defaults.shortcutUndo);
        result.shortcutRedo = preferences.getString(key("shortcutRedo"), defaults.shortcutRedo);
        result.shortcutCopy = preferences.getString(key("shortcutCopy"), defaults.shortcutCopy);
        result.shortcutCut = preferences.getString(key("shortcutCut"), defaults.shortcutCut);
        result.shortcutPaste = preferences.getString(key("shortcutPaste"), defaults.shortcutPaste);
        result.shortcutMirrorPaste = preferences.getString(key("shortcutMirrorPaste"), defaults.shortcutMirrorPaste);
        result.shortcutDelete = preferences.getString(key("shortcutDelete"), defaults.shortcutDelete);
        return result.isValid() ? result : defaults;
    }

    public static void save(SharedPreferences preferences, EditorSettings value) {
        if (preferences == null || value == null || !value.isValid()) {
            throw new IllegalArgumentException("valid settings are required");
        }
        SharedPreferences.Editor editor = preferences.edit();
        putDouble(editor, "musicVolume", value.musicVolume);
        putDouble(editor, "soundEffectVolume", value.soundEffectVolume);
        editor.putBoolean(key("highlightSimultaneousNotes"), value.highlightSimultaneousNotes);
        putDouble(editor, "noteWidthPixels", value.noteWidthPixels);
        putDouble(editor, "lineDefaultWidth", value.lineDefaultWidth);
        editor.putBoolean(key("markLineId"), value.markLineId);
        editor.putInt(key("lineColorRgb"), value.lineColorRgb);
        editor.putInt(key("backgroundBrightness"), value.backgroundBrightness);
        editor.putInt(key("tapFlickHitsoundOffsetMs"), value.tapFlickHitsoundOffsetMs);
        editor.putInt(key("dragHitsoundOffsetMs"), value.dragHitsoundOffsetMs);
        editor.putBoolean(key("autosaveEnabled"), value.autosaveEnabled);
        putDouble(editor, "autosaveIntervalSeconds", value.autosaveIntervalSeconds);
        putDouble(editor, "timelineScrollSpeed", value.timelineScrollSpeed);
        putDouble(editor, "eventScrollSpeed", value.eventScrollSpeed);
        putDouble(editor, "previewBackgroundAlpha", value.previewBackgroundAlpha);
        editor.putInt(key("playerWidth"), value.playerWidth);
        editor.putInt(key("playerHeight"), value.playerHeight);
        editor.putBoolean(key("autoApplyPropertyEdits"), value.autoApplyPropertyEdits);
        editor.putBoolean(key("autoMoveToClipboard"), value.autoMoveToClipboard);
        editor.putBoolean(key("showTips"), value.showTips);
        editor.putBoolean(key("xyBindingEnabled"), value.xyBindingEnabled);
        editor.putBoolean(key("skipWhenUndoRedo"), value.skipWhenUndoRedo);
        editor.putBoolean(key("autoStickEvents"), value.autoStickEvents);
        editor.putBoolean(key("splitSnapToGrid"), value.splitSnapToGrid);
        putDouble(editor, "cutDensity", value.cutDensity);
        editor.putBoolean(key("drawEventCurves"), value.drawEventCurves);
        editor.putBoolean(key("drawEventNumbers"), value.drawEventNumbers);
        putDouble(editor, "correctionXThreshold", value.correctionXThreshold);
        putDouble(editor, "correctionCollisionDistance", value.correctionCollisionDistance);
        putDouble(editor, "correctionReadTimeSeconds", value.correctionReadTimeSeconds);
        putDouble(editor, "correctionDragWarningSeconds", value.correctionDragWarningSeconds);
        putDouble(editor, "correctionCombinationSeconds", value.correctionCombinationSeconds);
        editor.putString(key("shortcutPlayPause"), value.shortcutPlayPause);
        editor.putString(key("shortcutSave"), value.shortcutSave);
        editor.putString(key("shortcutUndo"), value.shortcutUndo);
        editor.putString(key("shortcutRedo"), value.shortcutRedo);
        editor.putString(key("shortcutCopy"), value.shortcutCopy);
        editor.putString(key("shortcutCut"), value.shortcutCut);
        editor.putString(key("shortcutPaste"), value.shortcutPaste);
        editor.putString(key("shortcutMirrorPaste"), value.shortcutMirrorPaste);
        editor.putString(key("shortcutDelete"), value.shortcutDelete);
        editor.apply();
    }

    private static String key(String name) {
        return PREFIX + name;
    }

    private static double getDouble(SharedPreferences preferences, String name, double fallback) {
        return Double.longBitsToDouble(preferences.getLong(
                key(name), Double.doubleToRawLongBits(fallback)));
    }

    private static void putDouble(SharedPreferences.Editor editor, String name, double value) {
        editor.putLong(key(name), Double.doubleToRawLongBits(value));
    }
}
