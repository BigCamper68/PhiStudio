package com.xpe.mobile.preview;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class PhiraJudgeLineStyleTest {
    @Test public void thicknessUsesGameplayViewportAndSignedScaleBranch() {
        // prpr line.rs: 0.01 or 0.0076 world units; camera width spans two units.
        assertEquals(9.6f, PhiraJudgeLineStyle.normalThickness(1920, 1), 1e-5);
        assertEquals(19.2f, PhiraJudgeLineStyle.normalThickness(3840, 1), 1e-5);
        assertEquals(3.648f, PhiraJudgeLineStyle.normalThickness(1920, .5), 1e-5);
        assertEquals(14.592f, PhiraJudgeLineStyle.normalThickness(1920, 2), 1e-5);
        assertEquals(7.296f, PhiraJudgeLineStyle.normalThickness(1920, -1), 1e-5);
        assertEquals(0f, PhiraJudgeLineStyle.normalThickness(1920, 0), 0);
        assertEquals(9.60048f, PhiraJudgeLineStyle.normalThickness(1920, 1.00005), 1e-5);
        assertEquals(7.2974592f, PhiraJudgeLineStyle.normalThickness(1920, 1.0002), 1e-5);
    }

    @Test public void endpointsMatchDefaultPrprLengthAndMirroredScale() {
        assertEquals(5760f, PhiraJudgeLineStyle.normalHalfLength(1920, 1), 0);
        assertEquals(2880f, PhiraJudgeLineStyle.normalHalfLength(1920, -.5), 0);
        assertEquals(0f, PhiraJudgeLineStyle.normalHalfLength(1920, 0), 0);
    }

    @Test public void autoplayColorIncludesResourcePackAlphaAndAuthoredOverride() {
        assertEquals(0xE1FFEC9F, color(-1, 255, true, false));
        assertEquals(0x71FFEC9F, color(-1, 128, true, false));
        assertEquals(0x00FFEC9F, color(-1, 0, true, false));
        assertEquals(0x80000000, color(0x000000, 128, true, false));
        assertEquals(0xFF123456, color(0x123456, 255, true, false));
        // Textures, GIFs, text and paint do not inherit the gold AP color.
        assertEquals(0xFFFFFFFF, color(-1, 255, false, false));
        assertEquals(0x80FFFFFF, color(-1, 128, false, false));
        assertEquals(0x80123456, color(0x123456, 128, false, false));
    }

    @Test public void disablingEditorHighlightRestoresBothDefaultAndAuthoredColors() {
        assertEquals(0xFF5BD3AC, color(-1, 255, true, true));
        assertEquals(0xFF5BD3AC, color(0x123456, 255, true, true));
        assertEquals(0x805BD3AC, color(0x123456, 128, false, true));
        assertEquals(0xE1FFEC9F, color(-1, 255, true, false));
        assertEquals(0xFF123456, color(0x123456, 255, true, false));
        assertEquals(0xFFFFFFFF, color(-1, 255, false, false));
    }

    private static int color(int authored, int alpha, boolean normal, boolean highlight) {
        return PhiraJudgeLineStyle.color(authored, alpha, normal, highlight, 0x5BD3AC);
    }
}
