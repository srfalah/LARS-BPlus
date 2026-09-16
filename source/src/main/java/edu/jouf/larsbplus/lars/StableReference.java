package edu.jouf.larsbplus.lars;

import java.util.Arrays;

final class StableReference {
    private static final OperationType[] TYPES = OperationType.values();
    private final double[] p95 = new double[TYPES.length];
    private final double[] p99 = new double[TYPES.length];
    private double splitRate = Double.NaN;
    private double scanAmplification = Double.NaN;
    private double averageOccupancy = Double.NaN;
    private int acceptedWindows;

    StableReference() {
        Arrays.fill(p95, Double.NaN);
        Arrays.fill(p99, Double.NaN);
    }

    boolean ready(int requiredWindows) { return acceptedWindows >= requiredWindows; }
    int acceptedWindows() { return acceptedWindows; }

    void update(RegionWindowStats stats, double alpha, int minSamplesPerOperationType) {
        for (OperationType type : TYPES) {
            int ordinal = type.ordinal();
            int count = stats.latencySampleCount(type);
            double value95 = stats.p95(type);
            double value99 = stats.p99(type);
            if (count >= minSamplesPerOperationType && Double.isFinite(value95)) {
                p95[ordinal] = ewmaMaybe(p95[ordinal], value95, alpha);
            }
            if (count >= minSamplesPerOperationType && Double.isFinite(value99)) {
                p99[ordinal] = ewmaMaybe(p99[ordinal], value99, alpha);
            }
        }
        double writes = stats.count(OperationType.INSERT)
                + stats.count(OperationType.DELETE)
                + stats.count(OperationType.UPDATE);
        if (writes > 0) {
            double rate = stats.leafSplits() / writes;
            splitRate = ewmaMaybe(splitRate, rate, alpha);
        }
        if (Double.isFinite(stats.averageScanAmplification())) {
            scanAmplification = ewmaMaybe(scanAmplification, stats.averageScanAmplification(), alpha);
        }
        averageOccupancy = ewmaMaybe(averageOccupancy, stats.averageOccupancy(), alpha);
        acceptedWindows++;
    }

    double p95(OperationType type) { return p95[type.ordinal()]; }
    double p99(OperationType type) { return p99[type.ordinal()]; }
    double splitRate() { return splitRate; }
    double scanAmplification() { return scanAmplification; }
    double averageOccupancy() { return averageOccupancy; }

    private static double ewma(double oldV, double newV, double alpha) {
        return alpha * newV + (1.0 - alpha) * oldV;
    }

    private static double ewmaMaybe(double oldV, double newV, double alpha) {
        return Double.isFinite(oldV) ? ewma(oldV, newV, alpha) : newV;
    }
}
