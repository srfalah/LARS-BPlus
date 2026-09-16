package edu.jouf.larsbplus.experiment;

import java.util.SplittableRandom;

/**
 * Deterministic adaptation of YCSB's ScrambledZipfianGenerator.
 *
 * <p>The distribution uses YCSB's standard theta ({@code 0.99}), ten-billion-item
 * reference universe, precomputed zeta value, and FNV-64 scrambling. YCSB's
 * thread-local random source is replaced with a seeded {@link SplittableRandom}
 * so paired experiment runs generate identical logical requests.</p>
 */
public final class ScrambledZipfianLongGenerator {
    public static final double ZIPFIAN_CONSTANT = 0.99;
    public static final long REFERENCE_ITEM_COUNT = 10_000_000_000L;
    public static final double REFERENCE_ZETA = 26.46902820178302;

    private static final long FNV_OFFSET_BASIS_64 = 0xCBF29CE484222325L;
    private static final long FNV_PRIME_64 = 1_099_511_628_211L;

    private final long itemCount;
    private final SplittableRandom random;
    private final double theta;
    private final double alpha;
    private final double zeta2Theta;
    private final double eta;
    private final long referenceDomainSize;

    public ScrambledZipfianLongGenerator(long itemCount, long seed) {
        this(itemCount, seed, ZIPFIAN_CONSTANT);
    }

    ScrambledZipfianLongGenerator(long itemCount, long seed, double theta) {
        if (itemCount < 1L) throw new IllegalArgumentException("itemCount must be positive");
        if (!(theta > 0.0 && theta < 1.0)) {
            throw new IllegalArgumentException("theta must be in (0,1)");
        }
        this.itemCount = itemCount;
        this.random = new SplittableRandom(seed);
        this.theta = theta;
        this.alpha = 1.0 / (1.0 - theta);
        this.zeta2Theta = 1.0 + Math.pow(0.5, theta);
        // The official constructor uses the inclusive range [0, REFERENCE_ITEM_COUNT].
        this.referenceDomainSize = REFERENCE_ITEM_COUNT + 1L;
        this.eta = (1.0 - Math.pow(2.0 / referenceDomainSize, 1.0 - theta))
                / (1.0 - zeta2Theta / REFERENCE_ZETA);
    }

    /** Returns a scrambled logical item in {@code [0,itemCount)}. */
    public long nextLong() {
        double u = random.nextDouble();
        double uz = u * REFERENCE_ZETA;
        long rank;
        if (uz < 1.0) {
            rank = 0L;
        } else if (uz < 1.0 + Math.pow(0.5, theta)) {
            rank = 1L;
        } else {
            rank = (long) (referenceDomainSize
                    * Math.pow(eta * u - eta + 1.0, alpha));
            if (rank >= referenceDomainSize) rank = referenceDomainSize - 1L;
        }
        return Math.floorMod(fnvHash64(rank), itemCount);
    }

    /**
     * Returns an item that is already present when the fixed generator domain also
     * reserves logical IDs for future inserts.
     */
    public long nextExistingLong(long currentItemCount) {
        if (currentItemCount < 1L || currentItemCount > itemCount) {
            throw new IllegalArgumentException("currentItemCount outside generator domain");
        }
        long candidate;
        do {
            candidate = nextLong();
        } while (candidate >= currentItemCount);
        return candidate;
    }

    static long fnvHash64(long value) {
        long hash = FNV_OFFSET_BASIS_64;
        long remaining = value;
        for (int i = 0; i < Long.BYTES; i++) {
            long octet = remaining & 0xFFL;
            remaining >>>= Byte.SIZE;
            hash ^= octet;
            hash *= FNV_PRIME_64;
        }
        return hash;
    }
}
