package edu.jouf.larsbplus.lars;

import edu.jouf.larsbplus.core.BPlusTree;

import java.util.Arrays;

/**
 * Per-region observation accumulator using primitive reusable buffers.
 */
final class RegionWindowAccumulator {
    private static final OperationType[] TYPES = OperationType.values();

    private final long regionId;
    private final LongBuffer[] latencies = new LongBuffer[TYPES.length];
    private final int[] operationCounts = new int[TYPES.length];
    private final int[] mergePositions = new int[TYPES.length];
    private int operationCount;
    private double scanAmplificationSum;
    private int scanSamples;
    private long scanLeaves;
    private long scanResults;
    private int rawScanSamples;
    private long windowStartOperation = -1L;
    private long windowEndOperation = -1L;

    RegionWindowAccumulator(long regionId) {
        this.regionId = regionId;
        for (OperationType type : TYPES) latencies[type.ordinal()] = new LongBuffer(64);
    }

    void recordPoint(int ordinal, long latencyNanos) {
        recordPoint(ordinal, latencyNanos, -1L);
    }

    void recordPoint(int ordinal, long latencyNanos, long operationIndex) {
        noteOperationIndex(operationIndex);
        operationCounts[ordinal]++;
        if (latencyNanos >= 0) latencies[ordinal].add(latencyNanos);
        operationCount++;
    }

    void record(OperationType type, long latencyNanos, double scanAmplification) {
        recordPoint(type.ordinal(), latencyNanos);
        if (type == OperationType.RANGE_SEARCH && Double.isFinite(scanAmplification)) {
            scanAmplificationSum += scanAmplification;
            scanSamples++;
        }
    }

    /** Ratio-of-sums estimator for regional leaf work per useful result. */
    void recordRange(long latencyNanos, int leavesVisited, int usefulResults) {
        recordRange(latencyNanos, leavesVisited, usefulResults, -1L);
    }

    void recordRange(long latencyNanos, int leavesVisited, int usefulResults, long operationIndex) {
        recordPoint(OperationType.RANGE_SEARCH.ordinal(), latencyNanos, operationIndex);
        scanLeaves += leavesVisited;
        scanResults += usefulResults;
        rawScanSamples++;
    }

    void reset() {
        Arrays.fill(operationCounts, 0);
        for (LongBuffer buffer : latencies) buffer.clear();
        operationCount = scanSamples = rawScanSamples = 0;
        scanAmplificationSum = 0;
        scanLeaves = scanResults = 0;
        windowStartOperation = windowEndOperation = -1L;
    }

    int operationCount() { return operationCount; }

    private void noteOperationIndex(long operationIndex) {
        if (operationIndex < 0) return;
        if (windowStartOperation < 0) windowStartOperation = operationIndex;
        windowEndOperation = operationIndex;
    }

    RegionWindowStats finish(long windowSequence,
                             BPlusTree.RegionMetrics structural,
                             BPlusTree.StructuralCounters counters) {
        return finishStructural(windowSequence, structural.averageOccupancy(), structural.minimumOccupancy(),
                structural.leafCount(), structural.recordCount(), counters);
    }

    /** Compatibility overload for tests and verification probes. */
    RegionWindowStats finish(long windowSequence,
                             BPlusTree.RegionView structural,
                             BPlusTree.StructuralCounters counters) {
        return finishStructural(windowSequence, structural.averageOccupancy(), structural.minimumOccupancy(),
                structural.leafCount(), structural.recordCount(), counters);
    }

    private RegionWindowStats finishStructural(long windowSequence,
                                                double averageOccupancy,
                                                double minimumOccupancy,
                                                int leafCount,
                                                int recordCount,
                                                BPlusTree.StructuralCounters counters) {
        int[] sampleCounts = new int[TYPES.length];
        double[] p50 = new double[TYPES.length];
        double[] p95 = new double[TYPES.length];
        double[] p99 = new double[TYPES.length];
        Arrays.fill(p50, Double.NaN);
        Arrays.fill(p95, Double.NaN);
        Arrays.fill(p99, Double.NaN);

        int totalSamples = 0;
        for (OperationType type : TYPES) {
            int ordinal = type.ordinal();
            LongBuffer values = latencies[ordinal];
            values.sort();
            int n = values.size();
            sampleCounts[ordinal] = n;
            totalSamples += n;
            if (n > 0) {
                p50[ordinal] = values.percentile(0.50);
                p95[ordinal] = values.percentile(0.95);
                p99[ordinal] = values.percentile(0.99);
            }
        }

        double overallP99 = totalSamples == 0 ? Double.NaN : mergedPercentile(totalSamples, 0.99);
        double scanAmp = rawScanSamples > 0
                ? scanLeaves / (double) Math.max(1L, scanResults)
                : (scanSamples == 0 ? Double.NaN : scanAmplificationSum / scanSamples);
        long splits = counters == null ? 0 : counters.leafSplits();
        long merges = counters == null ? 0 : counters.leafMerges();

        return new RegionWindowStats(
                regionId,
                windowSequence,
                windowStartOperation,
                windowEndOperation,
                operationCount,
                operationCounts,
                sampleCounts,
                p50,
                p95,
                p99,
                overallP99,
                scanAmp,
                splits,
                merges,
                averageOccupancy,
                minimumOccupancy,
                leafCount,
                recordCount
        );
    }

    /** Exact percentile over the union of already-sorted per-type buffers, without allocation. */
    private double mergedPercentile(int totalSamples, double p) {
        Arrays.fill(mergePositions, 0);
        int target = Math.max(0, Math.min(totalSamples - 1, (int) Math.ceil(p * totalSamples) - 1));
        long value = 0;
        for (int rank = 0; rank <= target; rank++) {
            int selected = -1;
            long best = Long.MAX_VALUE;
            for (int i = 0; i < latencies.length; i++) {
                LongBuffer buffer = latencies[i];
                int pos = mergePositions[i];
                if (pos < buffer.size() && (selected < 0 || buffer.get(pos) < best)) {
                    selected = i;
                    best = buffer.get(pos);
                }
            }
            if (selected < 0) throw new IllegalStateException("sample merge underflow");
            value = best;
            mergePositions[selected]++;
        }
        return value;
    }

    private static final class LongBuffer {
        private long[] values;
        private int size;

        LongBuffer(int initialCapacity) {
            values = new long[Math.max(8, initialCapacity)];
        }

        void add(long value) {
            if (size == values.length) values = Arrays.copyOf(values, values.length << 1);
            values[size++] = value;
        }

        void clear() { size = 0; }
        int size() { return size; }
        long get(int index) { return values[index]; }
        void sort() { Arrays.sort(values, 0, size); }

        double percentile(double p) {
            if (size == 0) return Double.NaN;
            int index = (int) Math.ceil(p * size) - 1;
            index = Math.max(0, Math.min(size - 1, index));
            return values[index];
        }
    }
}
