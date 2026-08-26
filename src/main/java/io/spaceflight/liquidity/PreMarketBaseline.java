package io.spaceflight.liquidity;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Pre-market liquidity baseline with a decayed rolling window.
 *
 * <p>Before the session opens (default 09:30 ET) every depth update feeds resting-liquidity
 * statistics per side. The pre-open sample establishes "what normal incoming liquidity looks
 * like", and after the open the same window keeps rolling forward (with the same exponential
 * decay), so strength is always measured against a recent, smoothly decaying reference rather
 * than a hard-coded constant or yesterday's regime.</p>
 */
public final class PreMarketBaseline {

    private final long openMillis;
    private final DecayWindow bidLiquidity;
    private final DecayWindow askLiquidity;
    private boolean open;

    /**
     * @param nowMillis current timestamp
     * @param openMillis session-open timestamp (e.g. 09:30 America/New_York)
     * @param rollingUpdates decay half-life in number of updates
     */
    public PreMarketBaseline(long nowMillis, long openMillis, double rollingUpdates) {
        this.openMillis = openMillis;
        this.open = nowMillis >= openMillis;
        this.bidLiquidity = new DecayWindow(rollingUpdates);
        this.askLiquidity = new DecayWindow(rollingUpdates);
    }

    /** Feeds one aggregate resting-size observation per side. */
    public void observe(long nowMillis, double totalBidSize, double totalAskSize) {
        if (!open && nowMillis >= openMillis) {
            open = true;
        }
        bidLiquidity.add(totalBidSize);
        askLiquidity.add(totalAskSize);
    }

    /** True when a usable baseline exists (enough pre-open samples accumulated). */
    public boolean isReady(long minSamples) {
        return bidLiquidity.isPrimed(minSamples) && askLiquidity.isPrimed(minSamples);
    }

    public boolean isOpen() {
        return open;
    }

    /** Timestamp (millis UTC) of the next open at the given exchange-time hour/minute. */
    public static long sessionOpenFor(long nowMillis, int hour, int minute, ZoneId tz) {
        ZonedDateTime zoned = Instant.ofEpochMilli(nowMillis).atZone(tz);
        ZonedDateTime open = zoned.toLocalDate().atTime(hour, minute).atZone(tz);
        return (open.toInstant().toEpochMilli() <= nowMillis)
                ? open.plusDays(1).toInstant().toEpochMilli()
                : open.toInstant().toEpochMilli();
    }

    public static long sessionOpenFor(long nowMillis, int hour, int minute) {
        return sessionOpenFor(nowMillis, hour, minute, ZoneId.of("America/New_York"));
    }

    /**
     * Normalizes an observed level size into [0,1] strength relative to the rolling
     * baseline for that side. Sizes at/above 1.5x baseline map to full strength.
     */
    public double normalize(boolean bidSide, double size) {
        double ref = bidSide ? bidLiquidity.mean() : askLiquidity.mean();
        if (!(ref > 0)) {
            return 0.0;
        }
        return Math.min(1.0, size / (1.5 * ref));
    }

    /** Number of updates absorbed so far (pre-open + session). */
    public long samples() {
        return Math.min(bidLiquidity.count(), askLiquidity.count());
    }
}
