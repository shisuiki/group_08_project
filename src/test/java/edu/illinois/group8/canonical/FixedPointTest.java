package edu.illinois.group8.canonical;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FixedPointTest {
    @Test
    void priceDollarsToMicrosScalesSimpleDecimals() {
        assertEquals(560_000L, FixedPoint.priceDollarsToMicros("0.5600"));
        assertEquals(136_000_000L, FixedPoint.priceDollarsToMicros("136.00"));
        assertEquals(-54_000_000L, FixedPoint.priceDollarsToMicros("-54.00"));
    }

    @Test
    void decimalStringsRoundHalfUpAtMicrosScale() {
        assertEquals(123_456L, FixedPoint.priceDollarsToMicros("0.1234564"));
        assertEquals(123_457L, FixedPoint.priceDollarsToMicros("0.1234565"));
        assertEquals(-123_457L, FixedPoint.priceDollarsToMicros("-0.1234565"));
    }

    @Test
    void decimalStringsSupportCommonForms() {
        assertEquals(500_000L, FixedPoint.priceDollarsToMicros(".5"));
        assertEquals(1_000_000L, FixedPoint.priceDollarsToMicros("1."));
        assertEquals(220_000L, FixedPoint.priceDollarsToMicros(" 0.2200 "));
        assertEquals(220_000L, FixedPoint.priceDollarsToMicros("+0.2200"));
        assertEquals(5_000_000L, FixedPoint.quantityToMicros("5.0"));
    }

    @Test
    void decimalStringsHandleNullAndBlank() {
        assertNull(FixedPoint.priceDollarsToMicros(null));
        assertNull(FixedPoint.priceDollarsToMicros(""));
        assertNull(FixedPoint.quantityToMicros("   "));
    }

    @Test
    void decimalStringsFallbackKeepsScientificNotationCompatibility() {
        assertEquals(1L, FixedPoint.priceDollarsToMicros("1e-6"));
        assertEquals(100_000_000L, FixedPoint.quantityToMicros("1E2"));
    }

    @Test
    void invalidDecimalStringsStillThrow() {
        assertThrows(NumberFormatException.class, () -> FixedPoint.priceDollarsToMicros("abc"));
        assertThrows(NumberFormatException.class, () -> FixedPoint.quantityToMicros("1.2.3"));
    }

    @Test
    void scaledDecimalOverflowIsVisible() {
        assertThrows(ArithmeticException.class, () -> FixedPoint.priceDollarsToMicros("9223372036855"));
    }

    @Test
    void centsToPriceMicrosUsesIntegerArithmetic() {
        assertNull(FixedPoint.centsToPriceMicros(null));
        assertEquals(560_000L, FixedPoint.centsToPriceMicros(56));
        assertEquals(10_000L, FixedPoint.centsToPriceMicros(1));
    }

    @Test
    void quantityCountToMicrosUsesIntegerArithmetic() {
        assertNull(FixedPoint.quantityCountToMicros(null));
        assertEquals(10_000_000L, FixedPoint.quantityCountToMicros(10));
    }
}
