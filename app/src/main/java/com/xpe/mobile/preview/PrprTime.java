package com.xpe.mobile.preview;

import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Immutable BPM index. Rendering keeps sub-millisecond and negative event times. */
final class PrprTime {
    private final double[] beats, seconds, rates;

    PrprTime(ChartDocument chart) {
        List<BpmChange> changes = new ArrayList<>(chart.bpmChanges);
        changes.sort(Comparator.comparingDouble(c -> c.startTime.toDouble()));
        int n = Math.max(1, changes.size());
        beats = new double[n]; seconds = new double[n]; rates = new double[n];
        rates[0] = 60.0 / 120.0;
        for (int i = 0; i < changes.size(); i++) {
            BpmChange change = changes.get(i);
            // BpmList anchors time zero at its first authored beat, including negative beats.
            beats[i] = change.startTime.toDouble();
            if (i > 0) seconds[i] = seconds[i - 1]
                    + (beats[i] - beats[i - 1]) * rates[i - 1];
            rates[i] = Double.isFinite(change.bpm) && change.bpm > 0
                    ? 60.0 / change.bpm : rates[Math.max(0, i - 1)];
        }
    }

    double beat(double atSeconds) {
        int low = 0, high = seconds.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (seconds[mid] <= atSeconds) low = mid + 1; else high = mid;
        }
        int i = Math.max(0, low - 1);
        return beats[i] + (atSeconds - seconds[i]) / rates[i];
    }

    double seconds(double beat) {
        int low = 0, high = beats.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (beats[mid] <= beat) low = mid + 1; else high = mid;
        }
        int i = Math.max(0, low - 1);
        return seconds[i] + (beat - beats[i]) * rates[i];
    }
}
