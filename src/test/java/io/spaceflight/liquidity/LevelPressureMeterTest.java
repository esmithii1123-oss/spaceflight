package io.spaceflight.liquidity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LevelPressureMeterTest {

    private static final LevelPressureMeter.Params P = LevelPressureMeter.Params.ofDefaults();
    // defaults: 20s churn half-life, proximity 3 ticks, flag at 50% of level size, clear at 25%

    @Test
    void buysAttackAsks_sellsAttackBids() {
        LevelPressureMeter m = new LevelPressureMeter(P);
        m.observeStrongLevels(0, List.of(
                new LevelPressureMeter.StrongLevel(true, 100, 1000),
                new LevelPressureMeter.StrongLevel(false, 200, 1000)));

        long t = 0;
        for (int i = 0; i < 20; i++) { // heavy buying right at the ask level
            m.onFill(t, 200, true, 100); t += 500; m.poll(t);
        }
        List<LevelPressureMeter.AbsorptionState> states = m.poll(t);
        boolean askFlagged = states.stream().anyMatch(s -> !s.bidSide() && s.absorbing());
        boolean bidFlagged = states.stream().anyMatch(s -> s.bidSide() && s.absorbing());
        assertTrue(askFlagged, "buying into an ask level must flag it");
        assertFalse(bidFlagged, "bid level untouched by buying");
    }

    @Test
    void churnDecaysAndFlagsClear_withHysteresis() {
        LevelPressureMeter loose = new LevelPressureMeter(P);
        loose.observeStrongLevels(0, List.of(
                new LevelPressureMeter.StrongLevel(true, 100, 1000)));
        long t = 0;
        for (int i = 0; i < 10; i++) { loose.onFill(t, 100, false, 60); t += 500; } // 60%+ churn
        List<LevelPressureMeter.AbsorptionState> states = loose.poll(t);
        assertTrue(states.get(0).absorbing(), "past threshold → flagged");

        // Quiet for ~6 half-lives: churn decays below clear-frac (25%), flag clears.
        t += 120_000;
        var after = loose.poll(t);
        assertFalse(after.get(0).absorbing(), "decay must eventually clear the flag");
        assertTrue(after.get(0).churnFrac() < 0.05);
    }

    @Test
    void proximityWeightsDistantFillsLower() {
        LevelPressureMeter close = new LevelPressureMeter(new LevelPressureMeter.Params(20.0, 3, 0.5, 0.05));
        close.observeStrongLevels(0, List.of(new LevelPressureMeter.StrongLevel(true, 100, 400)));

        LevelPressureMeter farOnly = new LevelPressureMeter(new LevelPressureMeter.Params(20.0, 1, 0.5, 0.05));
        farOnly.observeStrongLevels(0, List.of(new LevelPressureMeter.StrongLevel(true, 100, 400)));

        close.onFill(0, 103, false, 200);   // 3 ticks away: weight (1-3/4)=0.25 with prox=3 → still counted
        var sClose = close.poll(1);

        LevelPressureMeter tight = new LevelPressureMeter(new LevelPressureMeter.Params(20.0, 0, 0.5, 0.05));
        tight.observeStrongLevels(0, List.of(new LevelPressureMeter.StrongLevel(true, 100, 400)));
        tight.onFill(0, 103, false, 200);   // outside prox=0 → ignored entirely
        assertTrue(tight.poll(1).get(0).churnFrac() == 0.0);

        assertNotEquals(sClose.get(0).churnFrac(), 0.5,
                "distant fill must contribute less than a full print AT the level");
    }

    @Test
    void onlyConfirmedLevelsAreTracked_andRungRemovalDropsState() {
        LevelPressureMeter m = new LevelPressureMeter(P);
        m.observeStrongLevels(0, List.of(new LevelPressureMeter.StrongLevel(true, 100, 500)));
        m.observeStrongLevels(1000, List.of()); // rung vanished from the ladder
        assertTrue(m.poll(1000).isEmpty(), "no level left → no absorption state");

        m.onFill(1000, 99, false, 900); // fills while nothing tracked → no crash, no ghost entries
        assertTrue(m.poll(1002).isEmpty());
    }

    @Test
    void newRungStartsClean_noChurnLeakFromOlderLevelsAtSamePrice() {
        LevelPressureMeter m = new LevelPressureMeter(P);
        m.observeStrongLevels(0, List.of(new LevelPressureMeter.StrongLevel(true, 100, 200)));
        m.onFill(0, 100, false, 150);            // old rung takes churn
        m.reset();
        m.observeStrongLevels(1000, List.of(new LevelPressureMeter.StrongLevel(true, 100, 200)));
        assertFalse(m.isAbsorbing(true, 100), "fresh ladder sync must not inherit stale churn");
    }

    @Test
    void backwardSeekResetsInsteadOfInflating() {
        LevelPressureMeter m = new LevelPressureMeter(P);
        m.observeStrongLevels(100_000, List.of(new LevelPressureMeter.StrongLevel(true, 100, 500)));
        m.onFill(100_000, 100, false, 600);      // beyond threshold at that time
        assertTrue(m.poll(100_000).get(0).absorbing());
        m.observeStrongLevels(50_000, List.of(new LevelPressureMeter.StrongLevel(true, 100, 500))); // rewind
        m.onFill(50_000, 100, false, 100);
        assertFalse(m.poll(50_000).get(0).absorbing(), "post-seek small churn must not be inflated");
    }

    @Test
    void zeroQtyFillsIgnored_invalidSizesRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new LevelPressureMeter.StrongLevel(true, 1, 0));
        LevelPressureMeter m = new LevelPressureMeter(P);
        m.observeStrongLevels(0, List.of(new LevelPressureMeter.StrongLevel(true, 100, 100)));
        m.onFill(0, 100, true, 0);
        assertEquals(0.0, m.poll(1).get(0).churnFrac());
    }
}
