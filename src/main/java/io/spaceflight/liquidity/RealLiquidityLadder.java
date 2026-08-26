package io.spaceflight.liquidity;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.ZoneId;

import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.CustomModule;
import velox.api.layer1.simplified.DepthDataListener;
import velox.api.layer1.simplified.Indicator;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.Parameter;
import velox.api.layer1.simplified.TimeListener;
import velox.api.layer1.simplified.TradeDataListener;

/**
 * spaceflight — Real Liquidity Ladder.
 *
 * <p>Display-only Bookmap add-on that separates genuine resting liquidity from algorithmic
 * quote noise, builds a pre-market baseline before the session opens, scores level strength
 * with a decayed rolling window, and lets broken levels <i>fade away</i> instead of flickering
 * so buyer pull-outs remain observable.</p>
 *
 * <p>What it draws:</p>
 * <ul>
 *   <li>"Bid liquidity strength" / "Ask liquidity strength" lines (bottom graph): normalized,
 *     baseline-relative strength of each side's real-liquidity ladder.</li>
 *   <li>Break/fade markers on the price chart: when a strong level is traded through, a marker
 *     is stamped at that price and repeated while the level fades, with opacity proportional
 *     to remaining strength.</li>
 * </ul>
 *
 * <p>This module never submits, modifies or cancels orders.</p>
 */
@Layer1SimpleAttachable
@Layer1StrategyName("spaceflight: Real Liquidity Ladder")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class RealLiquidityLadder implements CustomModule, DepthDataListener, TradeDataListener, TimeListener {

    @Parameter(name = "Number of levels per side", reloadOnChange = true)
    private Integer levelsPerSide = 3;

    /** Strictness: minimum time in milliseconds a quote must persist to count as real. */
    @Parameter(name = "Strictness: persistence (ms)", reloadOnChange = true)
    private Integer strictnessPersistenceMillis = 3_000;

    /** Strictness: jitter cap. Higher algo noise tolerance = looser filtering. */
    @Parameter(name = "Strictness: max relative jitter", reloadOnChange = true)
    private Double strictnessMaxJitter = 0.45;

    /** Clarity: fade half-life in seconds. Larger values keep broken levels visible longer. */
    @Parameter(name = "Clarity: fade half-life (sec)", reloadOnChange = true)
    private Integer clarityFadeHalfLifeSec = 12;

    /** Clarity: strength below which a fading level disappears entirely. */
    @Parameter(name = "Clarity: fade floor", reloadOnChange = true)
    private Double clarityFadeFloor = 0.05;

    /** Rolling-window decay half-life, in depth updates. */
    @Parameter(name = "Rolling window half-life (updates)", reloadOnChange = true)
    private Integer rollingHalfLifeUpdates = 20;

    @Parameter(name = "Session open hour (exchange tz)", reloadOnChange = true)
    private Integer openHour = 9;

    @Parameter(name = "Session open minute", reloadOnChange = true)
    private Integer openMinute = 30;

    @Parameter(name = "Show break/fade markers", reloadOnChange = true)
    private Boolean showMarkers = true;

    private static final ZoneId EXCHANGE_TZ = ZoneId.of("America/New_York");

    private InstrumentInfo instrument;
    private int pipsPerTick = 1;
    private long now;
    private long openTimeMillis;
    private RealLiquidityEngine engine;
    private PreMarketBaseline baseline;

    private Indicator bidStrength;
    private Indicator askStrength;
    private Indicator breakMarkers;

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        this.instrument = info;
        this.pipsPerTick = Math.max(1, (int) info.pips);
        this.now = initialState.getCurrentTime() > 0 ? initialState.getCurrentTime() : System.currentTimeMillis();
        this.openTimeMillis = PreMarketBaseline.sessionOpenFor(now, openHour, openMinute);

        rebuildEngine();

        bidStrength = api.registerIndicator("SF: Bid real-liquidity strength", GraphType.BOTTOM);
        bidStrength.setColor(Color.GREEN);
        askStrength = api.registerIndicator("SF: Ask real-liquidity strength", GraphType.BOTTOM);
        askStrength.setColor(Color.RED);
        if (Boolean.TRUE.equals(showMarkers)) {
            breakMarkers = api.registerIndicator("SF: Broken-level fade markers", GraphType.PRIMARY);
            breakMarkers.setColor(Color.ORANGE);
        }
    }

    private void rebuildEngine() {
        baseline = new PreMarketBaseline(now, openTimeMillis, rollingHalfLifeUpdates);
        RealLiquidityEngine.EngineParams params = new RealLiquidityEngine.EngineParams(
                rollingHalfLifeUpdates, strictnessPersistenceMillis, /* minRelativeDepth */ 0.5,
                strictnessMaxJitter, clarityFadeHalfLifeSec * 1000d, clarityFadeFloor, levelsPerSide);
        engine = new RealLiquidityEngine(params, baseline);
    }

    @Override
    public void onDepth(boolean isBid, int priceInTicks, int size) {
        engine.onDepth(now, isBid, priceInTicks, size);
    }

    @Override
    public void onTrade(double priceInUnits, int size, TradeInfo tradeInfo) {
        int fillTick = priceToTick(priceInUnits);
        engine.onTrade(now, fillTick);
        stampFadingMarkers();
    }

    @Override
    public void onTimestamp(long timestamp) {
        this.now = timestamp;

        // Feed aggregate resting sizes into the baseline window every timestamp tick
        // (works both pre-market and during the rolling post-open window).
        baseline.observe(now, engine.totalRestingSize(true), engine.totalRestingSize(false));

        bidStrength.addPoint(engine.sideStrengthIndex(now, true));
        askStrength.addPoint(engine.sideStrengthIndex(now, false));
        stampFadingMarkers();
    }

    /** Raw price in currency units -> integer price-tick index used by the ladder. */
    private int priceToTick(double priceUnits) {
        double unitsPerTick = pipsPerTick; // InstrumentInfo.pips is the size of one tick in raw units
        return (int) Math.round(priceUnits / unitsPerTick);
    }

    /** Stamps markers at prices of strong levels currently fading, opacity ~ remaining strength. */
    private void stampFadingMarkers() {
        if (breakMarkers == null) return;
        for (boolean bidSide : new boolean[] {true, false}) {
            for (LiquidityLevel level : engine.visibleLevels(now, bidSide)) {
                if (level.state() == LiquidityLevel.State.FADING) {
                    double strength = level.strength(now);
                    if (strength >= clarityFadeFloor) {
                        breakMarkers.addIcon(level.price(), fadedIcon(strength), 0, 0);
                    }
                }
            }
        }
    }

    private BufferedImage fadedIcon(double strength) {
        int dim = 18;
        BufferedImage icon = new BufferedImage(dim, dim, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int alpha = (int) Math.round(255 * Math.min(1.0, strength));
            g.setColor(new Color(255, 165, 0, alpha));
            g.setStroke(new BasicStroke(2f));
            int pad = 4;
            g.drawRect(pad, pad, dim - 2 * pad, dim - 2 * pad);
            // A shrinking bar visualizes how much strength remains.
            int barHeight = (int) Math.round((dim - 2 * pad - 2) * Math.max(0.08, strength));
            g.fillRect(pad + 2, dim - pad - barHeight, dim - 2 * pad - 4, barHeight);
        } finally {
            g.dispose();
        }
        return icon;
    }

    @Override
    public void stop() {
        // Display-only module holds no resources beyond the Bookmap-managed indicators.
    }
}
