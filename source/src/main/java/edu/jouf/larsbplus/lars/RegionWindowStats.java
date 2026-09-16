package edu.jouf.larsbplus.lars;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Immutable per-region observation-window statistics. */
public final class RegionWindowStats {
    private static final OperationType[] TYPES = OperationType.values();

    private final long regionId;
    private final long windowSequence;
    private final long windowStartOperation;
    private final long windowEndOperation;
    private final int totalOperations;
    private final int[] counts;
    private final int[] latencySampleCounts;
    private final double[] p50Nanos;
    private final double[] p95Nanos;
    private final double[] p99Nanos;
    private final double overallP99Nanos;
    private final double averageScanAmplification;
    private final long leafSplits;
    private final long leafMerges;
    private final double averageOccupancy;
    private final double minimumOccupancy;
    private final int leafCount;
    private final int recordCount;

    /** Compatibility constructor used by tests and diagnostic self-check fixtures. */
    public RegionWindowStats(long regionId,
                             long windowSequence,
                             int totalOperations,
                             Map<OperationType, Integer> counts,
                             Map<OperationType, Integer> latencySampleCounts,
                             Map<OperationType, Double> p50Nanos,
                             Map<OperationType, Double> p95Nanos,
                             Map<OperationType, Double> p99Nanos,
                             double overallP99Nanos,
                             double averageScanAmplification,
                             long leafSplits,
                             long leafMerges,
                             double averageOccupancy,
                             double minimumOccupancy,
                             int leafCount,
                             int recordCount) {
        this(regionId, windowSequence, -1L, -1L, totalOperations,
                toIntArray(counts), toIntArray(latencySampleCounts),
                toDoubleArray(p50Nanos), toDoubleArray(p95Nanos), toDoubleArray(p99Nanos),
                overallP99Nanos, averageScanAmplification, leafSplits, leafMerges,
                averageOccupancy, minimumOccupancy, leafCount, recordCount, false);
    }

    /** Package-private primitive constructor for the foreground monitoring path. */
    RegionWindowStats(long regionId,
                      long windowSequence,
                      long windowStartOperation,
                      long windowEndOperation,
                      int totalOperations,
                      int[] counts,
                      int[] latencySampleCounts,
                      double[] p50Nanos,
                      double[] p95Nanos,
                      double[] p99Nanos,
                      double overallP99Nanos,
                      double averageScanAmplification,
                      long leafSplits,
                      long leafMerges,
                      double averageOccupancy,
                      double minimumOccupancy,
                      int leafCount,
                      int recordCount) {
        this(regionId, windowSequence, windowStartOperation, windowEndOperation, totalOperations,
                counts, latencySampleCounts, p50Nanos, p95Nanos, p99Nanos,
                overallP99Nanos, averageScanAmplification, leafSplits, leafMerges,
                averageOccupancy, minimumOccupancy, leafCount, recordCount, true);
    }

    private RegionWindowStats(long regionId,
                              long windowSequence,
                              long windowStartOperation,
                              long windowEndOperation,
                              int totalOperations,
                              int[] counts,
                              int[] latencySampleCounts,
                              double[] p50Nanos,
                              double[] p95Nanos,
                              double[] p99Nanos,
                              double overallP99Nanos,
                              double averageScanAmplification,
                              long leafSplits,
                              long leafMerges,
                              double averageOccupancy,
                              double minimumOccupancy,
                              int leafCount,
                              int recordCount,
                              boolean clonePrimitiveInputs) {
        this.regionId = regionId;
        this.windowSequence = windowSequence;
        this.windowStartOperation = windowStartOperation;
        this.windowEndOperation = windowEndOperation;
        this.totalOperations = totalOperations;
        // The primitive monitoring constructor passes newly-created percentile/sample arrays but
        // reuses the accumulator's operation-count array. Clone only the latter on that path.
        this.counts = clonePrimitiveInputs ? counts.clone() : counts;
        this.latencySampleCounts = latencySampleCounts;
        this.p50Nanos = p50Nanos;
        this.p95Nanos = p95Nanos;
        this.p99Nanos = p99Nanos;
        this.overallP99Nanos = overallP99Nanos;
        this.averageScanAmplification = averageScanAmplification;
        this.leafSplits = leafSplits;
        this.leafMerges = leafMerges;
        this.averageOccupancy = averageOccupancy;
        this.minimumOccupancy = minimumOccupancy;
        this.leafCount = leafCount;
        this.recordCount = recordCount;
    }

    public long regionId() { return regionId; }
    public long windowSequence() { return windowSequence; }
    public long windowStartOperation() { return windowStartOperation; }
    public long windowEndOperation() { return windowEndOperation; }
    public int totalOperations() { return totalOperations; }
    public double overallP99Nanos() { return overallP99Nanos; }
    public double averageScanAmplification() { return averageScanAmplification; }
    public long leafSplits() { return leafSplits; }
    public long leafMerges() { return leafMerges; }
    public double averageOccupancy() { return averageOccupancy; }
    public double minimumOccupancy() { return minimumOccupancy; }
    public int leafCount() { return leafCount; }
    public int recordCount() { return recordCount; }

    public int count(OperationType type) { return counts[type.ordinal()]; }
    public int latencySampleCount(OperationType type) { return latencySampleCounts[type.ordinal()]; }
    public double operationShare(OperationType type) {
        return totalOperations == 0 ? 0.0 : counts[type.ordinal()] / (double) totalOperations;
    }
    public double p50(OperationType type) { return p50Nanos[type.ordinal()]; }
    public double p95(OperationType type) { return p95Nanos[type.ordinal()]; }
    public double p99(OperationType type) { return p99Nanos[type.ordinal()]; }

    /** Lazily materialized compatibility views; controller code uses primitive accessors. */
    public Map<OperationType, Integer> counts() { return intMap(counts); }
    public Map<OperationType, Integer> latencySampleCounts() { return intMap(latencySampleCounts); }
    public Map<OperationType, Double> p50Nanos() { return doubleMap(p50Nanos); }
    public Map<OperationType, Double> p95Nanos() { return doubleMap(p95Nanos); }
    public Map<OperationType, Double> p99Nanos() { return doubleMap(p99Nanos); }

    private static int[] toIntArray(Map<OperationType, Integer> map) {
        int[] out = new int[TYPES.length];
        for (OperationType type : TYPES) out[type.ordinal()] = map.getOrDefault(type, 0);
        return out;
    }

    private static double[] toDoubleArray(Map<OperationType, Double> map) {
        double[] out = new double[TYPES.length];
        java.util.Arrays.fill(out, Double.NaN);
        for (OperationType type : TYPES) {
            Double value = map.get(type);
            if (value != null) out[type.ordinal()] = value;
        }
        return out;
    }

    private static Map<OperationType, Integer> intMap(int[] values) {
        EnumMap<OperationType, Integer> out = new EnumMap<>(OperationType.class);
        for (OperationType type : TYPES) out.put(type, values[type.ordinal()]);
        return Collections.unmodifiableMap(out);
    }

    private static Map<OperationType, Double> doubleMap(double[] values) {
        EnumMap<OperationType, Double> out = new EnumMap<>(OperationType.class);
        for (OperationType type : TYPES) {
            double value = values[type.ordinal()];
            if (Double.isFinite(value)) out.put(type, value);
        }
        return Collections.unmodifiableMap(out);
    }
}
