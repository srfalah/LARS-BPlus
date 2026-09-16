package edu.jouf.larsbplus.experiment;

import edu.jouf.larsbplus.lars.OperationType;

import java.util.Objects;

/**
 * Operation profiles adapted from the official YCSB core workload files.
 * This enum describes workload mixes only; it does not claim full YCSB client compatibility.
 */
public enum YcsbWorkloadProfile {
    YCSB_A(0.50, 0.50, 0.00, 0.00, 0.00),
    YCSB_B(0.95, 0.05, 0.00, 0.00, 0.00),
    YCSB_E(0.00, 0.00, 0.05, 0.95, 0.00);

    private final double search;
    private final double update;
    private final double insert;
    private final double rangeSearch;
    private final double delete;

    YcsbWorkloadProfile(double search, double update, double insert,
                        double rangeSearch, double delete) {
        double total = search + update + insert + rangeSearch + delete;
        if (Math.abs(total - 1.0) > 1e-12) {
            throw new IllegalArgumentException("YCSB operation mix must sum to one: " + total);
        }
        this.search = search;
        this.update = update;
        this.insert = insert;
        this.rangeSearch = rangeSearch;
        this.delete = delete;
    }

    public static YcsbWorkloadProfile fromWorkloadName(WorkloadName workload) {
        Objects.requireNonNull(workload, "workload");
        return switch (workload) {
            case YCSB_A -> YCSB_A;
            case YCSB_B -> YCSB_B;
            case YCSB_E -> YCSB_E;
            default -> throw new IllegalArgumentException("not a YCSB-derived workload: " + workload);
        };
    }

    public WorkloadName workloadName() {
        return WorkloadName.valueOf(name());
    }

    public OperationType chooseOperation(double sample) {
        if (!(sample >= 0.0 && sample < 1.0)) {
            throw new IllegalArgumentException("sample must be in [0,1): " + sample);
        }
        double cumulative = search;
        if (sample < cumulative) return OperationType.SEARCH;
        cumulative += update;
        if (sample < cumulative) return OperationType.UPDATE;
        cumulative += insert;
        if (sample < cumulative) return OperationType.INSERT;
        cumulative += rangeSearch;
        if (sample < cumulative) return OperationType.RANGE_SEARCH;
        return OperationType.DELETE;
    }

    public double searchProportion() { return search; }
    public double updateProportion() { return update; }
    public double insertProportion() { return insert; }
    public double rangeSearchProportion() { return rangeSearch; }
    public double deleteProportion() { return delete; }
}
