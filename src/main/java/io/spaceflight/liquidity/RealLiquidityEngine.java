package io.spaceflight.liquidity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Core real-liquidity detector.
 *
 * <p>Consumes incremental depth updates (Bookmap's DepthListener delivers one changed
 * price level at a time) plus trade prints, and maintains a ladder of
 * {@link LiquidityLevel}s keyed by side and price tick:</p>
 * <ul>
 *   <li><b>Noise filter</b>: algo-generated quotes flicker — they appear briefly and jitter.
 *       A level only becomes "real" after it persists a minimum wall-clock time, holds
 *       meaningful depth, and keeps low relative jitter.</li>
 *   <li><b>Fade on break</b>: when price trades through a strong level, the level enters
 *       FADING state and its displayed strength decays exponentially over a configurable
 *       half-life. Buyer pull-outs therefore stay observable instead of blinking out.</li>
 * </ul>
 */
public final class RealLiquidityEngine {

    private final LiquidityLevel.Config levelConfig;
    private final Map<Integer, LiquidityLevel> bids = new HashMap<>();
    private final Map<Integer, LiquidityLevel> asks = new HashMap<>();
    private final int maxLevelsPerSide;
    private PreMarketBaseline baseline;

    public record EngineParams(double decayHalfLifeUpdates, long minPersistenceMillis,
                               double minRelativeDepth, double maxRelativeJitter,
                               double fadeHalfLifeMillis, double fadeFloor,
                               int maxLevelsPerSide) {
        public static EngineParams ofDefaults() {
            return new EngineParams(20, 3_000, 0.5, 0.45, 12_000, 0.05, 3);
        }
    }

    public RealLiquidityEngine(EngineParams params, PreMarketBaseline baseline) {
        this.levelConfig = new LiquidityLevel.Config(params.decayHalfLifeUpdates(),
                params.minPersistenceMillis(), params.minRelativeDepth(),
                params.maxRelativeJitter(), params.fadeHalfLifeMillis(), params.fadeFloor(), 24);
        this.baseline = baseline;
        this.maxLevelsPerSide = params.maxLevelsPerSide();
    }

    /** Feeds one changed price-level quote: Bookmap's {@code onDepth(isBid, price, size)}. */
    public void onDepth(long nowMillis, boolean bidSide, int priceTick, int size) {
        side(bidSide).computeIfAbsent(priceTick, p -> new LiquidityLevel(p, bidSide, levelConfig))
                .observe(size, nowMillis);
        sweepExpired(nowMillis);
    }

    /**
     * Trade print at the given price tick (converted from raw price units by the caller).
     * Any existing level strictly beyond the fill price is considered traded through:
     * for bids above the fill, and asks below the fill.
     */
    public void onTrade(long nowMillis, int fillPriceTick) {
        bids.keySet().stream().filter(p -> p > fillPriceTick)
                .forEach(p -> bids.get(p).breakLevel(nowMillis));
        asks.keySet().stream().filter(p -> p < fillPriceTick)
                .forEach(p -> asks.get(p).breakLevel(nowMillis));
    }

    private void sweepExpired(long nowMillis) {
        bids.values().removeIf(l -> l.isExpired(nowMillis));
        asks.values().removeIf(l -> l.isExpired(nowMillis));
    }

    /** Number of tracked levels for a side (including fading ones). */
    public int trackedCount(boolean bidSide) {
        return side(bidSide).size();
    }

    /** Sum of current resting sizes for a side — used to feed the pre-market baseline. */
    public double totalRestingSize(boolean bidSide) {
        return side(bidSide).values().stream().mapToDouble(LiquidityLevel::lastSizeOrNull).sum();
    }

    /**
     * Current displayable ladder: up to maxLevelsPerSide strongest levels per side.
     * Only confirmed (STRONG) and broken-but-still-fading levels are shown — still-forming
     * quotes stay invisible so the ladder never flickers while strictness filtering runs.
     */
    public List<LiquidityLevel> visibleLevels(long nowMillis, boolean bidSide) {
        List<LiquidityLevel> pool = new ArrayList<>(side(bidSide).values());
        pool.removeIf(l -> l.strength(nowMillis) <= 0);
        pool.removeIf(l -> l.state() == LiquidityLevel.State.BUILDING);
        pool.sort(Comparator.comparingDouble((LiquidityLevel l) -> l.strength(nowMillis)).reversed());
        return pool.subList(0, Math.min(maxLevelsPerSide, pool.size()));
    }

    /**
     * Session-side strength index in [0,1]: total remaining strength across that side's
     * ladder, saturating around four fully-strength levels. Plotted as a line so users can
     * see real liquidity build pre-open, thin out during breaks, and fade smoothly.
     */
    public double sideStrengthIndex(long nowMillis, boolean bidSide) {
        double sum = 0;
        for (LiquidityLevel l : side(bidSide).values()) {
            sum += l.strength(nowMillis);
        }
        return Math.min(1.0, sum / 4.0);
    }

    private Map<Integer, LiquidityLevel> side(boolean bidSide) {
        return bidSide ? bids : asks;
    }
}
