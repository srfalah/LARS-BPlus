package edu.jouf.larsbplus.experiment;

public enum WorkloadName {
    READ_HEAVY,
    WRITE_HEAVY,
    MIXED,
    WORKLOAD_SHIFT,
    SKEW_80_20,
    LOCAL_HOTSPOT,
    STRESS,
    STABLE_CONTROL,
    INSERT_PRESSURE_CONTROL,
    SCAN_FRAGMENTATION_CONTROL,
    YCSB_A,
    YCSB_B,
    YCSB_E;

    public boolean diagnosticControl() {
        return this == STABLE_CONTROL || this == INSERT_PRESSURE_CONTROL || this == SCAN_FRAGMENTATION_CONTROL;
    }

    public boolean ycsbDerived() {
        return this == YCSB_A || this == YCSB_B || this == YCSB_E;
    }
}
