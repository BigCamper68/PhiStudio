package com.xpe.mobile.editor;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BatchValueTransformTest {
    @Test
    public void createsEasedPeriodicValuesInChronologicalIndexOrder() {
        BatchValueTransform.Spec spec = new BatchValueTransform.Spec();
        spec.lowerBound = 0.0;
        spec.upperBound = 4.0;
        spec.easingType = 1;
        spec.periodicSequence = new double[]{1.0, -1.0};

        assertArrayEquals(new double[]{0.0, -1.0, 2.0, -3.0, 4.0},
                BatchValueTransform.values(spec, 5), 1.0e-9);
    }

    @Test
    public void supportsEveryManualCombinationMode() {
        assertEquals(13.0, BatchValueTransform.apply(
                BatchValueTransform.Mode.BY, 10.0, 3.0), 0.0);
        assertEquals(3.0, BatchValueTransform.apply(
                BatchValueTransform.Mode.TO, 10.0, 3.0), 0.0);
        assertEquals(30.0, BatchValueTransform.apply(
                BatchValueTransform.Mode.TIMES, 10.0, 3.0), 0.0);
        assertEquals(10.0, BatchValueTransform.apply(
                BatchValueTransform.Mode.MAX, 10.0, 3.0), 0.0);
        assertEquals(3.0, BatchValueTransform.apply(
                BatchValueTransform.Mode.MIN, 10.0, 3.0), 0.0);
    }

    @Test
    public void parsesFiniteSequencesAndRejectsEmptyOnes() {
        assertArrayEquals(new double[]{1.0, -2.0, 0.5},
                BatchValueTransform.parseSequence("1, -2; 0.5"), 0.0);
        assertTrue(BatchValueTransform.isValid(validSpec()));
        BatchValueTransform.Spec invalid = validSpec();
        invalid.periodicSequence = new double[0];
        assertFalse(BatchValueTransform.isValid(invalid));
    }

    private static BatchValueTransform.Spec validSpec() {
        BatchValueTransform.Spec spec = new BatchValueTransform.Spec();
        spec.lowerBound = 0.0;
        spec.upperBound = 1.0;
        return spec;
    }
}
