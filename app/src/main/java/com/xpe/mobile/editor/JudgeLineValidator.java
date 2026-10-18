package com.xpe.mobile.editor;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;

public final class JudgeLineValidator {
    public enum Error {
        NONE,
        TARGET_NOT_FOUND,
        LINE_ZERO_PROTECTED,
        LAST_LINE_REQUIRED,
        INVALID_NAME,
        INVALID_TEXTURE,
        INVALID_GROUP,
        INVALID_BPM_FACTOR,
        INVALID_PARENT
    }

    private JudgeLineValidator() {
    }

    public static Error validateProperties(JudgeLine line) {
        if (line == null) return Error.TARGET_NOT_FOUND;
        if (line.name == null || line.name.trim().isEmpty()) return Error.INVALID_NAME;
        if (line.texture == null || line.texture.trim().isEmpty()) return Error.INVALID_TEXTURE;
        if (line.group < 0) return Error.INVALID_GROUP;
        if (!Double.isFinite(line.bpmFactor) || line.bpmFactor <= 0.0) {
            return Error.INVALID_BPM_FACTOR;
        }
        return Error.NONE;
    }

    public static Error validateDelete(ChartDocument chart, int index) {
        if (chart == null || index < 0 || index >= chart.judgeLines.size()) {
            return Error.TARGET_NOT_FOUND;
        }
        if (index == 0) return Error.LINE_ZERO_PROTECTED;
        if (chart.judgeLines.size() <= 1) return Error.LAST_LINE_REQUIRED;
        return Error.NONE;
    }

    public static Error validateProperties(ChartDocument chart, JudgeLine target, JudgeLine edited) {
        if (chart == null || target == null || !chart.judgeLines.contains(target)) {
            return Error.TARGET_NOT_FOUND;
        }
        Error properties = validateProperties(edited);
        return properties == Error.NONE ? validateParent(chart, target, edited.father) : properties;
    }

    public static Error validateParent(ChartDocument chart, JudgeLine target, int parent) {
        if (chart == null || target == null || !chart.judgeLines.contains(target)) {
            return Error.TARGET_NOT_FOUND;
        }
        boolean[] visited = new boolean[chart.judgeLines.size()];
        while (parent != -1) {
            if (parent < 0 || parent >= visited.length || visited[parent]
                    || chart.judgeLines.get(parent) == target) {
                return Error.INVALID_PARENT;
            }
            visited[parent] = true;
            parent = chart.judgeLines.get(parent).father;
        }
        return Error.NONE;
    }
}
