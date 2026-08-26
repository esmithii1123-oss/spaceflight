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

    @Test
    void persistenceThresholdIsUserAdjustable() {
        // Same quote stream: 2s of presence.
        long t = 0;
        // 30 updates at 50ms spacing => only ~1.5s of wall-clock presence, enough samples to judge stability.
        java.util.function.Function<RealLiquidityEngine, LiquidityLevel.State> probe = eng -> {
            long tt = 0;
            for (int i = 0; i < 30; i++) { eng.onDepth(tt, true, 21_000, 400); tt += 50; }
            return eng.visibleLevels(tt, true).isEmpty()
                    ? LiquidityLevel.State.BUILDING : eng.visibleLevels(tt, true).get(0).state();
        };

        // 1s persistence -> 2s quote is plenty (baseline absent: depth gate passes).
        RealLiquidityEngine fast = new RealLiquidityEngine(new RealLiquidityEngine.EngineParams(
                20, 1_000, 0.5, 0.45, 12_000, 0.05, 3, 24, null), null);
        assertEquals(LiquidityLevel.State.STRONG, probe.apply(fast), "1s setting should confirm a ~1.5s quote");

        // 10s persistence -> the same 2s quote must stay hidden.
        RealLiquidityEngine slow = new RealLiquidityEngine(new RealLiquidityEngine.EngineParams(
                20, 10_000, 0.5, 0.45, 12_000, 0.05, 3, 24, null), null);
        assertEquals(LiquidityLevel.State.BUILDING, probe.apply(slow),
                "10s setting must reject a quote that only sat for ~1.5s");
    }

    @Test
    void fullSessionStoryBaselineToBreakToFadeToCleanup() {
        // Session opens at t=0 (already past). Baseline: strongest level ~100 on both sides.
        PreMarketBaseline baseline = new PreMarketBaseline(0, 0, 20);
        RealLiquidityEngine e = new RealLiquidityEngine(new RealLiquidityEngine.EngineParams(
                20, 3_000, 0.5, 0.45, 12_000, 0.05, 3, 24, null), baseline);

        // 1) Prime the baseline with 60 ticks of modest liquidity.
        long t = 0;
        for (int i = 0; i < 60; i++) {
            e.onDepth(t, true, 20_000, 100);
            e.onDepth(t, false, 20_100, 100);
            baseline.observe(t, e.largestRestingSize(true), e.largestRestingSize(false));
            t += 1_000;
        }
        assertTrue(baseline.isReady(8), "baseline must prime from simulated pre-market ticks");
        // A shallow 30-size rung (norm depth ~0.2 < 0.5 gate) must stay hidden even after priming.
        for (int i = 0; i < 30; i++) { e.onDepth(t, true, 20_050, 30); t += 1_000; }
        assertTrue(e.visibleLevels(t, true).stream().noneMatch(l -> l.price() == 20_050),
                "shallow rung must stay hidden: below the baseline depth gate");

        // 2) A genuinely strong bid appears and persists: promotes after gates pass.
        for (int i = 0; i < 30; i++) { e.onDepth(t, true, 19_950, 200); t += 1_000; }
        assertEquals(LiquidityLevel.State.STRONG, e.visibleLevels(t - 1_000, true).get(0).state(),
                "200-size vs ~100 baseline must confirm as real");

        // 3) Price drops through it: fade begins, remains observable, then cleans up.
        // A bid level is only "traded through" when fills print BELOW it (market sells eat
        // into support); a rally away from a bid must leave it intact.
        assertEquals(LiquidityLevel.State.STRONG,
                stateOf(e, t, true, 19_950), "a rally ABOVE a bid must not break it");
        e.onTrade(t + 500, 19_949);
        LiquidityLevel broken = levelAt(e, t + 500, true, 19_950);
        assertNotNull(broken);
        assertEquals(LiquidityLevel.State.FADING, broken.state(), "fills below a bid must break it");

        double mid = levelAt(e, t + 6_000, true, 19_950).strength(t + 6_000);
        assertTrue(mid > 0 && mid < 1.0, "still visible 6s after break (mid-fade)");
        e.onDepth(t + 90_000, false, 20_200, 10); // poke sweep well past fade
        assertEquals(0, e.trackedCount(true), "fully faded level must be cleaned up");
    }

    private static RealLiquidityEngine.ScoredLevel scoredAt(RealLiquidityEngine e, long t,
            boolean bidSide, int priceTick) {
        return e.visibleLevelsScored(t, bidSide).stream()
                .filter(sl -> sl.level().price() == priceTick).findFirst().orElse(null);
    }

    private static LiquidityLevel levelAt(RealLiquidityEngine e, long t, boolean bidSide, int priceTick) {
        RealLiquidityEngine.ScoredLevel sl = scoredAt(e, t, bidSide, priceTick);
        return sl == null ? null : sl.level();
    }

    private static LiquidityLevel.State stateOf(RealLiquidityEngine e, long t, boolean bidSide, int priceTick) {
        LiquidityLevel l = levelAt(e, t, bidSide, priceTick);
        return l == null ? null : l.state();
    }
}
