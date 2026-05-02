package edu.illinois.group8.canonical;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class FixedPoint {
    public static final long PRICE_SCALE = 1_000_000L;
    public static final long QUANTITY_SCALE = 1_000_000L;

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
        return BigDecimal.valueOf(cents.longValue())
            .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(PRICE_SCALE))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact();
    }

    public static Long quantityCountToMicros(Number count) {
        if (count == null) {
            return null;
        }
        return BigDecimal.valueOf(count.longValue())
            .multiply(BigDecimal.valueOf(QUANTITY_SCALE))
            .longValueExact();
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
        return new BigDecimal(value.trim())
            .multiply(BigDecimal.valueOf(scale))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact();
    }
}
