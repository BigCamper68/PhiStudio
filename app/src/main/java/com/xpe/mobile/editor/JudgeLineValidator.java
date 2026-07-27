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
        INVALID_BPM_FACTOR
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
}
