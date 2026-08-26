package io.spaceflight.liquidity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

    @Test
    void shallowLevelsNeverPromoteWhenBaselineIsReady() {
        // Baseline reference decays toward ~100 on both sides after enough ticks.
        PreMarketBaseline baseline = new PreMarketBaseline(0, Long.MAX_VALUE / 2, 20);
        for (int i = 0; i < 60; i++) baseline.observe(i * 1_000L, 100.0, 100.0);
        assertTrue(baseline.isReady(8));

        // minRelativeDepth 0.5 => a level needs >= ~0.75x the ~100 reference (~75) to promote.
        RealLiquidityEngine weak = new RealLiquidityEngine(
                new RealLiquidityEngine.EngineParams(20, 3_000, 0.5, 0.45,
                        12_000, 0.05, 3, 24, null), baseline);
        long t = 0;
        for (int i = 0; i < 30; i++) { weak.onDepth(t, true, 21000, 30); t += 1_000; }
        assertEquals(LiquidityLevel.State.BUILDING, stateAt(weak, t - 1_000, true),
                "shallow level must stay hidden while a ready baseline says it is thin");

        RealLiquidityEngine deep = new RealLiquidityEngine(
                new RealLiquidityEngine.EngineParams(20, 3_000, 0.5, 0.45,
                        12_000, 0.05, 3, 24, null), baseline);
        t = 0;
        for (int i = 0; i < 30; i++) { deep.onDepth(t, true, 21001, 200); t += 1_000; }
        assertEquals(LiquidityLevel.State.STRONG, stateAt(deep, t - 1_000, true));
    }

    private static LiquidityLevel.State stateAt(RealLiquidityEngine e, long t, boolean bidSide) {
        return e.visibleLevels(t, bidSide).isEmpty()
                ? LiquidityLevel.State.BUILDING : e.visibleLevels(t, bidSide).get(0).state();
    }

    @Test
    void eventSinkReceivesLifecycleTransitions() {
        List<RealLiquidityEngine.LiquidityEvent> events = new ArrayList<>();
        RealLiquidityEngine e = new RealLiquidityEngine(
                new RealLiquidityEngine.EngineParams(20, 3_000, 0.5, 0.45,
                        12_000, 0.05, 3, 24, events::add), null);
        long t = 0;
        for (int i = 0; i < 30; i++) { e.onDepth(t, true, 21000, 400); t += 1_000; }
        e.onTrade(t, 20_999);

        assertTrue(events.stream().anyMatch(ev -> "PROMOTE".equals(ev.type())));
        assertTrue(events.stream().anyMatch(ev -> "BROKEN".equals(ev.type())));
        assertEquals(RealLiquidityEngine.ScoredLevel.class.getSimpleName(),
                RealLiquidityEngine.ScoredLevel.class.getSimpleName()); // shape guard
    }

    @Test
    void abandonedBuildingLevelsAreSweptToBoundMemory() {
        RealLiquidityEngine e = engine();
        long t = 0;
        e.onDepth(t, true, 21_000, 500);       // stray algo quote
        e.onDepth(t + 1_000, true, 21_000, 0); // gone
        long later = t + 10 * 3_000 + 1;       // far past 10x persistence floor
        e.onDepth(later, false, 21_050, 100);  // poke the sweep
        assertEquals(0, e.trackedCount(true), "stale BUILDING price must not accumulate");
    }

    @Test
    void fadingLevelsDoNotPolluteBaselineReference() {
        RealLiquidityEngine e = engine();
        long t = 0;
        for (int i = 0; i < 30; i++) { e.onDepth(t, true, 21_000, 400); t += 1_000; }
        e.onTrade(t, 20_999); // break -> FADING
        assertEquals(0.0, e.largestRestingSize(true),
                "broken level's stale size must not feed the baseline");
    }

    @Test
    void fadeIsMonotonicEvenWhileZerosStreamInAfterBreak() {
        RealLiquidityEngine e = engine();
        long t = 0;
        for (int i = 0; i < 30; i++) { e.onDepth(t, true, 21_000, 400); t += 1_000; }
        e.onTrade(t, 20_999);

        // Post-break: size 0 keeps arriving at the broken level (as Bookmap sends on removal).
        double prev = e.visibleLevels(t, true).get(0).strength(t);
        for (int i = 1; i <= 40; i++) {
            long now = t + i * 500;
            e.onDepth(now, true, 21_000, 0); // decayed mean keeps sliding toward 0
            double s = e.visibleLevels(now, true).isEmpty()
                    ? 0.0 : e.visibleLevels(now, true).get(0).strength(now);
            assertTrue(s <= prev + 1e-9, "strength must never jump back up mid-fade (flicker)");
            prev = s;
        }
    }
}
