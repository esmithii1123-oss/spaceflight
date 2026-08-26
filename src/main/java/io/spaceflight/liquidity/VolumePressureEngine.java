package io.spaceflight.liquidity;

/**
 * Core volume-pressure detector (spaceflight's re-creation of Bookmap Market Pulse's
 * Volume Pressure / Volume Pressure Imbalance, hardened):
 *
 * <ul>
 *   <li><b>Side separation by construction</b>: every trade is classified via the aggressor
 *       flag — buys press asks, sells press bids. Each side keeps an independent decayed
 *       volume sum and its own normalization scale.</li>
 *   <li><b>Robust normalization</b> instead of a rolling-max "training period": smoothed
 *       pressure is measured against a {@link DecayedQuantile} of recent flow. One giant
 *       print cannot pin the line at 100% forever; the scale follows the current regime.</li>
 *   <li><b>Combined or split display</b>, user-selected: SPLIT shows each side's % of its
 *       own scale; NET shows one line in roughly [-1,+1] where sign = dominant side; BOTH
 *       emits all three values from one snapshot.</li>
 * </ul>
 *
 * <p>Pure logic: no Bookmap types. Time always comes from callers, so behavior is identical
 * live and in replay.</p>
 */
public final class VolumePressureEngine {

    public enum Mode { SPLIT, NET, BOTH }

    /** Immutable tuning knobs. */
    public record Params(double halfLifeSec, Mode mode, double scaleQuantile,
                         int warmupSamples, int maxRetainedSamples) {
        public static Params ofDefaults() {
            return new Params(60.0, Mode.BOTH, 0.75, 40, 2048);
        }
        public Params {
            if (!(halfLifeSec > 0)) throw new IllegalArgumentException("halfLifeSec must be > 0");
            mode = mode == null ? Mode.BOTH : mode;
            if (!(scaleQuantile > 0 && scaleQuantile <= 1)) {
                throw new IllegalArgumentException("scaleQuantile must be in (0,1]");
            }
            warmupSamples = Math.max(1, warmupSamples);
            maxRetainedSamples = Math.max(maxRetainedSamples, warmupSamples);
        }
    }

    /** One sampled reading, in widget-ready units. NaN fields until warmed up. */
    public record Snapshot(long timeMillis, double buyPercent, double sellPercent, double net) {
        public boolean primed() {
            return Double.isFinite(buyPercent) || Double.isFinite(net);
        }
    }

    private final long halfLifeMillis;
    private final Mode mode;
    private final DecayedQuantile buyScale;
    private final DecayedQuantile sellScale;

    // Decay *sums* (not means): magnitude of recently-traded volume per side.
    private double buySmoothed;
    private double sellSmoothed;
    private long lastEventMillis = Long.MIN_VALUE;
    private boolean anyEvent;

    public VolumePressureEngine(Params params) {
        this.halfLifeMillis = Math.round(params.halfLifeSec() * 1000.0);
        this.mode = params.mode();
        this.buyScale = new DecayedQuantile(params.scaleQuantile(), halfLifeMillis,
                params.maxRetainedSamples(), params.warmupSamples());
        this.sellScale = new DecayedQuantile(params.scaleQuantile(), halfLifeMillis,
                params.maxRetainedSamples(), params.warmupSamples());
    }

    /**
     * Feeds one trade print. {@code size} must be non-negative; classification comes straight
     * from the aggressor flag. Time-ordered calls expected; backward seeks reset state to
     * avoid stale forward-data pollution.
     */
    public void onTrade(long nowMillis, boolean bidAggressor, int size) {
        if (!Double.isFinite(nowMillis)) {
            return;
        }
        if (lastEventMillis != Long.MIN_VALUE && nowMillis < lastEventMillis) {
            buySmoothed = sellSmoothed = 0;
            anyEvent = false;
            lastEventMillis = nowMillis;
        }
        advanceDecay(nowMillis);

        int qty = Math.max(0, size);
        if (bidAggressor) {
            buySmoothed += qty;
        } else {
            sellSmoothed += qty;
        }
        anyEvent = true;
    }

    /**
     * Takes one sampling tick: updates both scales with the current smoothed flows and returns
     * the snapshot for the sub-chart lines. Call this on a regular cadence (e.g. TimeListener)
     * so the reference distribution reflects wall-clock flow density, not event arrival gaps.
     */
    public Snapshot sample(long nowMillis) {
        advanceDecay(nowMillis);
        buyScale.update(nowMillis, buySmoothed);
        sellScale.update(nowMillis, sellSmoothed);

        if (!anyEvent
                || !Double.isFinite(buyScale.value())
                || !Double.isFinite(sellScale.value())) {
            return new Snapshot(nowMillis, Double.NaN, Double.NaN, Double.NaN); // warming up
        }
        double buyPct = percent(buySmoothed, buyScale.value());
        double sellPct = percent(sellSmoothed, sellScale.value());

        double net = Double.NaN;
        if (mode == Mode.NET || mode == Mode.BOTH) {
            double ref = Math.max(buyScale.value(), sellScale.value()); // shared scale > 0 here
            net = (buySmoothed - sellSmoothed) / Math.max(1e-9, ref);
            net = clamp(net, -1.5, 1.5); // guard absurd ratios; sign is what matters most
        }
        return switch (mode) {
            case SPLIT -> new Snapshot(nowMillis, buyPct, sellPct, Double.NaN);
            case NET -> new Snapshot(nowMillis, Double.NaN, Double.NaN, net);
            case BOTH -> new Snapshot(nowMillis, buyPct, sellPct, net);
        };
    }

    /**
     * Per-sample % of the side's own reference scale. Zero flow reads exactly 0 (never NaN via
     * 0/0). May exceed 100% on genuine bursts; hard-capped so one data glitch can't break scales.
     */
    private static double percent(double smoothed, double scale) {
        if (!(scale > 0)) {
            return smoothed <= 0 ? 0.0 : Double.NaN;
        }
        if (smoothed <= 0) {
            return 0.0;
        }
        return Math.min(500.0, Math.max(0.0, smoothed / scale * 100.0));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Exponential half-life decay applied to both side sums since the previous timestamp. */
    private void advanceDecay(long nowMillis) {
        if (anyEvent && lastEventMillis != Long.MIN_VALUE && nowMillis > lastEventMillis) {
            double factor = Math.pow(0.5, (nowMillis - lastEventMillis) / (double) halfLifeMillis);
            buySmoothed *= factor;
            sellSmoothed *= factor;
        }
        lastEventMillis = nowMillis;
    }
}
