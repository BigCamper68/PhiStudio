package com.xpe.mobile.editor;

import com.xpe.mobile.model.Easing;

import java.util.Random;

/** Pure value profile shared by advanced note/event batch editing and Event Clone. */
public final class BatchValueTransform {
    public static final int MAX_SEQUENCE_LENGTH = 256;

    public enum Mode {
        BY,
        TO,
        TIMES,
        MAX,
        MIN
    }

    public static final class Spec {
        public double lowerBound;
        public double upperBound;
        public int easingType = 1;
        public double[] periodicSequence = new double[]{1.0};
        public double disturbance;
        public long randomSeed;

        public Spec copy() {
            Spec copy = new Spec();
            copy.lowerBound = lowerBound;
            copy.upperBound = upperBound;
            copy.easingType = easingType;
            copy.periodicSequence = periodicSequence == null
                    ? null : periodicSequence.clone();
            copy.disturbance = disturbance;
            copy.randomSeed = randomSeed;
            return copy;
        }
    }

    private BatchValueTransform() {
    }

    public static boolean isValid(Spec spec) {
        if (spec == null || !Double.isFinite(spec.lowerBound)
                || !Double.isFinite(spec.upperBound)
                || !Double.isFinite(spec.disturbance)
                || spec.easingType < Easing.MIN_TYPE
                || spec.easingType > Easing.MAX_TYPE
                || spec.periodicSequence == null
                || spec.periodicSequence.length == 0
                || spec.periodicSequence.length > MAX_SEQUENCE_LENGTH) {
            return false;
        }
        for (double value : spec.periodicSequence) {
            if (!Double.isFinite(value)) return false;
        }
        return true;
    }

    public static double[] values(Spec spec, int count) {
        if (!isValid(spec) || count < 0) {
            throw new IllegalArgumentException("invalid batch value profile");
        }
        double[] result = new double[count];
        Random random = new Random(spec.randomSeed);
        double disturbance = Math.abs(spec.disturbance);
        for (int index = 0; index < count; index++) {
            double input = count <= 1 ? 0.0 : index / (double) (count - 1);
            double eased = Easing.apply(spec.easingType, input);
            double base = spec.lowerBound
                    + (spec.upperBound - spec.lowerBound) * eased;
            double noise = disturbance == 0.0
                    ? 0.0 : (random.nextDouble() * 2.0 - 1.0) * disturbance;
            double periodic = spec.periodicSequence[index % spec.periodicSequence.length];
            result[index] = (base + noise) * periodic;
            if (!Double.isFinite(result[index])) {
                throw new IllegalArgumentException("batch value is not finite");
            }
        }
        return result;
    }

    public static double apply(Mode mode, double current, double generated) {
        if (mode == null || !Double.isFinite(current) || !Double.isFinite(generated)) {
            throw new IllegalArgumentException("invalid batch operation");
        }
        double result;
        switch (mode) {
            case BY: result = current + generated; break;
            case TO: result = generated; break;
            case TIMES: result = current * generated; break;
            case MAX: result = Math.max(current, generated); break;
            case MIN: result = Math.min(current, generated); break;
            default: throw new IllegalArgumentException("unknown batch mode");
        }
        if (!Double.isFinite(result)) throw new IllegalArgumentException("batch result is not finite");
        return result;
    }

    public static double[] parseSequence(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("periodic sequence is required");
        }
        String[] parts = text.trim().split("[\\s,;]+", -1);
        if (parts.length == 0 || parts.length > MAX_SEQUENCE_LENGTH) {
            throw new IllegalArgumentException("periodic sequence is too long");
        }
        double[] result = new double[parts.length];
        try {
            for (int index = 0; index < parts.length; index++) {
                result[index] = Double.parseDouble(parts[index]);
                if (!Double.isFinite(result[index])) {
                    throw new IllegalArgumentException("periodic sequence must be finite");
                }
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid periodic sequence", exception);
        }
        return result;
    }
}
