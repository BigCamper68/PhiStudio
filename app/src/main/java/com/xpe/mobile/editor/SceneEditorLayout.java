package com.xpe.mobile.editor;

/** Existing shader layout: 48 dp toolbar, 60/40 preview/grid split, compact bottom properties. */
public final class SceneEditorLayout {
    private SceneEditorLayout() { }
    public static int top(int height, float density) { return Math.min(height, Math.round(48 * density)); }
    public static int panel(int height, float density, boolean collapsed, boolean expanded) {
        int top = top(height, density);
        return Math.min(Math.max(0, height - top - Math.round(64 * density)),
                collapsed ? Math.round(36 * density) : expanded
                        ? Math.min(Math.round(176 * density), Math.round(height * .45f)) : Math.round(88 * density));
    }
    public static int split(int width) { return Math.round(width * .60f); }
}
