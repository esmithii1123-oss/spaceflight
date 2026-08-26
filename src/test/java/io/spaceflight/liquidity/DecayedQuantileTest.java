package io.spaceflight.liquidity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DecayedQuantileTest {

    @Test
    void nanUntilMinSamples() {
        DecayedQuantile q = new DecayedQuantile(0.75, 60_000, 2048, 5);
        q.update(0, 10);
        q.update(1_000, 20);
        assertTrue(Double.isNaN(q.value()));
        for (int i = 2; i < 6; i++) {
            q.update(i * 1_000L, i);
        }
        assertFalse(Double.isNaN(q.value()));
    }

    @Test
    void oldSpikesFadeOutOfTheReference() {
        // One giant outlier then long quiet stretch: the reference must come back DOWN,
        // unlike a rolling max which would stay pinned at the outlier forever.
        DecayedQuantile q = new DecayedQuantile(0.75, 30_000 /* 30s half-life */, 2048, 10);
        for (int i = 0; i < 40; i++) {
            q.update(i * 500L, 10.0); // steady regime ~10
        }
        double calmRef = q.value();
        assertTrue(calmRef > 0 && calmRef < 30, "calm reference should sit near 10, got " + calmRef);

        q.update(20_000L, 1_000.0); // monster spike mid-session

        // A single outlier must NOT pin the scale up — that's exactly what's broken in a
        // rolling max. It may nudge at most slightly.
        assertTrue(q.value() < calmRef * 3,
                "one outlier must not dominate the reference; got " + q.value());

        // Sustained regime change DOES raise it (these are new normal), then quiet decays it back.
        long t = 30_000;
        for (int i = 0; i < 60; i++) { q.update(t, 400.0); t += 500; }
        double highRef = q.value();
        assertTrue(highRef > calmRef * 5, "a persistent regime shift raises the reference");

        // Back to calm for ~8 half-lives: reference follows the instrument down.
        for (long u = t; u <= t + 240_000; u += 500) { q.update(u, 10.0); }
        double lateRef = q.value();
        assertTrue(lateRef < highRef / 4, "old regime must decay away; got " + lateRef);
    }

    @Test
    void weightsRespectHalfLife() {
        // After one half-life, an old observation has half the weight of a fresh one:
        // the weighted 0.5-quantile then lands on the FRESH value's rung.
        DecayedQuantile q = new DecayedQuantile(0.5, 10_000, 1024, 1);
        q.update(0, 100);
        q.update(10_000, 25);   // old 100 now weighs 0.5 vs fresh 1.0 → majority on 25
        assertEquals(25.0, q.value(), 1e-9);

        DecayedQuantile q2 = new DecayedQuantile(0.75, 10_000, 1024, 1);
        q2.update(0, 100);      // acc target .75: old contributes .5 of 1.5 → still falls on 100
        q2.update(10_000, 25);
        assertEquals(100.0, q2.value(), 1e-9);
    }

    @Test
    void rejectsInvalidConfigAndNaNInput() {
        assertThrows(IllegalArgumentException.class, () -> new DecayedQuantile(0, 1000, 64, 5));
        assertThrows(IllegalArgumentException.class, () -> new DecayedQuantile(1.5, 1000, 64, 5));
        assertThrows(IllegalArgumentException.class, () -> new DecayedQuantile(0.5, 0, 64, 5));

        DecayedQuantile q = new DecayedQuantile(0.75, 60_000, 2048, 1);
        q.update(0, Double.NaN);
        assertEquals(0, q.count());
    }

    @Test
    void backwardSeekDropsStaleState() {
        DecayedQuantile q = new DecayedQuantile(0.75, 60_000, 2048, 1);
        q.update(100_000, 42);
        q.update(50_000, 7); // rewind
        assertEquals(1, q.count());
        assertEquals(7.0, q.value(), 1e-9);
    }
}
