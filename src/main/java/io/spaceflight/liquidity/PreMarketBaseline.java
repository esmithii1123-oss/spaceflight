package io.spaceflight.liquidity;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Pre-market liquidity baseline with a decayed rolling window.
 *
 * <p>Before the session opens (default 09:30 ET) every depth tick feeds the per-side size of
 * the strongest resting level into a decayed window, establishing "what a genuinely large,
 * normal level looks like" for this instrument right now. After the open the same window keeps
 * rolling forward with the same exponential decay, so level strength is always measured against
 * a recent reference rather than a hard-coded constant or yesterday's regime.</p>
 *
 * <p>The reference is the <b>strongest level</b> seen on each tick (not the book-wide total),
 * because levels are scored individually against it.</p>
 */
public final class PreMarketBaseline {

    private long openMillis;
    private long lastMillis = Long.MIN_VALUE;
    private boolean open;
    private final DecayWindow bidLiquidity;
    private final DecayWindow askLiquidity;

    /**
     * @param nowMillis current timestamp
     * @param openMillis session-open timestamp (e.g. 09:30 America/New_York)
     * @param rollingUpdates decay half-life in number of ticks
     */
    public PreMarketBaseline(long nowMillis, long openMillis, double rollingUpdates) {
        this.openMillis = openMillis;
        this.open = nowMillis >= openMillis;
        this.bidLiquidity = new DecayWindow(rollingUpdates);
        this.askLiquidity = new DecayWindow(rollingUpdates);
    }

    /** Feeds one strongest-level observation per side (0 if no tracked levels yet). */
    public void observe(long nowMillis, double leadingBidSize, double leadingAskSize) {
        lastMillis = nowMillis;
        if (!open && nowMillis >= openMillis) {
            open = true;
        }
        bidLiquidity.add(leadingBidSize);
        askLiquidity.add(leadingAskSize);
    }

    /**
     * Rolls the session-open boundary to the next session (day rollover). The decayed windows
     * are kept: overnight/pre-open activity naturally re-primes the baseline for the new day.
     * Open state is recomputed against the current time.
     */
    public void rollSessionWindowTo(long nextOpenMillis, long nowMillis) {
        if (nextOpenMillis <= openMillis) {
            return; // ignore stale/non-forwarding boundaries
        }
        this.openMillis = nextOpenMillis;
        this.open = nowMillis >= nextOpenMillis;
    }

    /** True when a usable baseline exists (enough samples accumulated). */
    public boolean isReady(long minSamples) {
        return bidLiquidity.isPrimed(minSamples) && askLiquidity.isPrimed(minSamples);
    }

    public boolean isOpen() {
        return open;
    }

    public long lastTimestamp() {
        return lastMillis;
    }

    /** Decayed mean of the leading level size for a side; NaN before any sample. */
    public double referenceSize(boolean bidSide) {
        return bidSide ? bidLiquidity.mean() : askLiquidity.mean();
    }

    /**
     * Normalizes an observed level size into [0,1] strength relative to the rolling baseline
     * for that side. Sizes at/above 1.5x the reference map to full strength; sizes below 50%
     * of the reference map to zero.
     */
    public double normalize(boolean bidSide, double size) {
        double ref = referenceSize(bidSide);
        if (!(ref > 0)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, size / (1.5 * ref)));
    }

    /** Number of ticks absorbed so far (pre-open + session). */
    public long samples() {
        return Math.min(bidLiquidity.count(), askLiquidity.count());
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
}
