package io.spaceflight.liquidity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ladder-aware pressure conditioning — the module Market Pulse does not have.
 *
 * <p>The volume-pressure engine says "buyers are pressing". This class answers the question a
 * discretionary trader actually has: <b>pressing what?</b> Every trade print is attributed to
 * nearby confirmed (STRONG) resting levels from the real-liquidity ladder, proximity-weighted
 * by tick distance:</p>
 *
 * <pre>
 *   buys  press ASK levels  (market lifts offers sitting at/around your level)
 *   sells press BID levels  (market hits bids sitting at/around your level)
 * </pre>
 *
 * <p>For each level it accumulates decayed "churn" = traded volume routed through its vicinity,
 * expressed as a fraction of that level's own resting size. When churn exceeds
 * {@code absorptionThresholdFrac} of the level's size while the level STILL STANDS (not broken,
 * not fading), passive liquidity there is absorbing aggression. That is the actionable signal:
 * large aggression, no price movement, giant orders holding.</p>
 *
 * <p>Hysteresis prevents flag flicker: a flag raises at {@code thresholdFrac} and only clears
 * when churn decays below {@code clearFrac}. Pure logic; replay-safe (time from callers).</p>
 */
final class LevelPressureMeter {

    /** One confirmed resting level as seen from the ladder this sampling instant. */
    public record StrongLevel(boolean bidSide, int priceTick, int size) {
        public StrongLevel {
            if (size <= 0) throw new IllegalArgumentException("size must be > 0");
        }
    }

    /** Raised or lowered absorption state for one level. */
    public record AbsorptionState(boolean bidSide, int priceTick, double churnFrac,
                                  boolean absorbing) {}

    public record Params(double churnHalfLifeSec, int proximityTicks,
                         double absorptionThresholdFrac, double clearFrac) {
        public static Params ofDefaults() {
            return new Params(20.0, 3, 0.5, 0.05);
        }
        public Params {
            if (!(churnHalfLifeSec > 0)) throw new IllegalArgumentException("churnHalfLifeSec must be > 0");
            proximityTicks = Math.max(0, proximityTicks);
            absorptionThresholdFrac = Math.max(0.05, absorptionThresholdFrac);
            clearFrac = Math.min(clearFrac <= 0 ? absorptionThresholdFrac / 2
                    : Math.min(clearFrac, absorptionThresholdFrac / 2), absorptionThresholdFrac);
        }
    }

    private static final class Entry {
        double churn;
        boolean absorbing;
    }

    private final long churnHalfLifeMillis;
    private final int proximityTicks;
    private final Params params;

    /** key = (side<<32 | priceTick) -> accumulator. Mirrors the ladder's current STRONG set. */
    private final Map<Long, Entry> entries = new HashMap<>();
    private final Map<Long, StrongLevel> levels = new HashMap<>();
    private long lastMillis = Long.MIN_VALUE;

    public LevelPressureMeter(Params params) {
        this.params = params;
        this.proximityTicks = params.proximityTicks();
        this.churnHalfLifeMillis = Math.round(params.churnHalfLifeSec() * 1000.0);
    }

    /** Re-syncs the tracked strong-level set each sampling tick (ladder may add/remove rungs). */
    public void observeStrongLevels(long nowMillis, List<StrongLevel> strongLevels) {
        if (lastMillis != Long.MIN_VALUE && nowMillis < lastMillis) {
            reset(); // backward seek: drop everything rather than carry stale churn forward
        }
        lastMillis = nowMillis;

        levels.clear();
        Map<Long, Entry> next = new HashMap<>();
        for (StrongLevel l : strongLevels) {
            Long key = key(l.bidSide(), l.priceTick());
            levels.put(key, l);
            Entry e = entries.get(key);
            if (e == null) {
                e = new Entry(); // new rung: start clean so old churn never leaks onto fresh levels
                entries.put(key, e);
            }
            next.put(key, e);
        }
        entries.keySet().retainAll(next.keySet()); // gone from ladder -> gone here
    }

    /**
     * Attributes one fill to nearby strong levels on the side being consumed.
     * {@code bidAggressor=true} hits ask levels; {@code false} hits bid levels.
     */
    public void onFill(long nowMillis, int fillPriceTick, boolean bidAggressor, int qty) {
        int q = Math.max(0, qty);
        if (q == 0 || lastMillis == Long.MIN_VALUE || nowMillis < lastMillis) {
            return;
        }
        advanceDecay(nowMillis);
        for (Map.Entry<Long, StrongLevel> e : levels.entrySet()) {
            StrongLevel lvl = e.getValue();
            if (lvl.bidSide() == bidAggressor) {
                continue; // buys attack asks, sells attack bids — never their own side's levels
            }
            int dist = Math.abs(lvl.priceTick() - fillPriceTick);
            if (dist > proximityTicks) {
                continue;
            }
            // Linear falloff: a print AT the level counts fully, adjacent ticks count less.
            double weight = 1.0 - dist / (double) (proximityTicks + 1);
            entries.get(e.getKey()).churn += weight * q;
        }
    }

    /** Polls current absorption states (with hysteresis). Call once per sampling tick. */
    public List<AbsorptionState> poll(long nowMillis) {
        advanceDecay(nowMillis);
        List<AbsorptionState> out = new ArrayList<>();
        for (Map.Entry<Long, Entry> en : entries.entrySet()) {
            Entry e = en.getValue();
            Long k = en.getKey();
            StrongLevel lvl = levels.get(k);
            double frac = lvl == null ? 0 : e.churn / lvl.size();
            boolean was = e.absorbing;
            boolean now;
            if (!was) {
                now = frac >= params.absorptionThresholdFrac();
            } else {
                now = frac >= params.clearFrac(); // stay flagged until deeply decayed
            }
            e.absorbing = now;
            out.add(new AbsorptionState(lvl.bidSide(),
                    lvl.priceTick(),
                    frac,
                    now));
        }
        return out;
    }

    /** True when the given side+price is currently flagged absorbing. */
    public boolean isAbsorbing(boolean bidSide, int priceTick) {
        Entry e = entries.get(key(bidSide, priceTick));
        return e != null && e.absorbing;
    }

    public void reset() {
        entries.clear();
        levels.clear();
        lastMillis = Long.MIN_VALUE;
    }

    private void advanceDecay(long nowMillis) {
        if (lastMillis == Long.MIN_VALUE || nowMillis <= lastMillis) {
            return;
        }
        double factor = Math.pow(0.5, (nowMillis - lastMillis) / (double) churnHalfLifeMillis);
        for (Entry e : entries.values()) {
            e.churn *= factor;
        }
        lastMillis = nowMillis;
    }

    private static Long key(boolean bidSide, int priceTick) {
        return ((long) (bidSide ? 1 : 0) << 32) | (priceTick & 0xFFFFFFFFL);
    }
}
