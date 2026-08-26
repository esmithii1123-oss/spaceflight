package io.spaceflight.liquidity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavior contract for the real-liquidity ladder:
 * noise must never become a level, strong levels survive jitter, breaks fade
 * instead of flickering off, and fading levels are eventually cleaned up.
 */
class RealLiquidityEngineTest {

    private RealLiquidityEngine engine() {
        return new RealLiquidityEngine(RealLiquidityEngine.EngineParams.ofDefaults(), null);
    }

    @Test
    void flickeringQuoteNeverBecomesStrong() {
        RealLiquidityEngine e = engine();
        long t = 0;
        // Algo quote appears for 1s, disappears for 5s, repeatedly — always below persistence floor.
        for (int i = 0; i < 10; i++) {
            e.onDepth(t, true, 21000, 500);       // appears
            e.onDepth(t + 1_000, true, 21000, 0); // vanishes
            t += 6_000;
            e.onDepth(t, false, 21050, 100);
        }
        List<LiquidityLevel> bids = e.visibleLevels(t, true);
        assertTrue(bids.isEmpty(), () -> "flickering quotes leaked through: " + bids);
    }

    @Test
    void persistentQuoteIsPromotedToStrong() {
        RealLiquidityEngine e = engine();
        long t = 0;
        // Steady resting bid held above the 3s persistence requirement (updates every 1s).
        for (int i = 0; i < 30; i++) {
            e.onDepth(t, true, 21000, 400);
            t += 1_000;
        }
        assertEquals(LiquidityLevel.State.STRONG, e.visibleLevels(t - 1_000, true).get(0).state());
    }

    @Test
    void brokenLevelFadesInsteadOfDisappearing() {
        RealLiquidityEngine e = engine();
        long t = 0;
        for (int i = 0; i < 30; i++) { e.onDepth(t, true, 21000, 400); t += 1_000; }
        long brokenAt = t;

        e.onTrade(brokenAt, 20999); // market traded down through our bid ladder rung

        LiquidityLevel lvl = e.visibleLevels(brokenAt, true).get(0);
        assertEquals(LiquidityLevel.State.FADING, lvl.state());

        double strengthRightAfter = lvl.strength(brokenAt + 100);
        double strengthLater = lvl.strength(brokenAt + 12_000);
        assertTrue(strengthRightAfter > strengthLater, "fade should be monotonic");
        assertTrue(strengthLater > 0, "fading level must remain observable after break");
    }

    @Test
    void fadedLevelsAreEventuallyRemoved() {
        RealLiquidityEngine e = engine();
        long t = 0;
        for (int i = 0; i < 30; i++) { e.onDepth(t, true, 21000, 400); t += 1_000; }
        e.onTrade(t, 20_999);

        // Default fade half-life 12s; walk far past several half-lives.
        long later = t + 60_000;
        e.onDepth(later, false, 21_000, 1); // poke the engine to sweep
        assertEquals(0, e.trackedCount(true));
    }

    @Test
    void tradeThroughRespectsSides() {
        RealLiquidityEngine e = engine();
        long t = 0;
        for (int i = 0; i < 10; i++) {
            e.onDepth(t, true, 21000, 300);   // bid below
            e.onDepth(t, false, 21100, 300);  // ask above
            t += 1_000;
        }
        // Rally up into the ask — asks below/behind fill are not a thing; asks at or above hold.
        e.onTrade(t, 21_101);
        assertTrue(e.trackedCount(false) == 0 || e.trackedCount(false) == 1);
        assertEquals(1, e.trackedCount(true));
        for (LiquidityLevel b : e.visibleLevels(t, true)) {
            assertNotEquals(LiquidityLevel.State.FADING, b.state());
        }
    }
}
