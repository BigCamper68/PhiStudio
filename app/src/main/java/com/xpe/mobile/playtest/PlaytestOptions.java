package com.xpe.mobile.playtest;

import android.content.SharedPreferences;
import com.xpe.mobile.model.ChartDocument;

/** User choices are remembered independently of the currently open chart. */
public final class PlaytestOptions {
    public static final int BEGINNING = 0, PLAYHEAD = 1, CUSTOM = 2;
    public int start = BEGINNING;
    public boolean customBeat;
    public String time = "0:00.000", beat = "0";
    public int countdown = 3;

    public static PlaytestOptions load(SharedPreferences prefs) {
        PlaytestOptions o = new PlaytestOptions();
        o.start = prefs.getInt("playtest.start", BEGINNING);
        if (o.start < BEGINNING || o.start > CUSTOM) o.start = BEGINNING;
        o.customBeat = prefs.getBoolean("playtest.customBeat", false);
        o.time = prefs.getString("playtest.time", o.time);
        o.beat = prefs.getString("playtest.beat", o.beat);
        o.countdown = prefs.getInt("playtest.countdown", 3);
        if (o.countdown != 0 && o.countdown != 2 && o.countdown != 3 && o.countdown != 5) o.countdown = 3;
        return o;
    }

    public void save(SharedPreferences prefs) {
        prefs.edit().putInt("playtest.start", start).putBoolean("playtest.customBeat", customBeat)
                .putString("playtest.time", time).putString("playtest.beat", beat)
                .putInt("playtest.countdown", countdown).apply();
    }

    public double seconds(ChartDocument chart, double playheadSeconds) {
        if (start == BEGINNING) return 0;
        if (start == PLAYHEAD) return Math.max(0, playheadSeconds);
        return customBeat ? chart.beatToSeconds(number(beat)) : parseTime(time);
    }

    public static double number(String input) {
        try {
            double result = Double.parseDouble(input.trim().replace(',', '.'));
            if (Double.isFinite(result) && result >= 0) return result;
        } catch (RuntimeException ignored) { }
        throw new IllegalArgumentException("Enter a finite, non-negative value");
    }

    /** Seconds, M:SS.mmm or H:MM:SS.mmm, always relative to chart beat zero. */
    public static double parseTime(String text) {
        String[] parts = text.trim().split(":", -1);
        if (parts.length < 1 || parts.length > 3) throw new IllegalArgumentException("Invalid time");
        double result = 0;
        for (int i = 0; i < parts.length; i++) {
            double part = number(parts[i]);
            if (parts.length > 1 && (i < parts.length - 1 && part != Math.floor(part)
                    || i > 0 && part >= 60)) throw new IllegalArgumentException("Invalid time");
            result = result * 60 + part;
        }
        if (!Double.isFinite(result)) throw new IllegalArgumentException("Invalid time");
        return result;
    }
}
