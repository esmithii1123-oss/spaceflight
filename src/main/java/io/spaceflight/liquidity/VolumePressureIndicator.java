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
import java.util.ArrayList;
import java.util.List;

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
 * spaceflight — Volume Pressure (standalone indicator #2).
 *
 * <p>Completely separate from the Real Liquidity Ladder add-on: attachable on its own, with
 * its own settings. Displays Market-Pulse-style buyer/seller flow pressure — hardened — and,
 * because the user wants one indicator per concept, it owns a PRIVATE real-liquidity engine
 * solely to know where confirmed resting levels are, which powers ladder-aware absorption
 * detection (churn through a standing level). No strength lines, no fade markers, no CSV of
 * ladder transitions here.</p>
 *
 * <p>Chart outputs (registered per mode):
 * <ul>
 *   <li>"SF: VP Buy %" / "SF: VP Sell %"  (SPLIT / BOTH modes)</li>
 *   <li>"SF: VP Net"                      (NET / BOTH modes)</li>
 *   <li>"SF: Levels absorbing" + primary-graph stamps</li>
 * </ul>
 *
 * <p>This module never submits, modifies or cancels orders.</p>
 */
@Layer1SimpleAttachable
@Layer1StrategyName("spaceflight: Volume Pressure")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class VolumePressureIndicator implements CustomModule, DepthDataListener, TradeDataListener, TimeListener {

    // ---- Volume Pressure display ----

    /** Display mode: 0 = off, 1 = SPLIT (buy%/sell%), 2 = NET (single net-delta line), 3 = BOTH. */
    @Parameter(name = "Mode (0 off/1 split/2 net/3 both)", reloadOnChange = true)
    private Integer vpMode = 3;

    /** Flow memory: EW half-life of traded-volume sums, seconds. */
    @Parameter(name = "Half-life (sec)", reloadOnChange = true)
    private Integer halfLifeSec = 60;

    /** Reference quantile used as the 100% scale (robust decayed quantile, NOT sticky max). */
    @Parameter(name = "Scale quantile", reloadOnChange = true)
    private Double scaleQuantile = 0.75;

    // ---- Level conditioning (absorption at confirmed resting levels) ----

    /** Where churn is measured from each level's price, in ticks. */
    @Parameter(name = "Level proximity (ticks)", reloadOnChange = true)
    private Integer levelProximityTicks = 3;

    /** Churn (as fraction of a level's resting size) that raises the ABSORPTION flag. */
    @Parameter(name = "Absorption threshold (fraction of level size)", reloadOnChange = true)
    private Double absorptionThresholdFrac = 0.5;

    @Parameter(name = "Show absorption markers", reloadOnChange = true)
    private Boolean showAbsorptionMarkers = true;

    // ---- Private level-confirmation settings (independent of the Ladder add-on) ----

    /** Minimum seconds a quote must persist to count as a confirmed level in THIS module. */
    @Parameter(name = "Level confirmation persistence (sec)", reloadOnChange = true)
    private Integer levelPersistenceSec = 3;

    /** Maximum relative jitter for a quote to confirm as a real level here. */
    @Parameter(name = "Level max relative jitter", reloadOnChange = true)
    private Double levelMaxJitter = 0.45;

    /** Minimum depth vs baseline reference for this module's own level set. */
    @Parameter(name = "Level min depth vs baseline", reloadOnChange = true)
    private Double levelMinDepth = 0.5;

    @Parameter(name = "Rolling window half-life (updates)", reloadOnChange = true)
    private Integer rollingHalfLifeUpdates = 20;

    @Parameter(name = "Session open hour (exchange tz)", reloadOnChange = true)
    private Integer openHour = 9;

    @Parameter(name = "Session open minute", reloadOnChange = true)
    private Integer openMinute = 30;

    @Parameter(name = "Log events to CSV (calibration)", reloadOnChange = true)
    private Boolean logToCsv = false;

    private static final double DEFAULT_CHURN_HALF_LIFE_SEC = 20.0;

    private InstrumentInfo instrumentInfo;
    private double pipsPerTick = 1.0;
    private String alias;
    private long now;
    private long openTimeMillis;
    private boolean csvLoggedOnce;
    private Path csvFile;

    // Private confirmation stack: needed only to know WHERE the confirmed levels are.
    private RealLiquidityEngine levelEngine;
    private PreMarketBaseline baseline;
    private VolumePressureEngine pressureEngine;
    private LevelPressureMeter levelMeter;

    private Indicator buyLine;      // SPLIT/BOTH
    private Indicator sellLine;     // SPLIT/BOTH
    private Indicator netLine;      // NET/BOTH
    private Indicator absorbCount;  // always when module active beyond OFF
    private Indicator absorbMarkers;// PRIMARY graph, optional

    /** side+priceTick -> millis when its CURRENT absorption streak was first stamped. */
    private final java.util.Map<Long, Long> absorptionStampMillis = new java.util.HashMap<>();

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        this.alias = alias;
        this.instrumentInfo = info;
        this.pipsPerTick = Math.max(1e-9, instrumentInfo.pips);
        this.now = initialState.getCurrentTime() > 0 ? initialState.getCurrentTime() : System.currentTimeMillis();
        this.openTimeMillis = PreMarketBaseline.sessionOpenOfDate(now, openHour.intValue(), openMinute.intValue());
        this.absorptionStampMillis.clear();
        this.csvFile = null;

        rebuildStack();

        VolumePressureEngine.Mode mode = mode();
        if (mode == VolumePressureEngine.Mode.SPLIT || mode == VolumePressureEngine.Mode.BOTH) {
            buyLine = api.registerIndicator("SF: VP Buy %", GraphType.BOTTOM);
            buyLine.setColor(new Color(80, 200, 120));
            sellLine = api.registerIndicator("SF: VP Sell %", GraphType.BOTTOM);
            sellLine.setColor(new Color(220, 90, 90));
        } else {
            buyLine = sellLine = null;
        }
        if (mode == VolumePressureEngine.Mode.NET || mode == VolumePressureEngine.Mode.BOTH) {
            netLine = api.registerIndicator("SF: VP Net", GraphType.BOTTOM);
            netLine.setColor(Color.CYAN);
        } else {
            netLine = null;
        }
        if (pressureEngine != null) {
            absorbCount = api.registerIndicator("SF: Levels absorbing", GraphType.BOTTOM);
            absorbCount.setColor(Color.MAGENTA);
            absorbMarkers = Boolean.TRUE.equals(showAbsorptionMarkers)
                    ? api.registerIndicator("SF: Absorption markers", GraphType.PRIMARY)
                    : null;
            if (absorbMarkers != null) {
                absorbMarkers.setColor(Color.MAGENTA);
            }
        } else {
            absorbCount = null;
            absorbMarkers = null;
        }
    }

    private VolumePressureEngine.Mode mode() {
        int m = vpMode == null ? 0 : vpMode;
        return switch (Math.max(0, Math.min(3, m))) {
            case 1 -> VolumePressureEngine.Mode.SPLIT;
            case 2 -> VolumePressureEngine.Mode.NET;
            case 3 -> VolumePressureEngine.Mode.BOTH;
            default -> null; // OFF
        };
    }

    private void rebuildStack() {
        long openMillis = openTimeMillis;
        baseline = new PreMarketBaseline(now, openMillis, rollingHalfLifeUpdates);

        long minPersistenceMillis = Math.max(1, levelPersistenceSec) * 1_000L;
        RealLiquidityEngine.EngineParams params = new RealLiquidityEngine.EngineParams(
                rollingHalfLifeUpdates, minPersistenceMillis, clamp01(levelMinDepth, 0.5),
                levelMaxJitter == null ? 0.45 : Math.max(0.05, levelMaxJitter),
                12_000d, 0.05, 3, 24, null);
        levelEngine = new RealLiquidityEngine(params, baseline);

        VolumePressureEngine.Mode m = mode();
        if (m != null) {
            double hl = Math.max(1, halfLifeSec);
            double q = clamp(scaleQuantile, 0.75, 0.05, 1.0);
            pressureEngine = new VolumePressureEngine(
                    new VolumePressureEngine.Params(hl, m, q, 40, 2048));
        } else {
            pressureEngine = null;
        }

        if (pressureEngine != null && m != null) {
            double thr = clamp(absorptionThresholdFrac, 0.5, 0.05, 2.0);
            levelMeter = new LevelPressureMeter(new LevelPressureMeter.Params(
                    DEFAULT_CHURN_HALF_LIFE_SEC,
                    levelProximityTicks == null ? 3 : Math.max(0, levelProximityTicks),
                    thr, thr / 4));
        } else {
            levelMeter = null;
        }
        csvLoggedOnce = false;
    }

    private static double clamp(Double v, double dflt, double lo, double hi) {
        return v == null ? dflt : Math.max(lo, Math.min(hi, v));
    }

    private static double clamp01(Double v, double dflt) {
        return v == null ? dflt : Math.max(0.0, Math.min(1.0, v));
    }

    @Override
    public void onDepth(boolean isBid, int priceInTicks, int size) {
        if (levelEngine != null) {
            levelEngine.onDepth(now, isBid, priceInTicks, size);
        }
    }

    @Override
    public void onTrade(double priceInUnits, int size, TradeInfo tradeInfo) {
        int fillTick = TickMath.priceToTick(priceInUnits, pipsPerTick);
        if (levelEngine != null) {
            levelEngine.onTrade(now, fillTick);
        }
        if (pressureEngine != null && tradeInfo != null) {
            boolean bidAggressor = tradeInfo.isBidAggressor;
            pressureEngine.onTrade(now, bidAggressor, size);
            if (levelMeter != null) {
                levelMeter.onFill(now, fillTick, bidAggressor, size);
            }
        }
    }

    @Override
    public void onTimestamp(long timestamp) {
        this.now = timestamp;
        rollSessionWindowIfNeeded();

        if (levelEngine != null && baseline != null) {
            baseline.observe(now, levelEngine.largestRestingSize(true), levelEngine.largestRestingSize(false));
        }
        sampleVolumePressure();
    }

    private void rollSessionWindowIfNeeded() {
        long todaysOpen = PreMarketBaseline.sessionOpenOfDate(now, openHour.intValue(), openMinute.intValue());
        if (todaysOpen > openTimeMillis) {
            if (baseline != null) {
                baseline.rollSessionWindowTo(todaysOpen, now);
            }
            openTimeMillis = todaysOpen;
        }
    }

    private void sampleVolumePressure() {
        if (pressureEngine == null) {
            return;
        }
        if (levelMeter != null) {
            List<LevelPressureMeter.StrongLevel> strong = new ArrayList<>();
            collectStrong(true, strong);
            collectStrong(false, strong);
            levelMeter.observeStrongLevels(now, strong);
        }

        VolumePressureEngine.Snapshot snap = pressureEngine.sample(now);
        if (snap.primed()) {
            if (buyLine != null && Double.isFinite(snap.buyPercent())) buyLine.addPoint(snap.buyPercent());
            if (sellLine != null && Double.isFinite(snap.sellPercent())) sellLine.addPoint(snap.sellPercent());
            if (netLine != null && Double.isFinite(snap.net())) netLine.addPoint(snap.net());
        }

        if (levelMeter == null) {
            return;
        }
        int absorbing = 0;
        for (LevelPressureMeter.AbsorptionState st : levelMeter.poll(now)) {
            Long key = stampKey(st.bidSide(), st.priceTick());
            long prev = absorptionStampMillis.getOrDefault(key, Long.MIN_VALUE);
            if (!st.absorbing()) {
                if (prev != Long.MIN_VALUE) {
                    absorptionStampMillis.remove(key);
                    appendCsv(now, st.bidSide(), st.priceTick(), "ABSORPTION_CLEAR", st.churnFrac());
                }
                continue;
            }
            absorbing++;
            if (prev == Long.MIN_VALUE) {
                absorptionStampMillis.put(key, now);
                appendCsv(now, st.bidSide(), st.priceTick(), "ABSORPTION_START", st.churnFrac());
                if (absorbMarkers != null) {
                    double rawPrice = TickMath.tickToPrice(st.priceTick(), pipsPerTick);
                    absorbMarkers.addIcon(rawPrice, absorptionIcon(Math.min(1.0, st.churnFrac())), 0, 0);
                }
            }
        }
        if (absorbCount != null) {
            absorbCount.addPoint(absorbing);
        }
    }

    private void collectStrong(boolean bidSide, List<LevelPressureMeter.StrongLevel> out) {
        for (RealLiquidityEngine.ScoredLevel sl : levelEngine.visibleLevelsScored(now, bidSide)) {
            LiquidityLevel l = sl.level();
            if (l.state() == LiquidityLevel.State.STRONG && l.lastSize() > 0) {
                out.add(new LevelPressureMeter.StrongLevel(l.isBidSide(), l.price(), l.lastSize()));
            }
        }
    }

    private static Long stampKey(boolean bidSide, int priceTick) {
        return ((long) (bidSide ? 1 : 0) << 32) | (priceTick & 0xFFFFFFFFL);
    }

    /** Solid magenta block whose height scales with how much churn the level has absorbed. */
    private BufferedImage absorptionIcon(double intensity) {
        int dim = 18;
        BufferedImage icon = new BufferedImage(dim, dim, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(255, 0, 255, (int) Math.round(255 * Math.min(1.0, intensity))));
            g.setStroke(new BasicStroke(2f));
            int pad = 4;
            g.drawRect(pad, pad, dim - 2 * pad, dim - 2 * pad);
            int barHeight = (int) Math.round((dim - 2 * pad - 2) * Math.max(0.15, intensity));
            g.fillRect(pad + 2, dim - pad - barHeight, dim - 2 * pad - 4, barHeight);
        } finally {
            g.dispose();
        }
        return icon;
    }

    // ------------------------------------------------------------------
    // Optional calibration CSV (best-effort; never disturbs processing).
    // ------------------------------------------------------------------

    private synchronized void appendCsv(long timeMillis, boolean bidSide, int priceTick,
                                        String event, double churnFrac) {
        if (!Boolean.TRUE.equals(logToCsv)) {
            return;
        }
        try {
            ensureCsv();
            Files.writeString(csvFile, "%s,%s,%s,%s,%d,%.3f%s".formatted(
                            Instant.ofEpochMilli(timeMillis), alias,
                            bidSide ? "BID" : "ASK", event, priceTick, churnFrac,
                            System.lineSeparator()),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignore) {
            // logging must never break processing
        }
    }

    private void ensureCsv() throws IOException {
        if (csvFile != null) {
            return;
        }
        Path dir = Path.of(System.getProperty("user.home"), ".spaceflight");
        Files.createDirectories(dir);
        csvFile = dir.resolve("volume-pressure-" + safe(alias) + "-" + now + ".csv");
        Files.writeString(csvFile, "time_iso,alias,side,event,price_tick,churn_frac"
                + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (!csvLoggedOnce) {
            csvLoggedOnce = true;
        }
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    @Override
    public void stop() {
        // Display-only module holds no resources beyond Bookmap-managed indicators.
    }
}
