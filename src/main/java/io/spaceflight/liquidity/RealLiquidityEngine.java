package io.spaceflight.liquidity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Core real-liquidity detector.
 *
 * <p>Consumes incremental depth updates (Bookmap's DepthListener delivers one changed price
 * level at a time) plus trade prints, and maintains a ladder of {@link LiquidityLevel}s:</p>
 * <ul>
 *   <li><b>Noise filter</b>: algo-generated quotes flicker — they appear briefly and jitter.
 *       A level only becomes "real" after it persists a minimum wall-clock time, holds enough
 *       updates to make stability measurable, keeps low relative jitter, and holds depth that
 *       is meaningful relative to the decayed pre-market/session baseline.</li>
 *   <li><b>Fade on break</b>: when price trades through a strong level, the level enters
 *       FADING state and its displayed strength decays exponentially over a configurable
 *       half-life. Buyer pull-outs therefore stay observable instead of blinking out.</li>
 * </ul>
 *
 * <p>Lifecycle transitions are optionally forwarded to an event sink for replay calibration.</p>
 */
public final class RealLiquidityEngine {

    private final LiquidityLevel.Config levelConfig;
    private final Map<Integer, LiquidityLevel> bids = new HashMap<>();
    private final Map<Integer, LiquidityLevel> asks = new HashMap<>();
    private final PreMarketBaseline baseline; // nullable in unit tests / before priming
    private final int maxLevelsPerSide;
    private final long minPersistenceMillis;
    private final double minRelativeDepth;
    private final double maxRelativeJitter;
    private final int minPromotionSamples;
    private final Consumer<LiquidityEvent> eventSink; // nullable

    /** One observable ladder transition, for replay calibration logging. */
    public record LiquidityEvent(long timeMillis, boolean bidSide, int priceTick,
                                 String type /* PROMOTE | BROKEN */,
                                 LiquidityLevel.State from, LiquidityLevel.State to,
                                 int lastSize, double jitter, double normalizedDepth) {}

    public record EngineParams(double decayHalfLifeUpdates, long minPersistenceMillis,
                               double minRelativeDepth, double maxRelativeJitter,
                               double fadeHalfLifeMillis, double fadeFloor,
                               int maxLevelsPerSide, int minPromotionSamples,
                               Consumer<LiquidityEvent> eventSink) {
        public static EngineParams ofDefaults() {
            return new EngineParams(20, 3_000, 0.5, 0.45, 12_000, 0.05, 3, 24, null);
        }
    }

    public RealLiquidityEngine(EngineParams params, PreMarketBaseline baseline) {
        this.levelConfig = new LiquidityLevel.Config(params.decayHalfLifeUpdates(),
                params.minPersistenceMillis(), params.fadeHalfLifeMillis(), params.fadeFloor());
        this.baseline = baseline;
        this.maxLevelsPerSide = params.maxLevelsPerSide();
        this.minPersistenceMillis = params.minPersistenceMillis();
        this.minRelativeDepth = Math.max(0.0, Math.min(1.0, params.minRelativeDepth()));
        this.maxRelativeJitter = params.maxRelativeJitter();
        this.minPromotionSamples = Math.max(8, params.minPromotionSamples());
        this.eventSink = params.eventSink();
    }

    /** Feeds one changed price-level quote: Bookmap's {@code onDepth(isBid, price, size)}. */
    public void onDepth(long nowMillis, boolean bidSide, int priceTick, int size) {
        side(bidSide).computeIfAbsent(priceTick, p -> new LiquidityLevel(p, bidSide, levelConfig));
        LiquidityLevel level = side(bidSide).get(priceTick);
        LiquidityLevel.State before = level.state();

        level.observe(size, nowMillis);
        maybePromote(level, bidSide, nowMillis);

        if (before != level.state()) {
            emit(nowMillis, bidSide, priceTick, transitionType(before, level.state()),
                    before, level.state(), level);
        }
        sweepExpired(nowMillis);
    }

    private void maybePromote(LiquidityLevel level, boolean bidSide, long nowMillis) {
        if (level.state() != LiquidityLevel.State.BUILDING || !level.isSamplePrimed(minPromotionSamples)) {
            return;
        }
        // Persistence: wall-clock presence since first appearance.
        if (level.persistence(nowMillis) < minPersistenceMillis) {
            return;
        }
        // Stability: flickering algo quotes have jitter above the cap once their window has
        // enough samples (that's what the sample floor guarantees).
        if (level.jitter() > maxRelativeJitter) {
            return;
        }
        // Depth: meaningful relative to the decayed session baseline. Until the baseline is
        // ready this gate passes — strictness comes from persistence + jitter instead, so a
        // freshly-attached module still functions.
        double normDepth = normalizedDepth(bidSide, level.lastSize());
        if (baselineReady() && normDepth < minRelativeDepth) {
            return;
        }
        level.markStrong();
    }

    private boolean baselineReady() {
        return baseline != null && baseline.isReady(Math.min(minPromotionSamples, 30));
    }

    /** Level size relative to the baseline reference in [0,1] (saturating). */
    private double normalizedDepth(boolean bidSide, int size) {
        if (baseline == null) {
            return 1.0; // no baseline context (tests): depth gate passes through
        }
        return baseline.normalize(bidSide, size);
    }

    /**
     * Depth confidence multiplier applied on top of lifecycle strength: levels barely meeting
     * the depth floor display near--floor confidence; large levels count fully.
     */
    private double depthConfidence(boolean bidSide, LiquidityLevel level) {
        if (!baselineReady()) {
            return 1.0;
        }
        double depth = normalizedDepth(bidSide, level.lastSize());
        double floor = Math.max(0.05, minRelativeDepth);
        return Math.min(1.0, depth / (2 * floor));
    }

    /**
     * Trade print at the given price tick (converted from raw price units by the caller).
     * Any existing level strictly beyond the fill price is considered traded through:
     * for bids above the fill, and asks below the fill.
     */
    public void onTrade(long nowMillis, int fillPriceTick) {
        for (boolean bidSide : new boolean[] {true, false}) {
            for (Map.Entry<Integer, LiquidityLevel> e : side(bidSide).entrySet()) {
                Integer price = e.getKey();
                boolean crossed = bidSide ? price > fillPriceTick : price < fillPriceTick;
                if (!crossed) continue;
                LiquidityLevel level = e.getValue();
                LiquidityLevel.State before = level.state();
                level.breakLevel(nowMillis);
                if (before != level.state()) {
                    emit(nowMillis, bidSide, price, "BROKEN", before, level.state(), level);
                }
            }
        }
        sweepExpired(nowMillis);
    }

    private void sweepExpired(long nowMillis) {
        bids.values().removeIf(l -> l.isExpired(nowMillis) || l.isAbandoned(nowMillis));
        asks.values().removeIf(l -> l.isExpired(nowMillis) || l.isAbandoned(nowMillis));
    }

    /** Number of tracked levels for a side (including fading ones). */
    public int trackedCount(boolean bidSide) {
        return side(bidSide).size();
    }

    /** Strongest resting size currently tracked on a side (0 if none) — feeds the baseline.
     *  FADING levels are excluded: their size field is stale after the break and must not
     *  pollute the rolling baseline reference. */
    public double largestRestingSize(boolean bidSide) {
        return side(bidSide).values().stream()
                .filter(l -> l.state() != LiquidityLevel.State.FADING)
                .mapToInt(LiquidityLevel::lastSize)
                .filter(s -> s > 0)
                .max().orElse(0);
    }

    /**
     * Current displayable ladder: up to maxLevelsPerSide strongest levels per side.
     * Only confirmed (STRONG) and broken-but-still-fading levels are shown — still-forming
     * quotes stay invisible so the ladder never flickers while filtering runs. Displayed
     * strength multiplies lifecycle strength by baseline-relative depth confidence.
     */
    public List<LiquidityLevel> visibleLevels(long nowMillis, boolean bidSide) {
        List<ScoredLevel> pool = scored(nowMillis, bidSide);
        List<LiquidityLevel> out = new ArrayList<>();
        for (int i = 0; i < Math.min(maxLevelsPerSide, pool.size()); i++) {
            out.add(pool.get(i).level);
        }
        return out;
    }

    /** Scored variant of {@link #visibleLevels} for callers that need display strength too. */
    public List<ScoredLevel> visibleLevelsScored(long nowMillis, boolean bidSide) {
        List<ScoredLevel> pool = scored(nowMillis, bidSide);
        return pool.subList(0, Math.min(maxLevelsPerSide, pool.size()));
    }

    public record ScoredLevel(LiquidityLevel level, double displayedStrength) {}

    private List<ScoredLevel> scored(long nowMillis, boolean bidSide) {
        List<ScoredLevel> pool = new ArrayList<>();
        for (LiquidityLevel l : side(bidSide).values()) {
            if (l.state() == LiquidityLevel.State.BUILDING) continue;
            double s = l.strength(nowMillis);
            if (s <= 0) continue;
            pool.add(new ScoredLevel(l, s * depthConfidence(bidSide, l)));
        }
        pool.sort(Comparator.comparingDouble((ScoredLevel sl) -> sl.displayedStrength()).reversed());
        return pool;
    }

    /**
     * Session-side strength index in [0,1]: depth-adjusted total remaining strength across
     * that side's ladder, saturating around four fully-strength levels. Plotted as a line so
     * users can see real liquidity build pre-open, thin out during breaks, and fade smoothly.
     */
    public double sideStrengthIndex(long nowMillis, boolean bidSide) {
        double sum = 0;
        for (ScoredLevel sl : scored(nowMillis, bidSide)) {
            sum += sl.displayedStrength();
        }
        return Math.min(1.0, sum / 4.0);
    }

    private String transitionType(LiquidityLevel.State from, LiquidityLevel.State to) {
        return to == LiquidityLevel.State.FADING ? "BROKEN" : "PROMOTE";
    }

    private void emit(long nowMillis, boolean bidSide, int priceTick, String type,
                      LiquidityLevel.State from, LiquidityLevel.State to, LiquidityLevel level) {
        if (eventSink == null) return;
        try {
            eventSink.accept(new LiquidityEvent(nowMillis, bidSide, priceTick, type,
                    from, to, level.lastSize(), level.jitter(),
                    normalizedDepth(bidSide, level.lastSize())));
        } catch (RuntimeException ignore) {
            // Logging must never break indicator processing.
        }
    }

    private Map<Integer, LiquidityLevel> side(boolean bidSide) {
        return bidSide ? bids : asks;
    }
}
