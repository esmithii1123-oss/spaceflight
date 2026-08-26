package io.spaceflight.liquidity;

import java.util.EnumMap;
import java.util.Map;

/**
 * Lifecycle of a single tracked liquidity level (one side at one price).
 *
 * <p>States:</p>
 * <pre>
 * BUILDING -> STRONG   : level persisted long enough and passed depth/jitter strictness
 * STRONG   -> BROKEN   : traded through (price crossed the level)
 * BROKEN   -> FADING   : immediately after break; strength decays exponentially instead
 *                        of dropping to zero, so buyer pull-outs stay observable
 * FADING   -> (gone)   : only removed once remaining strength falls below floor
 * </pre>
 *
 * <p>The class is deliberately free of any Bookmap API types and unit-testable in isolation.</p>
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
    private double lastSize;

    public LiquidityLevel(int price, boolean bidSide, Config cfg) {
        this.price = price;
        this.bidSide = bidSide;
        this.cfg = cfg;
        this.size = new DecayWindow(cfg.decayHalfLifeUpdates());
    }

    /** Immutable tuning knobs shared by all levels. */
    public record Config(double decayHalfLifeUpdates, long minPersistenceMillis,
                         double minRelativeDepth, double maxRelativeJitter,
                         double fadeHalfLifeMillis, double fadeFloor, long minPromotionSamples) {
        public static Config ofDefaults() {
            return new Config(20, 3_000, 0.5, 0.45, 12_000, 0.05, 24);
        }
    }

    /**
     * Feeds one observation for this exact price level.
     *
     * @param size current resting size at this level (contracts); 0 means the quote vanished
     * @param nowMillis wall-clock timestamp of this depth update
     */
    public void observe(int size, long nowMillis) {
        lastSize = size;
        if (firstSeenMillis == Long.MIN_VALUE) {
            firstSeenMillis = nowMillis;
        }
        this.size.add(size);
        promoteIfEligible(nowMillis);
    }

    private void promoteIfEligible(long nowMillis) {
        if (state != State.BUILDING || !size.isPrimed(cfg.minPromotionSamples())) {
            return;
        }
        long persistence = nowMillis - firstSeenMillis;
        double depthScore = relativeDepth();
        boolean stableQuote = size.relativeJitter() <= cfg.maxRelativeJitter();
        if (persistence >= cfg.minPersistenceMillis()
                && depthScore >= cfg.minRelativeDepth()
                && stableQuote) {
            state = State.STRONG;
        }
    }

    /** Resting size relative to the strongest observed size (normalized robust depth proxy). */
    double relativeDepth() {
        // Depth is compared against nothing external here; engine normalizes by baseline.
        // Use presence stability: levels that hold size >= 50% of their own peak rank higher.
        return lastSize <= 0 ? 0.0 : Math.min(1.0, lastSize / Math.max(lastSize, peakProxy()));
    }

    private double peakProxy() {
        double m = size.mean();
        return m <= 0 ? 1.0 : m * 2.0;
    }

    /** Marks the level as traded-through. Repeated calls are harmless. */
    public void breakLevel(long nowMillis) {
        if (state == State.BUILDING || state == State.STRONG) {
            state = State.FADING;
            brokenAtMillis = nowMillis;
        }
    }

    /**
     * Remaining strength in [0,1]. Strong-but-not-yet-confirmed levels grow toward
     * their measured confidence; broken levels decay exponentially and never flicker to zero.
     */
    public double strength(long nowMillis) {
        return switch (state) {
            case BUILDING -> {
                long persistence = (firstSeenMillis == Long.MIN_VALUE) ? 0 : nowMillis - firstSeenMillis;
                yield Math.min(0.49, 0.5 * persistence / (double) Math.max(1, cfg.minPersistenceMillis()));
            }
            case STRONG -> normalizedConfidence();
            case BROKEN, FADING -> {
                if (brokenAtMillis == Long.MIN_VALUE) yield 0.0;
                double elapsed = Math.max(0, nowMillis - brokenAtMillis);
                double remaining = Math.pow(0.5, elapsed / cfg.fadeHalfLifeMillis());
                yield Math.max(0, remaining * normalizedConfidence());
            }
        };
    }

    private double normalizedConfidence() {
        double jitterPenalty = 1.0 / (1.0 + size.relativeJitter());
        return Math.max(0.05, jitterPenalty);
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

    /** Current resting size last observed at this level. */
    public double lastSizeOrNull() {
        return lastSize;
    }
}
