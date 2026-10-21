package com.xpe.mobile.config;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class EditorSettingsMigrationTest {
    @Test public void previewNoteScaleDefaultsToOneAndPersistsWithoutChangingEditorNoteWidth() {
        SharedPreferences prefs=preferences(new HashMap<>());
        EditorSettings value=EditorSettingsStore.load(prefs);
        assertEquals(1,value.previewNoteScale,0);
        for(double scale:new double[]{.8,1,1.001,1.2}) {
            value.previewNoteScale=scale;assertTrue(value.isValid());
            EditorSettingsStore.save(prefs,value.copy());
            assertEquals(scale,EditorSettingsStore.load(prefs).previewNoteScale,0);
            assertEquals(58,EditorSettingsStore.load(prefs).noteWidthPixels,0);
        }
        for(double invalid:new double[]{.799,1.201,Double.NaN,Double.POSITIVE_INFINITY}) {
            value.previewNoteScale=invalid;assertFalse(value.isValid());
        }
    }
    @Test public void gridEffectsDefaultOnAndPersistIndependentlyAcrossCopiesAndUpgrades() {
        SharedPreferences prefs = preferences(new HashMap<>());
        EditorSettings value = EditorSettingsStore.load(prefs);
        assertTrue(value.gridPreviewShaders); assertTrue(value.gridPreviewNoiseFields);
        for (boolean shaders : new boolean[]{false, true}) for (boolean fields : new boolean[]{false, true}) {
            value.gridPreviewShaders = shaders; value.gridPreviewNoiseFields = fields;
            EditorSettingsStore.save(prefs, value.copy());
            EditorSettings loaded = EditorSettingsStore.load(prefs);
            assertEquals(shaders, loaded.gridPreviewShaders); assertEquals(fields, loaded.gridPreviewNoiseFields);
        }
    }
    @Test public void obsoleteCutDensityIsIgnoredAndRemovedWithoutResettingSettings() {
        Map<String,Object> values = new HashMap<>();
        values.put("editor_settings.cutDensity", Double.doubleToRawLongBits(Double.NaN));
        values.put("editor_settings.musicVolume", Double.doubleToRawLongBits(.35));
        SharedPreferences prefs = preferences(values);
        EditorSettings settings = EditorSettingsStore.load(prefs);
        assertEquals(.35, settings.musicVolume, 0);
        EditorSettingsStore.save(prefs, settings);
        assertFalse(values.containsKey("editor_settings.cutDensity"));
        assertEquals(.35, EditorSettingsStore.load(prefs).musicVolume, 0);
    }

    @Test public void tapToSeekDefaultsOffAndSurvivesCopySaveAndReload() {
        SharedPreferences prefs=preferences(new HashMap<>());
        EditorSettings settings=EditorSettingsStore.load(prefs);
        assertFalse(settings.tapToSeek);
        settings.tapToSeek=true;assertTrue(settings.copy().tapToSeek);
        EditorSettingsStore.save(prefs,settings);assertTrue(EditorSettingsStore.load(prefs).tapToSeek);
        settings.tapToSeek=false;EditorSettingsStore.save(prefs,settings);
        assertFalse(EditorSettingsStore.load(prefs).tapToSeek);
    }
    @Test public void obsoleteWidthCannotResetExistingSettingsAndHighlightPersists() {
        Map<String,Object> values = new HashMap<>();
        // Previous releases stored this as a double's raw bits. An invalid old
        // multiplier must no longer cause load() to reset the whole settings object.
        values.put("editor_settings.lineDefaultWidth", Double.doubleToRawLongBits(Double.NaN));
        values.put("editor_settings.previewBackgroundAlpha", Double.doubleToRawLongBits(.05));
        values.put("editor_settings.useChartBackgroundDim", false);
        values.put("editor_settings.previewBackgroundDim", Double.doubleToRawLongBits(Double.NaN));
        values.put("editor_settings.musicVolume", Double.doubleToRawLongBits(.35));
        values.put("editor_settings.lineColorRgb", 0x123456);
        values.put("editor_settings.playerWidth", 2100);
        values.put("editor_settings.playerHeight", 1400);
        SharedPreferences preferences = preferences(values);
        EditorSettings settings = EditorSettingsStore.load(preferences);
        assertEquals(.35, settings.musicVolume, 0);
        assertEquals(0x123456, settings.lineColorRgb);
        assertEquals(2100, settings.playerWidth);
        assertTrue(settings.highlightCurrentLine);
        settings.highlightCurrentLine = false;
        EditorSettingsStore.save(preferences, settings);
        assertFalse(values.containsKey("editor_settings.lineDefaultWidth"));
        assertFalse(values.containsKey("editor_settings.previewBackgroundAlpha"));
        assertFalse(values.containsKey("editor_settings.useChartBackgroundDim"));
        assertFalse(values.containsKey("editor_settings.previewBackgroundDim"));
        EditorSettings reopened = EditorSettingsStore.load(preferences);
        assertFalse(reopened.highlightCurrentLine);
        assertEquals(0x123456, reopened.lineColorRgb);
        assertEquals(.35, reopened.musicVolume, 0);
    }

    @Test public void newDefaultsAndLegacyDefaultsMigrateButCustomValuesSurvive() {
        Map<String,Object> values = new HashMap<>();
        SharedPreferences prefs = preferences(values);
        EditorSettings defaults = EditorSettingsStore.load(prefs);
        assertEquals(50, defaults.backgroundBrightness);
        assertEquals(0, defaults.dragHitsoundOffsetMs);
        values.put("editor_settings.backgroundBrightness", 147);
        values.put("editor_settings.dragHitsoundOffsetMs", -30);
        EditorSettings migrated = EditorSettingsStore.load(prefs);
        assertEquals(50, migrated.backgroundBrightness);
        assertEquals(0, migrated.dragHitsoundOffsetMs);
        values.put("editor_settings.backgroundBrightness", 90);
        values.put("editor_settings.dragHitsoundOffsetMs", -15);
        migrated = EditorSettingsStore.load(prefs);
        assertEquals(90, migrated.backgroundBrightness);
        assertEquals(-15, migrated.dragHitsoundOffsetMs);
        EditorSettingsStore.save(prefs, migrated);
        // After the one-time upgrade, even values matching the old defaults are custom.
        migrated.backgroundBrightness = 147;
        migrated.dragHitsoundOffsetMs = -30;
        EditorSettingsStore.save(prefs, migrated);
        assertEquals(147, EditorSettingsStore.load(prefs).backgroundBrightness);
        assertEquals(-30, EditorSettingsStore.load(prefs).dragHitsoundOffsetMs);
    }

    /** Interface-only in-memory backend: no Android/native runtime required. */
    private static SharedPreferences preferences(Map<String,Object> values) {
        ClassLoader loader = EditorSettingsMigrationTest.class.getClassLoader();
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                loader, new Class<?>[]{SharedPreferences.Editor.class}, (proxy,method,args) -> {
                    String name = method.getName();
                    if (name.startsWith("put")) {
                        values.put((String)args[0], args[1]);
                        return proxy;
                    }
                    if (name.equals("remove")) {
                        values.remove(args[0]);
                        return proxy;
                    }
                    if (name.equals("apply")) return null;
                    throw new UnsupportedOperationException(name);
                });
        return (SharedPreferences) Proxy.newProxyInstance(loader,
                new Class<?>[]{SharedPreferences.class}, (proxy,method,args) -> {
                    if (method.getName().equals("edit")) return editor;
                    if (method.getName().startsWith("get") && args != null && args.length == 2) {
                        return values.getOrDefault(args[0], args[1]);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
