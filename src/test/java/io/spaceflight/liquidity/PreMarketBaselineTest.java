package io.spaceflight.liquidity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pre-market baseline and decay-window contract. */
class PreMarketBaselineTest {

    @Test
    void baselineGrowsBeforeOpenAndStaysReady() {
        // 09:30 ET open; feed pre-market samples for two hours before.
        long open = PreMarketBaseline.sessionOpenFor(0L, 9, 30);
        PreMarketBaseline b = new PreMarketBaseline(0, open, 20);
        assertFalse(b.isOpen());

        long t = 0;
        boolean sawPreOpenClosed = false;
        while (t < open) {
            if (!b.isOpen() && b.samples() > 10) sawPreOpenClosed = true;
            b.observe(t, 100.0, 110.0);
            t += 5_000;
        }
        assertTrue(sawPreOpenClosed, "baseline should stay closed through pre-market");
        b.observe(t, 100.0, 110.0); // first tick at/after the open
        assertTrue(b.isOpen());
        assertTrue(b.isReady(3));
        assertTrue(Math.abs(b.normalize(true, 150.0)) > 0);
    }

    @Test
    void normalizeCapsAtFullStrength() {
        PreMarketBaseline b = new PreMarketBaseline(0, Long.MAX_VALUE / 2, 20);
        for (int i = 0; i < 50; i++) b.observe(i * 1_000, 100.0, 100.0);
        assertEquals(1.0, b.normalize(true, 300.0), 1e-9); // ≥1.5x baseline saturates
        assertTrue(b.normalize(true, 100.0) < 1.0);
    }

    @Test
    void decayWindowConvergesAndTracksJitter() {
        DecayWindow w = new DecayWindow(10);
        for (int i = 0; i < 200; i++) w.add(100 + (i % 2)); // tiny jitter around ~100
        assertTrue(w.relativeJitter() < 0.05, "steady quote should have low jitter");

        DecayWindow j = new DecayWindow(10);
        for (int i = 0; i < 200; i++) j.add((i % 2 == 0) ? 40 : 160); // flickering size
        assertTrue(j.relativeJitter() > 0.4, "algo jitter should be detected");
    }

    @Test
    void sessionOpenComputesNextOpenInExchangeTime() {
        // Choose a timestamp clearly after 09:30 ET on its date; next open must be next day.
        ZonedInstant ts = ZonedInstant.parseIso("2026-03-04T18:00:00Z"); // 13:00 ET
        long open = PreMarketBaseline.sessionOpenFor(ts.millis, 9, 30);
        assertTrue(open > ts.millis);
        assertTrue(open - ts.millis <= java.time.Duration.ofDays(1).plusHours(4).toMillis());
    }

    @Test
    void sessionOpenOfDatePinsTodaysBoundaryBothSidesOfTheOpen() {
        // 08:00 ET (13:00Z) on 2026-03-04 -> today's 09:30 open, not yet passed.
        long preOpen = RealLiquidityLadderEquivalent.instant("2026-03-04T13:00:00Z");
        long openAt930 = PreMarketBaseline.sessionOpenOfDate(preOpen, 9, 30);
        assertTrue(openAt930 > preOpen, "before the open, boundary must be in the future");

        // 11:00 ET (16:00Z) same date -> same boundary, already passed (session is open).
        long inSession = RealLiquidityLadderEquivalent.instant("2026-03-04T16:00:00Z");
        assertEquals(openAt930, PreMarketBaseline.sessionOpenOfDate(inSession, 9, 30),
                "boundary must NOT jump to tomorrow while today's session runs");
        assertTrue(inSession >= openAt930);
    }

    /** Small local helper so this test file stays free of heavy time APIs. */
    private static final class RealLiquidityLadderEquivalent {
        static long instant(String iso) {
            return java.time.Instant.parse(iso).toEpochMilli();
        }
    }

    /** Tiny helper so tests avoid pulling heavy time APIs everywhere. */
    private static final class ZonedInstant {
        final long millis;
        private ZonedInstant(long m) { millis = m; }
        static ZonedInstant parseIso(String s) {
            return new ZonedInstant(java.time.Instant.parse(s).toEpochMilli());
        }
    }
}
