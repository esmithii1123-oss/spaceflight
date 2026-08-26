package io.spaceflight.liquidity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VolumePressureEngineTest {

    private static final VolumePressureEngine.Params P =
            new VolumePressureEngine.Params(20.0, VolumePressureEngine.Mode.BOTH, 0.75, 30, 2048);

    @Test
    void sidesAreSeparatedByAggressorFlag() {
        VolumePressureEngine e = new VolumePressureEngine(P);
        long t = 0;
        for (int i = 0; i < 200; i++) {
            e.onTrade(t, true, 10); // market buys
            t += 250;
            e.sample(t);
        }
        VolumePressureEngine.Snapshot s = e.sample(t);
        assertTrue(s.primed());
        assertTrue(s.buyPercent() > 80, "steady buying should read as elevated buy pressure");
        assertEquals(0.0, s.sellPercent(), 1e-9, "no sells anywhere in the stream");
        assertTrue(s.net() > 0.5, "net must be strongly positive with only buys");
    }

    @Test
    void netFlipsSignWhenSellersTakeOver() {
        VolumePressureEngine e = new VolumePressureEngine(P);
        long t = 0;
        for (int i = 0; i < 120; i++) { // phase 1: buyers
            e.onTrade(t, true, 10); t += 250; e.sample(t);
        }
        for (int i = 0; i < 240; i++) { // phase 2: sustained selling (~6 half-lives)
            e.onTrade(t, false, 10); t += 250; e.sample(t);
        }
        assertTrue(e.sample(t).net() < -0.3, "after 6 half-lives of selling, net must be negative");
    }

    @Test
    void recentVolumeOutweighsOlderVolume() {
        // Same TOTAL volume on each side; buys all arrived first, sells all arrived last.
        // Recency weighting must leave the present reading seller-dominant even though
        // lifetime totals are identical.
        VolumePressureEngine e = new VolumePressureEngine(P);
        long t = 0;
        for (int i = 0; i < 50; i++) {           // phase 1: 500 total buying volume
            e.onTrade(t, true, 10); t += 250;
            if (i >= 30) { e.sample(t); }        // give scales warmup time mid-phase
        }
        for (int i = 0; i < 120; i++) {          // phase 2: 600 total selling over 30s (~1.5 hl)
            e.onTrade(t, false, 5); t += 250; e.sample(t);
        }
        double net = e.sample(t).net();
        assertTrue(net < 0, "older buy volume must decay below fresher sell volume; net=" + net);
    }

    @Test
    void normalizationRecoversAfterASpike_insteadOfStayingPinned() {
        // THE fix vs Market Pulse: rolling max stays pinned at the spike; decayed-quantile
        // reference comes back down so ordinary flow reads normal again.
        VolumePressureEngine e = new VolumePressureEngine(P);
        long t = 0;
        for (int i = 0; i < 300; i++) {                  // steady regime: 10/quarter-second
            e.onTrade(t, true, 10); t += 250; e.sample(t);
        }
        double calm = e.sample(t).buyPercent();
        assertTrue(calm < 140, "calm flow must read near/under its own scale: " + calm);

        for (int i = 0; i < 8; i++) {                    // burst: 20x prints
            e.onTrade(t, true, 200); t += 250; e.sample(t);
        }
        double during = e.sample(t).buyPercent();
        assertTrue(during > calm * 1.4, "burst must spike well above calm (" + during + ")");

        for (int i = 0; i < 480; i++) {                  // ~4 minutes of steady flow again
            e.onTrade(t, true, 10); t += 250; e.sample(t);
        }
        double recovered = e.sample(t).buyPercent();
        assertTrue(recovered < during * 0.75,
                "line must recover after a spike instead of pinning at max: " + recovered);
    }

    @Test
    void warmupReturnsNaNThenPrimes() {
        VolumePressureEngine e = new VolumePressureEngine(P);
        long t = 0;
        assertTrue(!e.sample(t).primed(), "no trades yet → not primed");
        for (int i = 0; i < 25; i++) {
            e.onTrade(t, true, 5); t += 500; e.sample(t);
        }
        assertFalse(e.sample(t).primed(), "below warmup samples (26 < 30)");
        for (int i = 0; i < 10; i++) {
            e.onTrade(t, false, 5); t += 500; e.sample(t);
        }
        assertTrue(e.sample(t).primed(), "past warmup both scales have data");
    }

    @Test
    void modeControlsEmittedFields() {
        VolumePressureEngine splitOnly = new VolumePressureEngine(
                new VolumePressureEngine.Params(20.0, VolumePressureEngine.Mode.SPLIT, 0.75, 10, 256));
        VolumePressureEngine netOnly = new VolumePressureEngine(
                new VolumePressureEngine.Params(20.0, VolumePressureEngine.Mode.NET, 0.75, 10, 256));

        long t = 0;
        for (int i = 0; i < 40; i++) {
            splitOnly.onTrade(t, true, 10); t += 250; splitOnly.sample(t);
            netOnly.onTrade(t, true, 10); netOnly.sample(t);
        }
        var ss = splitOnly.sample(t);
        assertTrue(Double.isFinite(ss.buyPercent()));
        assertTrue(Double.isNaN(ss.net()), "SPLIT mode emits no NET value");

        var ns = netOnly.sample(t);
        assertTrue(Double.isFinite(ns.net()));
        assertTrue(Double.isNaN(ns.buyPercent()), "NET mode emits no per-side %");
    }

    @Test
    void zeroSizeAndInvalidTimeAreSafe() {
        VolumePressureEngine e = new VolumePressureEngine(P);
        assertDoesNotThrow(() -> {
            e.onTrade(0, true, 0);
            e.onTrade(-5_000, false, 50); // backward seek before any positive time: resets
            e.sample(-1_000);
        });
    }
}
