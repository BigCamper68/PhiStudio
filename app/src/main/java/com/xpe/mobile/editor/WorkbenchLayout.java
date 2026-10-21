package com.xpe.mobile.editor;

/** Shared geometry for the canvas and native workbench; all arguments are pixels. */
public final class WorkbenchLayout {
    private WorkbenchLayout() { }

    public static float split(float width, float density) {
        float pane = Math.max(240f * density, width * .32f);
        pane = Math.min(pane, width * .44f);
        return Math.max(0, width - pane);
    }

    public static float toolbar(float density) { return 48f * density; }
    public static float footer(float density) { return 76f * density; }
    public static float workbenchFooter(float density) { return 34f * density; }
}
