package edu.jouf.larsbplus.experiment;

import edu.jouf.larsbplus.lars.Diagnosis;
import edu.jouf.larsbplus.lars.OperationType;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** Deterministic logical workload generator shared by every variant at the same seed. */
public final class WorkloadGenerator {
    public static final long KEY_STRIDE = 1L << 20;

    private WorkloadGenerator() {}

    public static List<ExperimentOperation> generate(WorkloadName workload,
                                                     int initialRecords,
                                                     int operations,
                                                     long seed) {
        if (workload.ycsbDerived()) {
            return YcsbDerivedWorkloadGenerator.generate(
                    YcsbWorkloadProfile.fromWorkloadName(workload), initialRecords, operations, seed);
        }
        if (workload == WorkloadName.INSERT_PRESSURE_CONTROL
                || workload == WorkloadName.SCAN_FRAGMENTATION_CONTROL) {
            throw new IllegalArgumentException("diagnostic ground-truth controls use DiagnosticExperimentRun");
        }
        SplittableRandom random = new SplittableRandom(seed);
        ArrayList<ExperimentOperation> out = new ArrayList<>(operations);
        int insertOrdinal = 1;
        for (int i = 0; i < operations; i++) {
            PhaseMix pm = phaseMix(workload, i, operations);
            OperationType type = chooseType(pm.mix, random.nextDouble());
            boolean hot = random.nextDouble() < pm.hotProbability;
            int baseIndex = chooseBaseIndex(initialRecords, pm.hotFraction, hot, random);
            long baseKey = baseIndex * KEY_STRIDE;
            long key;
            long toKey = baseKey;
            long value = mix64(seed ^ ((long) i << 17) ^ baseKey);

            switch (type) {
                case INSERT -> {
                    if (insertOrdinal >= KEY_STRIDE) {
                        throw new IllegalStateException("insert key-space exhausted; increase KEY_STRIDE");
                    }
                    key = baseKey + insertOrdinal++;
                }
                case RANGE_SEARCH -> {
                    key = baseKey;
                    int width = pm.scanWidthBaseKeys;
                    int upperIndex = Math.min(initialRecords - 1, baseIndex + width);
                    toKey = upperIndex * KEY_STRIDE + (KEY_STRIDE - 1);
                }
                default -> key = baseKey;
            }
            out.add(new ExperimentOperation(type, key, value, toKey, pm.phase,
                    workload == WorkloadName.STABLE_CONTROL ? Diagnosis.NO_CONFIDENT_DIAGNOSIS : null));
        }
        return List.copyOf(out);
    }

    public static List<edu.jouf.larsbplus.core.BPlusTree.Entry> initialEntries(int initialRecords) {
        ArrayList<edu.jouf.larsbplus.core.BPlusTree.Entry> entries = new ArrayList<>(initialRecords);
        for (int i = 0; i < initialRecords; i++) {
            long key = i * KEY_STRIDE;
            entries.add(new edu.jouf.larsbplus.core.BPlusTree.Entry(key, mix64(key)));
        }
        return List.copyOf(entries);
    }

    private static int chooseBaseIndex(int n, double hotFraction, boolean hot, SplittableRandom random) {
        if (!hot || hotFraction >= 1.0) return random.nextInt(n);
        int hotSize = Math.max(1, (int) Math.round(n * hotFraction));
        int start = Math.max(0, (n - hotSize) / 2);
        return start + random.nextInt(hotSize);
    }

    private static OperationType chooseType(Mix mix, double x) {
        double c = mix.search;
        if (x < c) return OperationType.SEARCH;
        c += mix.range;
        if (x < c) return OperationType.RANGE_SEARCH;
        c += mix.insert;
        if (x < c) return OperationType.INSERT;
        c += mix.update;
        if (x < c) return OperationType.UPDATE;
        return OperationType.DELETE;
    }

    private static PhaseMix phaseMix(WorkloadName workload, int i, int total) {
        double f = i / (double) Math.max(1, total);
        return switch (workload) {
            case READ_HEAVY -> new PhaseMix("READ_HEAVY", new Mix(.85, .05, .05, .03, .02), 0.0, 1.0, 16);
            case WRITE_HEAVY -> new PhaseMix("WRITE_HEAVY", new Mix(.25, .05, .45, .15, .10), 0.0, 1.0, 16);
            case MIXED -> new PhaseMix("MIXED", new Mix(.45, .10, .20, .15, .10), 0.0, 1.0, 24);
            case SKEW_80_20 -> new PhaseMix("SKEW_80_20", new Mix(.45, .10, .20, .15, .10), .80, .20, 24);
            case LOCAL_HOTSPOT -> new PhaseMix("LOCAL_HOTSPOT", new Mix(.25, .05, .45, .15, .10), .85, .02, 16);
            case STABLE_CONTROL -> new PhaseMix("STABLE", new Mix(.92, .08, 0, 0, 0), .50, .20, 16);
            case WORKLOAD_SHIFT -> {
                if (f < 1.0 / 3.0) yield new PhaseMix("SHIFT_READ", new Mix(.90, .05, .03, .02, 0), .20, .20, 16);
                if (f < 2.0 / 3.0) yield new PhaseMix("SHIFT_WRITE_HOT", new Mix(.20, .03, .55, .15, .07), .90, .02, 12);
                yield new PhaseMix("SHIFT_SCAN", new Mix(.50, .35, .07, .05, .03), .80, .20, 48);
            }
            case STRESS -> {
                if (f < .25) yield new PhaseMix("STRESS_INSERT", new Mix(.08, .02, .75, .10, .05), .90, .02, 8);
                if (f < .50) yield new PhaseMix("STRESS_READ", new Mix(.92, .03, .02, .02, .01), .85, .02, 12);
                if (f < .75) yield new PhaseMix("STRESS_SCAN", new Mix(.25, .60, .05, .05, .05), .85, .20, 64);
                yield new PhaseMix("STRESS_MIXED", new Mix(.35, .10, .30, .15, .10), .80, .05, 24);
            }
            case YCSB_A, YCSB_B, YCSB_E ->
                    throw new IllegalArgumentException("YCSB-derived workloads use YcsbDerivedWorkloadGenerator");
            default -> throw new IllegalArgumentException("unsupported standard workload: " + workload);
        };
    }

    private record Mix(double search, double range, double insert, double update, double delete) {
        Mix {
            double sum = search + range + insert + update + delete;
            if (Math.abs(sum - 1.0) > 1e-9) throw new IllegalArgumentException("mix must sum to 1.0: " + sum);
        }
    }

    private record PhaseMix(String phase, Mix mix, double hotProbability, double hotFraction, int scanWidthBaseKeys) {}

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdl;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53l;
        return z ^ (z >>> 33);
    }
}
