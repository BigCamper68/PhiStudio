package com.xpe.mobile.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class AttachedUiElementTest {
    @Test
    public void parsesCurrentAndToleratedRpeSpellings() {
        assertEquals(AttachedUiElement.PAUSE,
                AttachedUiElement.fromJsonValue("pause"));
        assertEquals(AttachedUiElement.COMBO_NUMBER,
                AttachedUiElement.fromJsonValue("comboNumber"));
        assertEquals(AttachedUiElement.COMBO_NUMBER,
                AttachedUiElement.fromJsonValue("combo_number"));
        assertEquals(AttachedUiElement.SCORE,
                AttachedUiElement.fromJsonValue(" SCORE "));
        assertNull(AttachedUiElement.fromJsonValue("futureHudElement"));
        assertNull(AttachedUiElement.fromJsonValue(4));
    }
}
