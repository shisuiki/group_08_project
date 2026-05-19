package edu.illinois.group8.canonical;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class FixedPoint {
    public static final long PRICE_SCALE = 1_000_000L;
    public static final long QUANTITY_SCALE = 1_000_000L;
    private static final int SCALE_DECIMAL_PLACES = 6;

    private FixedPoint() {
    }

    public static Long priceDollarsToMicros(String value) {
        return decimalStringToScaledLong(value, PRICE_SCALE);
    }

    public static Long quantityToMicros(String value) {
        return decimalStringToScaledLong(value, QUANTITY_SCALE);
    }

    public static Long centsToPriceMicros(Number cents) {
        if (cents == null) {
            return null;
        }
        return Math.multiplyExact(cents.longValue(), PRICE_SCALE / 100L);
    }

    public static Long quantityCountToMicros(Number count) {
        if (count == null) {
            return null;
        }
        return Math.multiplyExact(count.longValue(), QUANTITY_SCALE);
    }

    public static String microsToDollarString(long micros) {
        return BigDecimal.valueOf(micros)
            .divide(BigDecimal.valueOf(PRICE_SCALE), 6, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString();
    }

    private static Long decimalStringToScaledLong(String value, long scale) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int start = trimStart(value);
        int end = trimEnd(value);
        try {
            Long simpleDecimal = simpleDecimalStringToScaledLong(value, start, end, scale);
            if (simpleDecimal != null) {
                return simpleDecimal;
            }
        } catch (ArithmeticException overflow) {
            return bigDecimalStringToScaledLong(value, scale);
        }
        return bigDecimalStringToScaledLong(value, scale);
    }

    private static Long bigDecimalStringToScaledLong(String value, long scale) {
        return new BigDecimal(value.trim())
            .multiply(BigDecimal.valueOf(scale))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact();
    }

    private static Long simpleDecimalStringToScaledLong(String value, int start, int end, long scale) {
        int index = start;
        boolean negative = false;
        if (index < end) {
            char sign = value.charAt(index);
            if (sign == '-' || sign == '+') {
                negative = sign == '-';
                index++;
            }
        }

        long integer = 0L;
        long fraction = 0L;
        int fractionDigits = 0;
        int roundingDigit = -1;
        boolean afterDecimal = false;
        boolean sawDigit = false;

        while (index < end) {
            char ch = value.charAt(index++);
            if (ch == '.') {
                if (afterDecimal) {
                    return null;
                }
                afterDecimal = true;
                continue;
            }
            if (ch < '0' || ch > '9') {
                return null;
            }
            sawDigit = true;
            int digit = ch - '0';
            if (!afterDecimal) {
                integer = Math.addExact(Math.multiplyExact(integer, 10L), digit);
            } else if (fractionDigits < SCALE_DECIMAL_PLACES) {
                fraction = Math.addExact(Math.multiplyExact(fraction, 10L), digit);
                fractionDigits++;
            } else if (roundingDigit < 0) {
                roundingDigit = digit;
            }
        }

        if (!sawDigit) {
            return null;
        }

        while (fractionDigits < SCALE_DECIMAL_PLACES) {
            fraction = Math.multiplyExact(fraction, 10L);
            fractionDigits++;
        }

        long scaled = Math.addExact(Math.multiplyExact(integer, scale), fraction);
        if (roundingDigit >= 5) {
            scaled = Math.addExact(scaled, 1L);
        }
        return negative ? Math.negateExact(scaled) : scaled;
    }

    private static int trimStart(String value) {
        int index = 0;
        while (index < value.length() && value.charAt(index) <= ' ') {
            index++;
        }
        return index;
    }

    private static int trimEnd(String value) {
        int index = value.length();
        while (index > 0 && value.charAt(index - 1) <= ' ') {
            index--;
        }
        return index;
    }
}
