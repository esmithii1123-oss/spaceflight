package io.spaceflight.liquidity;

/**
 * Exponentially-weighted rolling window with an explicit decay half-life.
 *
 * <p>Used everywhere in spaceflight instead of hard cutoffs: samples never disappear
 * abruptly, they decay smoothly so the indicator can never flicker when data ages out.</p>
 */
public final class DecayWindow {

    private final double alpha;
    private double mean = Double.NaN;
    private double varianceM2 = Double.NaN; // incremental second moment about current mean
    private long samples;

    /**
     * @param halfLifeSamples number of samples after which an old observation contributes half as much
     */
    public DecayWindow(double halfLifeSamples) {
        if (!(halfLifeSamples > 0)) {
            throw new IllegalArgumentException("halfLife must be > 0, got " + halfLifeSamples);
        }
        this.alpha = 1.0 - Math.pow(0.5, 1.0 / halfLifeSamples);
    }

    /** Adds one sample and updates decayed mean/variance. */
    public void add(double value) {
        samples++;
        if (Double.isNaN(mean)) {
            mean = value;
            varianceM2 = 0.0;
            return;
        }
        mean += alpha * (value - mean);
        double centered = value - mean;
        varianceM2 += alpha * (centered * centered - varianceM2);
    }

    /** Decayed running mean; NaN until the first sample. */
    public double mean() {
        return mean;
    }

    /** Decayed standard deviation; 0 before at least two effective samples. */
    public double stdDev() {
        return samples < 2 ? 0.0 : Math.sqrt(Math.max(0.0, varianceM2));
    }

    public long count() {
        return samples;
    }

    /** Coefficient of variation of recent sizes: high values indicate jittery algo quotes. */
    public double relativeJitter() {
        return (mean <= 0) ? 0.0 : stdDev() / mean;
    }

    /** True once this window has seen a usable number of samples. */
    public boolean isPrimed(long minimumSamples) {
        return samples >= minimumSamples;
    }
}
