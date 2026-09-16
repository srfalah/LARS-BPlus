package edu.jouf.larsbplus.experiment;

import java.util.SplittableRandom;

/** Deterministic inclusive uniform scan-length generator for YCSB-E. */
public final class UniformScanLengthGenerator {
    private final int minimum;
    private final int maximum;
    private final SplittableRandom random;

    public UniformScanLengthGenerator(int minimum, int maximum, long seed) {
        if (minimum < 1 || maximum < minimum) {
            throw new IllegalArgumentException("invalid scan-length bounds");
        }
        this.minimum = minimum;
        this.maximum = maximum;
        this.random = new SplittableRandom(seed);
    }

    public int nextInt() {
        return random.nextInt(minimum, maximum + 1);
    }
}
