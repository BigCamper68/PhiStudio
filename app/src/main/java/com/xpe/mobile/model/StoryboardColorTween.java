package com.xpe.mobile.model;

import org.json.JSONArray;

/** Color event encoding and channel interpolation. */
final class StoryboardColorTween {
    static int readRgb(JSONArray value, int fallback) {
        if (value == null || value.length() < 3) return fallback;
        return clampChannel(value.optInt(0, (fallback >> 16) & 0xff)) << 16
                | clampChannel(value.optInt(1, (fallback >> 8) & 0xff)) << 8
                | clampChannel(value.optInt(2, fallback & 0xff));
    }

    static JSONArray writeRgb(int rgb) {
        return new JSONArray().put((rgb >> 16) & 0xff)
                .put((rgb >> 8) & 0xff).put(rgb & 0xff);
    }

    static int interpolateChannel(int start, int end, double progress) {
        return clampChannel((int) Math.round((start & 0xff)
                + ((end & 0xff) - (start & 0xff)) * progress));
    }

    static int clampChannel(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
