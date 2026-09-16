package edu.jouf.larsbplus.experiment;

import java.util.Arrays;

/** Exact primitive samples; a sorted snapshot is reused across percentile queries. */
final class PrimitiveLatencies {
    private long[] values = new long[64];
    private long[] sorted;
    private int size;
    private double sum;
    private long maximum;

    void add(long value) {
        if (size == values.length) values = Arrays.copyOf(values, values.length * 2);
        values[size++] = value;
        sum += value;
        maximum = Math.max(maximum, value);
        sorted = null;
    }
    int size() { return size; }
    double mean() { return size == 0 ? Double.NaN : sum / size; }
    long max() { return maximum; }
    double percentile(double q) {
        if (size == 0) return Double.NaN;
        if (sorted == null) {
            sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
        }
        return sorted[Math.max(0, Math.min(size - 1, (int) Math.ceil(q * size) - 1))];
    }
}
