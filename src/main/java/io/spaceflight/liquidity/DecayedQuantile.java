package io.spaceflight.liquidity;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Streaming, decay-weighted quantile estimator — spaceflight's replacement for Market Pulse's
 * "training period" (rolling maximum) normalization.
 *
 * <p>A rolling max sticks to whatever spike it saw once: one outlier pins the reference near
 * the top and the normalized line goes numb afterward. Here every stored observation carries an
 * exponentially decaying weight ({@code 0.5^(age / halfLife)}), so old extremes fade out of the
 * reference smoothly and the scale follows the instrument's *current* flow regime.</p>
 *
 * <p>Pure logic, no Bookmap types, deterministic under replay (time always comes from callers).</p>
 */
final class DecayedQuantile {

    private static final double MIN_WEIGHT = 1e-3; // below this a sample is forgotten entirely

    /** A stored observation: mutable weight, immutable value. */
    private static final class Sample {
        double weight;
        final double value;
        Sample(double weight, double value) {
            this.weight = weight;
            this.value = value;
        }
    }

    private final double targetQuantile; // in (0,1]
    private final double halfLifeMillis;
    private final int maxSamples;
    private final long minSamples;

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private long lastMillis = Long.MIN_VALUE;

    /**
     * @param targetQuantile quantile of the flow distribution used as the 100% scale (e.g. 0.75)
     * @param halfLifeMillis decay half-life for stored observations
     * @param maxSamples hard cap on retained observations (memory bound)
     * @param minSamples minimum observations before {@link #value()} returns a usable number
     */
    DecayedQuantile(double targetQuantile, double halfLifeMillis, int maxSamples, long minSamples) {
        if (!(targetQuantile > 0 && targetQuantile <= 1)) {
            throw new IllegalArgumentException("quantile must be in (0,1], got " + targetQuantile);
        }
        if (!(halfLifeMillis > 0)) {
            throw new IllegalArgumentException("halfLife must be > 0");
        }
        this.targetQuantile = targetQuantile;
        this.halfLifeMillis = halfLifeMillis;
        this.maxSamples = Math.max(minimumViableCapacity(maxSamples), 8);
        this.minSamples = Math.max(1, minSamples);
    }

    private static int minimumViableCapacity(int requested) {
        return requested;
    }

    /** Advances time for all stored observations and inserts one fresh sample at weight 1. */
    void update(long nowMillis, double value) {
        if (!Double.isFinite(value)) {
            return; // refuse non-finite input: one bad value must not poison the reference
        }
        if (lastMillis != Long.MIN_VALUE && nowMillis > lastMillis) {
            double factor = Math.pow(0.5, (nowMillis - lastMillis) / halfLifeMillis);
            for (Sample s : samples) {
                s.weight *= factor;
            }
        } else if (lastMillis != Long.MIN_VALUE && nowMillis < lastMillis) {
            // Backward seek (replay): old samples are "future" relative to seek point.
            // Dropping stale forward-state avoids weight inflation across seeks.
            samples.clear();
        }
        lastMillis = nowMillis;

        samples.addLast(new Sample(1.0, value));
        // Oldest samples always carry the smallest weight, so trimming from the front is safe.
        while (!samples.isEmpty() && samples.peekFirst().weight <= MIN_WEIGHT) {
            samples.removeFirst();
        }
        while (samples.size() > maxSamples) {
            samples.removeFirst();
        }
    }

    /** Number of retained (still-weighted) observations. */
    int count() {
        return samples.size();
    }

    /**
     * Current weighted {@code targetQuantile} of observed values; NaN until {@code minSamples}
     * observations are retained.
     */
    double value() {
        if (samples.size() < minSamples) {
            return Double.NaN;
        }
        Sample[] sorted = samples.toArray(new Sample[0]);
        Arrays.sort(sorted, Comparator.comparingDouble(s -> s.value));

        double total = 0;
        for (Sample s : sorted) {
            total += s.weight;
        }
        double acc = 0;
        for (Sample s : sorted) {
            acc += s.weight;
            if (acc >= targetQuantile * total) {
                return s.value;
            }
        }
        return sorted[sorted.length - 1].value;
    }
}
