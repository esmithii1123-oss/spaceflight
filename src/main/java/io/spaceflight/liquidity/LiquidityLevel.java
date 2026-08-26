package io.spaceflight.liquidity;

import java.util.EnumMap;
import java.util.Map;

/**
 * Lifecycle of a single tracked liquidity level (one side at one price).
 *
 * <p>States:</p>
 * <pre>
 * BUILDING -> STRONG   : engine confirms persistence + baseline-relative depth + quote stability
 * STRONG   -> BROKEN   : traded through (price crossed the level)
 * BROKEN   -> FADING   : immediately after break; strength decays exponentially instead
 *                        of dropping to zero, so buyer pull-outs stay observable
 * FADING   -> (gone)   : only removed once remaining strength falls below floor
 * </pre>
 *
 * <p>Promotion is decided by {@link RealLiquidityEngine} (which owns the baseline and peer
 * context); this class only tracks per-level statistics. Free of Bookmap API types.</p>
 */
public final class LiquidityLevel {

    public enum State { BUILDING, STRONG, BROKEN, FADING }

    private static final Map<State, String> LABELS = new EnumMap<>(Map.of(
            State.BUILDING, "forming",
            State.STRONG, "real",
            State.BROKEN, "broken",
            State.FADING, "fading"));

    private final int price;
    private final boolean bidSide;
    private final DecayWindow size;
    private final Config cfg;

    private State state = State.BUILDING;
    private long firstSeenMillis = Long.MIN_VALUE;
    private long brokenAtMillis = Long.MIN_VALUE;
    private int lastSize;

    public LiquidityLevel(int price, boolean bidSide, Config cfg) {
        this.price = price;
        this.bidSide = bidSide;
        this.cfg = cfg;
        this.size = new DecayWindow(cfg.decayHalfLifeUpdates());
    }

    /** Immutable tuning knobs shared by all levels (promotion thresholds live in the engine). */
    public record Config(double decayHalfLifeUpdates, long minPersistenceMillis,
                         double fadeHalfLifeMillis, double fadeFloor) {
        public static Config ofDefaults() {
            return new Config(20, 3_000, 12_000, 0.05);
        }
    }

    /** Feeds one observation for this exact price level. Repeated calls are safe. */
    public void observe(int size, long nowMillis) {
        lastSize = size;
        if (firstSeenMillis == Long.MIN_VALUE) {
            firstSeenMillis = nowMillis;
        }
        this.size.add(size);
    }

    /** Wall-clock time this level has existed (across vanish gaps). */
    public long persistence(long nowMillis) {
        return (firstSeenMillis == Long.MIN_VALUE) ? 0 : Math.max(0, nowMillis - firstSeenMillis);
    }

    public boolean isSamplePrimed(long minimumSamples) {
        return size.count() >= minimumSamples;
    }

    /** Relative size instability (std/mean) of recent quotes: high values indicate algo flicker. */
    public double jitter() {
        return size.relativeJitter();
    }

    /** Most recent resting size observed at this level. */
    public int lastSize() {
        return lastSize;
    }

    /** Marks the level as confirmed-real. Idempotent. */
    public void markStrong() {
        if (state == State.BUILDING) {
            state = State.STRONG;
        }
    }

    /** Marks the level as traded-through. Repeated calls are harmless. */
    public void breakLevel(long nowMillis) {
        if (state == State.BUILDING || state == State.STRONG || state == State.BROKEN) {
            state = State.FADING;
            brokenAtMillis = nowMillis;
        }
    }

    /**
     * Remaining strength in [0,1] driven purely by lifecycle: growing persistence while
     * building, full confidence once strong, exponential fade after break — never a
     * jump-to-zero, so the display cannot flicker. Baseline-relative depth scaling is
     * applied by the engine on top of this.
     */
    public double strength(long nowMillis) {
        return switch (state) {
            case BUILDING -> {
                long persistence = persistence(nowMillis);
                yield Math.min(0.49, 0.5 * persistence / (double) Math.max(1, cfg.minPersistenceMillis()));
            }
            case STRONG -> stabilityScore();
            case BROKEN, FADING -> {
                if (brokenAtMillis == Long.MIN_VALUE) yield 0.0;
                double elapsed = Math.max(0, nowMillis - brokenAtMillis);
                double remaining = Math.pow(0.5, elapsed / cfg.fadeHalfLifeMillis());
                yield Math.max(0, remaining * stabilityScore());
            }
        };
    }

    /** Confidence in [0.05,1] derived from quote stability (low jitter → high score). */
    private double stabilityScore() {
        return Math.max(0.05, 1.0 / (1.0 + size.relativeJitter()));
    }

    /** True when the level may be dropped entirely (faded below the strength floor). */
    public boolean isExpired(long nowMillis) {
        return state == State.FADING && strength(nowMillis) <= cfg.fadeFloor();
    }

    public State state() {
        return state;
    }

    public int price() {
        return price;
    }

    public boolean isBidSide() {
        return bidSide;
    }

    public String label() {
        return LABELS.get(state);
    }
}
