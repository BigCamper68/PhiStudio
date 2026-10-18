package com.xpe.mobile.editor;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class JudgeLineValidatorTest {
    private ChartDocument chart() {
        ChartDocument chart = new ChartDocument();
        chart.judgeLines.clear();
        for (int index = 0; index < 4; index++) chart.judgeLines.add(new JudgeLine());
        return chart;
    }

    @Test public void acceptsNoParentAndNestedParentsIncludingLineZero() {
        ChartDocument chart = chart();
        chart.judgeLines.get(1).father = 0;
        JudgeLine target = chart.judgeLines.get(3);
        JudgeLine edited = target.copyProperties();
        assertEquals(JudgeLineValidator.Error.NONE,
                JudgeLineValidator.validateProperties(chart, target, edited));
        edited.father = 1;
        assertEquals(JudgeLineValidator.Error.NONE,
                JudgeLineValidator.validateProperties(chart, target, edited));
        assertEquals(JudgeLineValidator.Error.NONE,
                JudgeLineValidator.validateParent(chart, chart.judgeLines.get(0), 2));
    }

    @Test public void rejectsSelfDescendantsAndCyclesAlreadyInTheParentChain() {
        ChartDocument chart = chart();
        JudgeLine target = chart.judgeLines.get(0);
        chart.judgeLines.get(1).father = 0;
        chart.judgeLines.get(2).father = 1;
        for (int parent : new int[]{0, 1, 2}) {
            assertEquals(JudgeLineValidator.Error.INVALID_PARENT,
                    JudgeLineValidator.validateParent(chart, target, parent));
        }
        chart.judgeLines.get(1).father = 2;
        assertEquals(JudgeLineValidator.Error.INVALID_PARENT,
                JudgeLineValidator.validateParent(chart, target, 1));
        assertEquals(JudgeLineValidator.Error.NONE,
                JudgeLineValidator.validateParent(chart, target, -1));
    }

    @Test public void rejectsMissingParentsAndStaleTargets() {
        ChartDocument chart = chart();
        JudgeLine target = chart.judgeLines.get(0);
        for (int parent : new int[]{-2, 4, Integer.MAX_VALUE}) {
            assertEquals(JudgeLineValidator.Error.INVALID_PARENT,
                    JudgeLineValidator.validateParent(chart, target, parent));
        }
        chart.judgeLines.get(1).father = 99;
        assertEquals(JudgeLineValidator.Error.INVALID_PARENT,
                JudgeLineValidator.validateParent(chart, target, 1));
        chart.judgeLines.remove(target);
        assertEquals(JudgeLineValidator.Error.TARGET_NOT_FOUND,
                JudgeLineValidator.validateProperties(chart, target, target.copyProperties()));
    }
}
