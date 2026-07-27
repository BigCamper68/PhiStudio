package com.xpe.mobile.model;

import org.json.JSONArray;
import org.json.JSONException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Objects;

public final class BeatTime implements Comparable<BeatTime> {
    public final int whole;
    public final int numerator;
    public final int denominator;

    public BeatTime(int whole, int numerator, int denominator) {
        if (denominator == 0) {
            throw new IllegalArgumentException("denominator must not be zero");
        }
        int sign = denominator < 0 ? -1 : 1;
        long improper = ((long) whole * denominator + numerator) * sign;
        int positiveDenominator = Math.abs(denominator);
        long normalizedWhole = Math.floorDiv(improper, positiveDenominator);
        long normalizedNumerator = Math.floorMod(improper, positiveDenominator);
        int gcd = gcd((int) normalizedNumerator, positiveDenominator);
        this.whole = Math.toIntExact(normalizedWhole);
        this.numerator = (int) normalizedNumerator / gcd;
        this.denominator = positiveDenominator / gcd;
    }

    public static BeatTime zero() {
        return new BeatTime(0, 0, 1);
    }

    public static BeatTime fromJson(JSONArray value) throws JSONException {
        if (value == null || value.length() < 3) {
            return zero();
        }
        return new BeatTime(value.getInt(0), value.getInt(1), value.getInt(2));
    }

    public static BeatTime fromDouble(double beats, int division) {
        int safeDivision = Math.max(1, division);
        int whole = (int) Math.floor(beats);
        int numerator = (int) Math.round((beats - whole) * safeDivision);
        if (numerator >= safeDivision) {
            whole += numerator / safeDivision;
            numerator %= safeDivision;
        }
        return new BeatTime(whole, numerator, safeDivision);
    }

    public static BeatTime parse(String text) {
        if (text == null) throw new IllegalArgumentException("beat time is required");
        String value = text.trim();
        int colon = value.indexOf(':');
        int slash = value.indexOf('/');
        if (colon <= 0 || slash <= colon + 1 || slash >= value.length() - 1
                || value.indexOf(':', colon + 1) >= 0 || value.indexOf('/', slash + 1) >= 0) {
            throw new IllegalArgumentException("beat time must use whole:numerator/denominator");
        }
        try {
            int whole = Integer.parseInt(value.substring(0, colon).trim());
            int numerator = Integer.parseInt(value.substring(colon + 1, slash).trim());
            int denominator = Integer.parseInt(value.substring(slash + 1).trim());
            return new BeatTime(whole, numerator, denominator);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("beat time must use integer components", exception);
        }
    }

    /** Parses either exact RPE whole:numerator/denominator notation or a finite decimal. */
    public static BeatTime parseFlexible(String text) {
        if (text == null) throw new IllegalArgumentException("beat time is required");
        String value = text.trim();
        if (value.indexOf(':') >= 0 || value.indexOf('/') >= 0) return parse(value);
        try {
            BigDecimal decimal = new BigDecimal(value).stripTrailingZeros();
            BigInteger improper = decimal.unscaledValue();
            int scale = decimal.scale();
            BigInteger denominator;
            if (scale < 0) {
                improper = improper.multiply(BigInteger.TEN.pow(-scale));
                denominator = BigInteger.ONE;
            } else {
                denominator = BigInteger.TEN.pow(scale);
            }
            return fromImproper(improper, denominator);
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "beat time must use whole:numerator/denominator or decimal notation",
                    exception);
        }
    }

    /** Returns the exact rational point at {@code step}/{@code steps} between two beats. */
    public static BeatTime interpolate(BeatTime start, BeatTime end, int step, int steps) {
        if (start == null || end == null) throw new IllegalArgumentException("beats are required");
        if (steps <= 0 || step < 0 || step > steps) {
            throw new IllegalArgumentException("interpolation step is out of range");
        }
        BigInteger startDenominator = BigInteger.valueOf(start.denominator);
        BigInteger endDenominator = BigInteger.valueOf(end.denominator);
        BigInteger startImproper = BigInteger.valueOf(start.whole)
                .multiply(startDenominator).add(BigInteger.valueOf(start.numerator));
        BigInteger endImproper = BigInteger.valueOf(end.whole)
                .multiply(endDenominator).add(BigInteger.valueOf(end.numerator));
        BigInteger commonStart = startImproper.multiply(endDenominator);
        BigInteger commonEnd = endImproper.multiply(startDenominator);
        BigInteger stepCount = BigInteger.valueOf(steps);
        BigInteger numerator = commonStart.multiply(stepCount)
                .add(commonEnd.subtract(commonStart).multiply(BigInteger.valueOf(step)));
        BigInteger denominator = startDenominator.multiply(endDenominator).multiply(stepCount);
        return fromImproper(numerator, denominator);
    }

    public JSONArray toJson() {
        return new JSONArray().put(whole).put(numerator).put(denominator);
    }

    public double toDouble() {
        return whole + (double) numerator / denominator;
    }

    public BeatTime plus(BeatTime other) {
        long left = ((long) whole * denominator + numerator) * other.denominator;
        long right = ((long) other.whole * other.denominator + other.numerator) * denominator;
        return fromImproper(left + right, (long) denominator * other.denominator);
    }

    public BeatTime minus(BeatTime other) {
        long left = ((long) whole * denominator + numerator) * other.denominator;
        long right = ((long) other.whole * other.denominator + other.numerator) * denominator;
        return fromImproper(left - right, (long) denominator * other.denominator);
    }

    @Override
    public int compareTo(BeatTime other) {
        int wholeComparison = Integer.compare(whole, other.whole);
        if (wholeComparison != 0) return wholeComparison;
        long left = (long) numerator * other.denominator;
        long right = (long) other.numerator * denominator;
        return Long.compare(left, right);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof BeatTime)) return false;
        BeatTime other = (BeatTime) object;
        return whole == other.whole && numerator == other.numerator && denominator == other.denominator;
    }

    @Override
    public int hashCode() {
        return Objects.hash(whole, numerator, denominator);
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "%d:%d/%d", whole, numerator, denominator);
    }

    private static int gcd(int a, int b) {
        a = Math.abs(a);
        b = Math.abs(b);
        if (a == 0) return Math.max(1, b);
        while (b != 0) {
            int temp = a % b;
            a = b;
            b = temp;
        }
        return Math.max(1, a);
    }

    private static BeatTime fromImproper(long numerator, long denominator) {
        if (denominator <= 0L || denominator > Integer.MAX_VALUE) {
            throw new ArithmeticException("beat denominator overflow");
        }
        long whole = Math.floorDiv(numerator, denominator);
        long remainder = Math.floorMod(numerator, denominator);
        return new BeatTime(Math.toIntExact(whole), Math.toIntExact(remainder),
                Math.toIntExact(denominator));
    }

    private static BeatTime fromImproper(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() <= 0) throw new ArithmeticException("invalid beat denominator");
        BigInteger gcd = numerator.gcd(denominator);
        BigInteger reducedNumerator = numerator.divide(gcd);
        BigInteger reducedDenominator = denominator.divide(gcd);
        if (reducedDenominator.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new ArithmeticException("beat denominator overflow");
        }
        BigInteger[] wholeAndRemainder = reducedNumerator.divideAndRemainder(reducedDenominator);
        if (wholeAndRemainder[1].signum() < 0) {
            wholeAndRemainder[0] = wholeAndRemainder[0].subtract(BigInteger.ONE);
            wholeAndRemainder[1] = wholeAndRemainder[1].add(reducedDenominator);
        }
        return new BeatTime(exactInt(wholeAndRemainder[0]),
                exactInt(wholeAndRemainder[1]), exactInt(reducedDenominator));
    }

    private static int exactInt(BigInteger value) {
        if (value.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                || value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new ArithmeticException("beat component overflow");
        }
        return value.intValue();
    }
}
