package com.xpe.mobile.editor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class MathExpressionTest {
    @Test
    public void supportsManualVariablesConstantsOperatorsAndFunctions() {
        MathExpression.Compiled circle = MathExpression.compile(
                "300*cos($t$*2*Pi) + min(10, 20)");
        assertEquals(310.0, circle.evaluate(0.0), 1.0e-9);
        assertEquals(-290.0, circle.evaluate(0.5), 1.0e-9);

        MathExpression.Compiled manualExample = MathExpression.compile(
                "min(100-200*t, 300*sin(t*25))");
        assertEquals(0.0, manualExample.evaluate(0.0), 1.0e-9);
    }

    @Test
    public void appliesPowerUnaryAndMultiArgumentFunctionsWithExpectedPrecedence() {
        assertEquals(-4.0, MathExpression.compile("-2^2").evaluate(0.0), 0.0);
        assertEquals(0.25, MathExpression.compile("2^-2").evaluate(0.0), 0.0);
        assertEquals(4.0, MathExpression.compile(
                "max(1, 4, 2) + clamp(8, 0, 3) - 3").evaluate(0.0), 0.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownFunctions() {
        MathExpression.compile("mystery(t)");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonFiniteEvaluation() {
        MathExpression.compile("1/(t-t)").evaluate(0.5);
    }
}
