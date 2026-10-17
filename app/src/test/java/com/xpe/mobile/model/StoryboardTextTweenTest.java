package com.xpe.mobile.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Expected strings are from upstream prpr's unmodified String::tween, using rustc 1.90. */
public final class StoryboardTextTweenTest {
    @Test
    public void roundsUnicodeRevealAtFloat32Boundary() {
        // At 5s in a 6s transition, the f32 product is exactly 2.5 glyphs.
        assertEquals("𝄞中文", StoryboardTextTween.tween("", "𝄞中文", (double) (5.0f / 6.0f)));
        assertEquals("𝄞中文", StoryboardTextTween.tween("𝄞中文", "", (double) (1.0f / 6.0f)));
        assertEquals("𝄞中", StoryboardTextTween.tween("𝄞", "𝄞中文", 0.5));
        assertEquals("𝄞中文", StoryboardTextTween.tween("𝄞中文", "𝄞", 0.25));
    }

    @Test
    public void countsUnicodeScalarsInsteadOfUtf16UnitsOrGraphemes() {
        assertEquals("𝄞", StoryboardTextTween.tween("", "𝄞中文", 0.2));
        assertEquals("a", StoryboardTextTween.tween("", "a\u0301", 0.5));
        assertEquals("𝄞中", StoryboardTextTween.tween("𝄞中文", "𝄞", 0.75));
        assertEquals("𝄞", StoryboardTextTween.tween("𝄞", "𝄞中文", -0.5));
    }

    @Test
    public void formatsNumericHalfwayValuesLikeRust() {
        assertNumeric("0", "1", 0.5, "0");
        assertNumeric("1", "2", 0.5, "2");
        assertNumeric("-1", "0", 0.5, "-0");
        assertNumeric("-2", "-1", 0.5, "-2");
        assertNumeric("0", "0.125", 0.5, "0.062");
        assertNumeric("0.125", "0.25", 0.5, "0.188");
        assertNumeric("-0.125", "0", 0.5, "-0.062");
        assertNumeric("-0.0", "-0.0002", 0.25, "-0.000");
    }

    @Test
    public void parsesEndpointsAndEvaluatesEveryIntermediateAsFloat32() {
        assertNumeric("16777217", "16777219", 0.25, "16777216");
        assertNumeric("3.0000001", "5", 0.25, "4");
        assertNumeric("1.5", "2.5", 0.5, "2.000");
    }

    @Test
    public void usesRustDecimalGrammarAndInvalidNumberFallback() {
        assertNumeric("1", "bad", 0.5, "0");
        for (String invalid : new String[]{"1 ", " 1", "0x1p0", "1f", "1d", "1_0"}) {
            assertNumeric(invalid, "2", 0.5, "1");
        }
        assertNumeric(".5", "5.", 0.5, "2.750");
        assertNumeric("1e1", "+2E1", 0.5, "15");
        assertNumeric("inf", "1", 0.5, "NaN");
        assertNumeric("nan", "1", 0.5, "NaN");
        assertNumeric("1e99", "-1e99", 0.5, "NaN");
    }

    @Test
    public void keepsEndpointSpellingAndOriginalMarkerBranchRules() {
        assertNumeric("001.250", "002.500", 0.0, "001.250");
        assertNumeric("001.250", "002.500", 1.0, "002.500");
        assertEquals("12", StoryboardTextTween.tween("12%P%", "", 0.25));
        assertEquals("12%", StoryboardTextTween.tween("", "12%P%", 0.5));
        assertEquals("12", StoryboardTextTween.tween("12%P%", "other", 0.5));
        assertEquals("old", StoryboardTextTween.tween("old", "new", 0.9));
        assertEquals("", StoryboardTextTween.tween(null, null, 0.5));
    }

    private static void assertNumeric(String start, String end, double progress, String expected) {
        assertEquals(start + " -> " + end + " at " + progress, expected,
                StoryboardTextTween.tween(start + "%P%", end + "%P%", progress));
    }
}
