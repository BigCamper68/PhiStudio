package com.xpe.mobile.preview;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.preview.PreviewChartIndex.PreparedChart;
import java.util.List;

/** PreviewLineEvaluator responsibilities extracted from ChartEvaluator. */
final class PreviewLineEvaluator {
    static boolean farOutsideScene(ResolvedLine line, double x, double start, double end,
                                           double offset, boolean above, boolean hold, double size,
                                           boolean uniform, double yScale, double inclineSin) {
        double sx = x;
        if (!hold) sx *= 1 - inclineSin * start / 360;
        if (hold && uniform) {
            start = offset + (start - offset) * size;
            end = offset + (end - offset) * size;
        }
        double side = above ? 1 : -1;
        double cos = line.cos;
        double sin = line.sin;
        double ax = line.x + cos * sx - sin * start * yScale * side;
        double ay = line.y * yScale + sin * sx + cos * start * yScale * side;
        double bx = hold ? line.x + cos * sx - sin * end * yScale * side : ax;
        double by = hold ? line.y * yScale + sin * sx + cos * end * yScale * side : ay;
        double margin = 6000 + 400 * Math.max(1, Math.abs(size));
        return Math.min(ax, bx) > margin || Math.max(ax, bx) < -margin
                || Math.min(ay, by) > margin * Math.max(1, yScale)
                || Math.max(ay, by) < -margin * Math.max(1, yScale);
    }

    static double lineScaleAt(JudgeLine line, ExtendedLineEvents extended,
                                      boolean horizontal, double beat, PrprTime time) {
        return lineScaleAtSeconds(line, extended, horizontal, time.seconds(beat), time);
    }

    static double lineScaleAtSeconds(JudgeLine line, ExtendedLineEvents extended,
                                     boolean horizontal, double seconds, PrprTime time) {
        List<ExtendedLineEvents.NumericEvent> events = horizontal
                ? extended.scaleXEvents : extended.scaleYEvents;
        if (events.isEmpty()) return 1.0;
        double raw = ChartEvaluator.finiteOr(PrprEvents.numericAtSeconds(events, time, seconds, 1.0, true), 1.0);
        boolean defaultTexture = line.texture == null || line.texture.trim().isEmpty()
                || "line.png".equalsIgnoreCase(line.texture.trim());
        if (!defaultTexture) return raw;
        boolean ordinaryOrPaint = line.attachUi == null && extended.textEvents.isEmpty();
        return horizontal && ordinaryOrPaint ? raw * 0.5 : raw;
    }

    static void resolveLine(ChartDocument chart, int index, LocalLine[] local,
                                    ResolvedLine[] resolved, int[] state, double localYScale) {
        if (state[index] == 2) return;
        LocalLine own = local[index];
        if (state[index] == 1) {
            resolved[index] = new ResolvedLine(own.x, own.y, own.rotationDegrees);
            state[index] = 2;
            return;
        }
        state[index] = 1;
        JudgeLine line = chart.judgeLines.get(index);
        int parentIndex = line.father;
        if (parentIndex < 0 || parentIndex >= chart.judgeLines.size()
                || parentIndex == index || state[parentIndex] == 1) {
            resolved[index] = new ResolvedLine(own.x, own.y, own.rotationDegrees);
        } else {
            PreviewLineEvaluator.resolveLine(chart, parentIndex, local, resolved, state, localYScale);
            ResolvedLine parent = resolved[parentIndex];
            double radians = Math.toRadians(parent.rotationDegrees);
            double rotatedX = own.x * Math.cos(radians) - own.y * Math.sin(radians) * localYScale;
            double rotatedY = own.x * Math.sin(radians) / localYScale + own.y * Math.cos(radians);
            resolved[index] = new ResolvedLine(parent.x + rotatedX, parent.y + rotatedY,
                    own.rotationDegrees + (line.rotateWithFather
                            ? parent.rotationDegrees : 0.0));
        }
        state[index] = 2;
    }

    static ResolvedLine resolveSingleLineAt(ChartDocument chart, PreparedChart prepared, int index,
                                                    double beat, boolean[] visiting, double localYScale) {
        return resolveSingleLineAtSeconds(chart, prepared, index, prepared.time.seconds(beat),
                visiting, localYScale);
    }

    static ResolvedLine resolveSingleLineAtSeconds(ChartDocument chart, PreparedChart prepared, int index,
                                                  double seconds, boolean[] visiting, double localYScale) {
        JudgeLine line = chart.judgeLines.get(index);
        double beat = prepared.time.beat(seconds);
        ResolvedLine own = new ResolvedLine(
                prepared.lines[index].valueAt(EventType.MOVE_X, beat, seconds),
                prepared.lines[index].valueAt(EventType.MOVE_Y, beat, seconds),
                -prepared.lines[index].valueAt(EventType.ROTATE, beat, seconds));
        int parentIndex = line.father;
        if (parentIndex < 0 || parentIndex >= chart.judgeLines.size()
                || parentIndex == index || visiting[index]) return own;
        visiting[index] = true;
        ResolvedLine parent = PreviewLineEvaluator.resolveSingleLineAtSeconds(chart, prepared, parentIndex, seconds, visiting, localYScale);
        visiting[index] = false;
        double radians = Math.toRadians(parent.rotationDegrees);
        double rotatedX = own.x * Math.cos(radians) - own.y * Math.sin(radians) * localYScale;
        double rotatedY = own.x * Math.sin(radians) / localYScale + own.y * Math.cos(radians);
        return new ResolvedLine(parent.x + rotatedX, parent.y + rotatedY,
                own.rotationDegrees + (line.rotateWithFather
                        ? parent.rotationDegrees : 0.0));
    }

    static final class LocalLine {
        final double x;
        final double y;
        final double rotationDegrees;
        final double rawAlpha;

        LocalLine(double x, double y, double rotationDegrees, double rawAlpha) {
            this.x = x;
            this.y = y;
            this.rotationDegrees = rotationDegrees;
            this.rawAlpha = rawAlpha;
        }
    }

    static final class AlphaExtension {
        final boolean hideLineAndNotes;
        final boolean cover;
        final double appearBeforeBeats;

        AlphaExtension(boolean hideLineAndNotes, boolean cover,
                       double appearBeforeBeats) {
            this.hideLineAndNotes = hideLineAndNotes;
            this.cover = cover;
            this.appearBeforeBeats = appearBeforeBeats;
        }

        static AlphaExtension from(double rawAlpha, boolean sourceCover) {
            return from(rawAlpha, sourceCover, false);
        }

        static AlphaExtension from(double rawAlpha, boolean sourceCover, boolean pecOrigin) {
            if (!Double.isFinite(rawAlpha) || rawAlpha >= 0.0) {
                return new AlphaExtension(false, sourceCover,
                        Double.POSITIVE_INFINITY);
            }
            if (pecOrigin) {
                double flag = Math.floor(-rawAlpha);
                if (flag == 1.0) return new AlphaExtension(true, sourceCover, Double.POSITIVE_INFINITY);
                if (flag == 2.0) return new AlphaExtension(false, true, Double.POSITIVE_INFINITY);
                if (flag >= 100.0 && flag < 1000.0) {
                    return new AlphaExtension(false, sourceCover, (flag - 100.0) / 10.0);
                }
                return new AlphaExtension(false, sourceCover, Double.POSITIVE_INFINITY);
            }
            // Negative RPE alpha hides the line and note geometry. Judgement effects
            // and audio remain independent of this visual visibility flag.
            return new AlphaExtension(true, sourceCover, Double.POSITIVE_INFINITY);
        }
    }

    static final class ResolvedLine {
        final double x;
        final double y;
        final double rotationDegrees;
        final double cos, sin;

        ResolvedLine(double x, double y, double rotationDegrees) {
            this.x = x;
            this.y = y;
            this.rotationDegrees = rotationDegrees;
            cos = Math.cos(Math.toRadians(rotationDegrees));
            sin = Math.sin(Math.toRadians(rotationDegrees));
        }
    }
}
