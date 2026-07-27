package com.xpe.mobile.editor;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;

import java.util.List;

public final class JudgeLineCommand {
    private JudgeLineCommand() {
    }

    public static EditHistory.Command add(ChartDocument chart, JudgeLine line, int index) {
        int safeIndex = Math.max(0, Math.min(index, chart.judgeLines.size()));
        int[] beforeFathers = fathers(chart.judgeLines);
        int[] afterFathers = new int[beforeFathers.length + 1];
        for (int oldIndex = 0; oldIndex < beforeFathers.length; oldIndex++) {
            int newIndex = oldIndex < safeIndex ? oldIndex : oldIndex + 1;
            int father = beforeFathers[oldIndex];
            afterFathers[newIndex] = father >= safeIndex ? father + 1 : father;
        }
        afterFathers[safeIndex] = line.father;
        return new EditHistory.Command() {
            @Override
            public void apply() {
                if (!chart.judgeLines.contains(line)) chart.judgeLines.add(safeIndex, line);
                applyFathers(chart.judgeLines, afterFathers);
            }

            @Override
            public void revert() {
                chart.judgeLines.remove(line);
                applyFathers(chart.judgeLines, beforeFathers);
            }
        };
    }

    public static EditHistory.Command delete(ChartDocument chart, int index) {
        JudgeLine target = chart.judgeLines.get(index);
        int[] beforeFathers = fathers(chart.judgeLines);
        int[] afterFathers = new int[beforeFathers.length - 1];
        for (int oldIndex = 0; oldIndex < beforeFathers.length; oldIndex++) {
            if (oldIndex == index) continue;
            int father = beforeFathers[oldIndex];
            int adjusted = father == index ? -1 : father > index ? father - 1 : father;
            afterFathers[oldIndex < index ? oldIndex : oldIndex - 1] = adjusted;
        }
        return new EditHistory.Command() {
            @Override
            public void apply() {
                chart.judgeLines.remove(target);
                applyFathers(chart.judgeLines, afterFathers);
            }

            @Override
            public void revert() {
                if (!chart.judgeLines.contains(target)) chart.judgeLines.add(index, target);
                applyFathers(chart.judgeLines, beforeFathers);
            }
        };
    }

    public static EditHistory.Command edit(JudgeLine target, JudgeLine before, JudgeLine after) {
        return new EditHistory.Command() {
            @Override
            public void apply() {
                copyProperties(after, target);
            }

            @Override
            public void revert() {
                copyProperties(before, target);
            }
        };
    }

    private static int[] fathers(List<JudgeLine> lines) {
        int[] values = new int[lines.size()];
        for (int index = 0; index < lines.size(); index++) values[index] = lines.get(index).father;
        return values;
    }

    private static void applyFathers(List<JudgeLine> lines, int[] values) {
        for (int index = 0; index < lines.size(); index++) lines.get(index).father = values[index];
    }

    private static void copyProperties(JudgeLine source, JudgeLine target) {
        target.group = source.group;
        target.name = source.name;
        target.texture = source.texture;
        target.bpmFactor = source.bpmFactor;
        target.cover = source.cover;
        target.zOrder = source.zOrder;
    }
}
