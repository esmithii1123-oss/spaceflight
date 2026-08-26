package io.spaceflight.liquidity;

/**
 * Pure price<->tick conversions, unit-testable without Bookmap.
 *
 * <p>Bookmap semantics (per the official DemoStrategies trading helpers):
 * {@code InstrumentInfo.pips} is the size of one price tick in raw price units —
 * e.g. 0.25 for MNQ/ES, 1.0 for whole-point tickers. Order-book depth arrives as
 * integer tick indices; trade prints and PRIMARY-graph rendering use raw prices.</p>
 */
public final class TickMath {

    private TickMath() {}

    /** Raw price units -> integer tick index. Rounds to the nearest tick. */
    public static int priceToTick(double priceUnits, double pips) {
        requirePips(pips);
        return (int) Math.round(priceUnits / pips);
    }

    /** Integer tick index -> raw price units (center of that tick rung). */
    public static double tickToPrice(int tickIndex, double pips) {
        requirePips(pips);
        return tickIndex * pips;
    }

    private static void requirePips(double pips) {
        if (!(pips > 0) || Double.isInfinite(pips)) {
            throw new IllegalArgumentException("pips must be a finite positive tick size, got " + pips);
        }
    }
}
