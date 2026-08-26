package io.spaceflight.liquidity;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

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
 *     is stamped at that price and refreshed at most once per {@code #markerRefreshMillis}
 *     while the level fades, with opacity proportional to remaining strength.</li>
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

    /** Strictness: level size must reach this fraction of the baseline reference (0..1). */
    @Parameter(name = "Strictness: min depth vs baseline", reloadOnChange = true)
    private Double strictnessMinDepth = 0.5;

    /** Clarity: fade half-life in seconds. Larger values keep broken levels visible longer. */
    @Parameter(name = "Clarity: fade half-life (sec)", reloadOnChange = true)
    private Integer clarityFadeHalfLifeSec = 12;

    /** Clarity: strength below which a fading level disappears entirely. */
    @Parameter(name = "Clarity: fade floor", reloadOnChange = true)
    private Double clarityFadeFloor = 0.05;

    /** Rolling-window decay half-life, in depth ticks. */
    @Parameter(name = "Rolling window half-life (updates)", reloadOnChange = true)
    private Integer rollingHalfLifeUpdates = 20;

    /** Minimum updates before stability statistics can promote a quote. */
    @Parameter(name = "Min samples before promotion", reloadOnChange = true)
    private Integer minPromotionSamples = 24;

    @Parameter(name = "Session open hour (exchange tz)", reloadOnChange = true)
    private Integer openHour = 9;

    @Parameter(name = "Session open minute", reloadOnChange = true)
    private Integer openMinute = 30;

    @Parameter(name = "Show break/fade markers", reloadOnChange = true)
    private Boolean showMarkers = true;

    /** Refresh rate for repeated fade markers at a broken level (ms). */
    @Parameter(name = "Fade marker refresh (ms)", reloadOnChange = true)
    private Integer markerRefreshMillis = 5_000;

    /** Replay-calibration mode: log every ladder transition to a CSV file. */
    @Parameter(name = "Log transitions to CSV (calibration)", reloadOnChange = true)
    private Boolean logTransitionsToCsv = false;

    private static final long MARKER_LOG_TTL_MILLIS = 60_000L;

    private InstrumentInfo instrument;
    private double pipsPerTick = 1.0;
    private String alias;
    private long now;
    private long openTimeMillis;
    private boolean promotedBaselineReadyLogged;
    private RealLiquidityEngine engine;
    private PreMarketBaseline baseline;

    private Indicator bidStrength;
    private Indicator askStrength;
    private Indicator breakMarkers;

    /** priceTick -> last stamp time; deduplicates fade markers and expires old entries. */
    private final java.util.Map<Integer, Long> lastStampMillis = new java.util.HashMap<>();

    /** Lazily-opened CSV writer for calibration mode. */
    private Path csvFile;

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        this.alias = alias;
        this.instrument = info;
        this.pipsPerTick = Math.max(1e-9, instrument.pips); // double: 0.25 for MNQ/ES, 1.0 for YM
        this.now = initialState.getCurrentTime() > 0 ? initialState.getCurrentTime() : System.currentTimeMillis();
        this.openTimeMillis = PreMarketBaseline.sessionOpenOfDate(now, openHour.intValue(), openMinute.intValue());
        this.lastStampMillis.clear();
        this.promotedBaselineReadyLogged = false;
        this.csvFile = null;

        rebuildEngine();

        bidStrength = api.registerIndicator("SF: Bid real-liquidity strength", GraphType.BOTTOM);
        bidStrength.setColor(Color.GREEN);
        askStrength = api.registerIndicator("SF: Ask real-liquidity strength", GraphType.BOTTOM);
        askStrength.setColor(Color.RED);
        if (Boolean.TRUE.equals(showMarkers)) {
            breakMarkers = api.registerIndicator("SF: Broken-level fade markers", GraphType.PRIMARY);
            breakMarkers.setColor(Color.ORANGE);
        } else {
            breakMarkers = null;
        }
    }

    private void rebuildEngine() {
        baseline = new PreMarketBaseline(now, openTimeMillis, rollingHalfLifeUpdates);

        java.util.function.Consumer<RealLiquidityEngine.LiquidityEvent> sink =
                Boolean.TRUE.equals(logTransitionsToCsv) ? ev -> appendCsv(ev) : null;

        RealLiquidityEngine.EngineParams params = new RealLiquidityEngine.EngineParams(
                rollingHalfLifeUpdates, strictnessPersistenceMillis, strictnessMinDepth,
                strictnessMaxJitter, clarityFadeHalfLifeSec * 1000d, clarityFadeFloor,
                levelsPerSide, minPromotionSamples, sink);
        engine = new RealLiquidityEngine(params, baseline);
    }

    @Override
    public void onDepth(boolean isBid, int priceInTicks, int size) {
        engine.onDepth(now, isBid, priceInTicks, size);
    }

    @Override
    public void onTrade(double priceInUnits, int size, TradeInfo tradeInfo) {
        engine.onTrade(now, priceToTick(priceInUnits));
    }

    @Override
    public void onTimestamp(long timestamp) {
        this.now = timestamp;
        rollSessionWindowIfNeeded();

        // Feed the strongest resting level per side into the decayed baseline window — works
        // both pre-market (building the reference) and during the session (rolling forward).
        boolean wasReady = baselineReady();
        baseline.observe(now, engine.largestRestingSize(true), engine.largestRestingSize(false));
        if (!wasReady && baselineReady() && !promotedBaselineReadyLogged) {
            promotedBaselineReadyLogged = true;
            if (Boolean.TRUE.equals(logTransitionsToCsv)) {
                appendCsvLine(now, "-,-,BASELINE_READY,baseline ref bid=%.0f ask=%.0f".formatted(
                        baseline.referenceSize(true), baseline.referenceSize(false)));
            }
        }

        bidStrength.addPoint(engine.sideStrengthIndex(now, true));
        askStrength.addPoint(engine.sideStrengthIndex(now, false));
        stampFadingMarkers();
    }

    /**
     * Keeps the boundary pinned to the open of the current ET date. Before today's open the
     * baseline is in pre-open accumulation; after it, the session is open. Crosses exactly at
     * midnight ET, not one minute after the open.
     */
    private void rollSessionWindowIfNeeded() {
        long todaysOpen = PreMarketBaseline.sessionOpenOfDate(now,
                openHour.intValue(), openMinute.intValue());
        // Adopt only forward-consistent boundaries: on a backward replay seek the baseline
        // rejects the roll, and the module must not desync from it.
        if (todaysOpen > openTimeMillis) {
            baseline.rollSessionWindowTo(todaysOpen, now);
            openTimeMillis = todaysOpen;
        }
    }

    private boolean baselineReady() {
        return baseline.isReady(Math.min(Math.max(8, minPromotionSamples), 30));
    }

    /** Raw price in currency units -> integer price-tick index used by the ladder. */
    private int priceToTick(double priceUnits) {
        return TickMath.priceToTick(priceUnits, pipsPerTick);
    }

    /** Stamps fade markers with dedupe: one per break, refreshed at most every N ms while fading. */
    private void stampFadingMarkers() {
        if (breakMarkers == null) return;
        expireOldStamps();
        for (boolean bidSide : new boolean[] {true, false}) {
            for (RealLiquidityEngine.ScoredLevel sl : fadingScored(bidSide)) {
                LiquidityLevel level = sl.level();
                double strength = sl.displayedStrength();
                if (strength <= 0 || level.state() != LiquidityLevel.State.FADING) continue;
                long last = lastStampMillis.getOrDefault(level.price(), Long.MIN_VALUE);
                long refresh = Math.max(1_000, markerRefreshMillis); // guard against 0/negative UI input
                if (now - last >= refresh) {
                    lastStampMillis.put(level.price(), now);
                    // PRIMARY graphs render in raw price units; ladder stores tick indices.
                    double rawPrice = TickMath.tickToPrice(level.price(), pipsPerTick);
                    breakMarkers.addIcon(rawPrice, fadedIcon(strength), 0, 0);
                }
            }
        }
    }

    private java.util.List<RealLiquidityEngine.ScoredLevel> fadingScored(boolean bidSide) {
        return engine.visibleLevelsScored(now, bidSide);
    }

    private void expireOldStamps() {
        lastStampMillis.entrySet().removeIf(e -> now - e.getValue() > MARKER_LOG_TTL_MILLIS);
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

    // ------------------------------------------------------------------
    // Calibration logging (CSV). Best-effort: failures are swallowed so the
    // indicator display is never affected.
    // ------------------------------------------------------------------

    private synchronized void appendCsv(RealLiquidityEngine.LiquidityEvent ev) {
        appendCsvLine(ev.timeMillis(), "%s,%d,%s,%s,%s,%d,%.3f,%.3f".formatted(
                ev.bidSide() ? "BID" : "ASK",
                ev.priceTick(),
                ev.type(),
                ev.from(),
                ev.to(),
                ev.lastSize(),
                ev.jitter(),
                ev.normalizedDepth()));
    }

    private synchronized void appendCsvLine(long timeMillis, String line) {
        try {
            ensureCsv();
            Files.writeString(csvFile, timeMillis + "," + Instant.ofEpochMilli(timeMillis)
                            + "," + alias + "," + line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignore) {
            // Display-only tooling: logging problems must never disturb processing.
        }
    }

    private void ensureCsv() throws IOException {
        if (csvFile != null) return;
        Path dir = Path.of(System.getProperty("user.home"), ".spaceflight");
        Files.createDirectories(dir);
        csvFile = dir.resolve("liquidity-" + safe(alias) + "-" + now + ".csv");
        Files.writeString(csvFile,
                "time_millis,time_iso,alias,side,price_tick,type,state_from,state_to,last_size,jitter,norm_depth"
                        + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    @Override
    public void stop() {
        // Display-only module holds no resources beyond the Bookmap-managed indicators.
    }
}
