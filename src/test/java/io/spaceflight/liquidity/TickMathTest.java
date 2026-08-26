package io.spaceflight.liquidity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ticks conversion contract, checked against real instrument tick specs.
 * MNQ/NQ/ES: tick = 0.25 raw price points; MYM/YM: 1.0; BTC-style: fractional > 1.
 */
class TickMathTest {

    @Test
    void quarterPointInstrumentsConvertExactly() {
        double pips = 0.25; // MNQ / ES
        assertEquals(86_605, TickMath.priceToTick(21_651.25, pips));
        assertEquals(84_000, TickMath.priceToTick(21_000.00, pips));
        assertEquals(21_651.25, TickMath.tickToPrice(86_605, pips), 1e-9);
    }

    @Test
    void wholePointInstrumentsAreNotDistorted() {
        double pips = 1.0; // YM / MYM
        assertEquals(43_000, TickMath.priceToTick(43_000.0, pips));
        assertEquals(43_000.0, TickMath.tickToPrice(43_000, pips), 1e-9);
    }

    @Test
    void subPointPricesRoundToNearestTick() {
        double pips = 0.25;
        assertEquals(84_001, TickMath.priceToTick(21_000.13, pips)); // 21000.13/0.25 = 84000.52
        assertEquals(84_000, TickMath.priceToTick(21_000.12, pips)); // 21000.12/0.25 = 84000.48
    }

    @Test
    void rejectsInvalidPips() {
        assertThrows(IllegalArgumentException.class, () -> TickMath.priceToTick(100, 0));
        assertThrows(IllegalArgumentException.class, () -> TickMath.priceToTick(100, -0.25));
        assertThrows(IllegalArgumentException.class, () -> TickMath.priceToTick(100, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> TickMath.tickToPrice(1, Double.POSITIVE_INFINITY));
    }
}
