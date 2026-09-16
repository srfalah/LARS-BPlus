package edu.jouf.larsbplus.experiment;

import edu.jouf.larsbplus.core.BPlusTree;
import edu.jouf.larsbplus.lars.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Executes one workload/variant/seed run and writes raw CSV artifacts. */
public final class SingleExperimentRun {
    private SingleExperimentRun() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> cli = parseArgs(args);
        CampaignProfile profile = CampaignProfile.valueOf(required(cli, "profile"));
        WorkloadName workload = WorkloadName.valueOf(required(cli, "workload"));
        ExperimentVariant variant = ExperimentVariant.valueOf(required(cli, "variant"));
        int repetition = Integer.parseInt(required(cli, "repetition"));
        long seed = Long.parseLong(required(cli, "seed"));
        Path outDir = Path.of(required(cli, "out"));

        if (workload == WorkloadName.INSERT_PRESSURE_CONTROL
                || workload == WorkloadName.SCAN_FRAGMENTATION_CONTROL) {
            DiagnosticExperimentRun.run(profile, workload, repetition, seed, outDir);
            return;
        }
        runStandard(profile, workload, variant, repetition, seed, outDir);
    }

    static void runStandard(CampaignProfile profile,
                            WorkloadName workload,
                            ExperimentVariant variant,
                            int repetition,
                            long seed,
                            Path outDir) throws Exception {
        Files.createDirectories(outDir);
        List<BPlusTree.Entry> initial = WorkloadGenerator.initialEntries(profile.initialRecords());
        IndexAdapter index = createAdapter(variant, initial, profile);
        List<ExperimentOperation> operations = WorkloadGenerator.generate(
                workload, profile.initialRecords(), profile.measuredOperations(), seed);

        // Untimed warm-up on a disposable index and a prefix of the same workload family.
        warmUp(initial, operations, variant, profile);

        LatencyBook allLatency = new LatencyBook();
        ArrayList<EpochRow> epochs = new ArrayList<>();
        ArrayList<EventRow> events = new ArrayList<>();
        long peakHeap = usedHeap();
        RuntimeDiagnostics.Snapshot diagnosticStart = RuntimeDiagnostics.snapshot();
        long runStart = System.nanoTime();

        int epochStart = 0;
        String epochPhase = operations.isEmpty() ? "EMPTY" : operations.get(0).phase();
        LatencyBook epochLatency = new LatencyBook();
        long epochStartNanos = System.nanoTime();
        AdapterCounters epochCounterStart = index.counters();

        for (int i = 0; i < operations.size(); i++) {
            ExperimentOperation op = operations.get(i);
            long t0 = System.nanoTime();
            index.execute(op);
            long elapsed = System.nanoTime() - t0;
            allLatency.record(op.type(), elapsed);
            epochLatency.record(op.type(), elapsed);

            for (GenericEvent e : index.drainNewEvents()) {
                events.add(EventRow.from(profile, workload, variant, repetition, seed,
                        op.phase(), op.expectedDiagnosis(), e));
            }

            if ((i & 1023) == 0) peakHeap = Math.max(peakHeap, usedHeap());

            boolean runBoundary = i + 1 == operations.size();
            boolean phaseBoundary = !runBoundary
                    && !Objects.equals(op.phase(), operations.get(i + 1).phase());
            if (phaseBoundary || runBoundary) {
                index.endPostCommitObservationScope(phaseBoundary ? "PHASE_BOUNDARY" : "RUN_END");
                // Boundary resolution may emit the terminal ROLLBACK/KEEP event for a censored
                // trial. Drain it while the current phase/diagnosis label is still exact.
                for (GenericEvent e : index.drainNewEvents()) {
                    events.add(EventRow.from(profile, workload, variant, repetition, seed,
                            op.phase(), op.expectedDiagnosis(), e));
                }
            }

            boolean epochSizeReached = epochLatency.totalCount() >= profile.epochOperations();
            if (epochSizeReached || phaseBoundary || runBoundary) {
                long now = System.nanoTime();
                epochs.add(EpochRow.from(profile, workload, variant, repetition, seed,
                        epochs.size(), epochPhase, epochStart, i + 1,
                        now - epochStartNanos, epochLatency,
                        epochCounterStart, index.counters()));
                epochLatency = new LatencyBook();
                epochStart = i + 1;
                if (i + 1 < operations.size()) epochPhase = operations.get(i + 1).phase();
                epochStartNanos = System.nanoTime();
                epochCounterStart = index.counters();
            }
        }

        long elapsedNanos = System.nanoTime() - runStart;
        RuntimeDiagnostics runtimeDiagnostics = RuntimeDiagnostics.since(diagnosticStart);
        peakHeap = Math.max(peakHeap, usedHeap());
        index.validate();
        AdapterCounters counters = index.counters();
        BPlusTree tree = index.tree();
        double avgOccupancy = tree.leafCount() == 0 ? 0.0
                : tree.size() / (double) (tree.leafCount() * tree.maxLeafKeys());

        writeRunMetrics(outDir.resolve("run_metrics.csv"), profile, workload, variant,
                repetition, seed, allLatency, elapsedNanos, peakHeap, tree, avgOccupancy,
                counters, events, runtimeDiagnostics);
        writeEpochMetrics(outDir.resolve("epoch_metrics.csv"), epochs);
        writeEvents(outDir.resolve("adaptation_events.csv"), events);
        writePostCommitMetrics(outDir.resolve("post_commit_region_metrics.csv"), profile, workload,
                variant, repetition, seed, operations, index.postCommitObservations());
    }

    private static void warmUp(List<BPlusTree.Entry> initial,
                               List<ExperimentOperation> operations,
                               ExperimentVariant variant,
                               CampaignProfile profile) {
        int n = Math.min(profile.warmupOperations(), operations.size());
        if (n == 0) return;
        // Use a substantial disposable tree so JIT compilation sees the same traversal/control
        // shapes without warming the measured index or paying the full 1M-record publication cost.
        int warmInitial = Math.min(100_000, initial.size());
        List<BPlusTree.Entry> prefix = initial.subList(0, warmInitial);
        IndexAdapter warm = createAdapter(variant, prefix, profile);
        for (int i = 0; i < n; i++) {
            ExperimentOperation op = operations.get(i);
            long maxKey = Math.max(0, warmInitial - 1L) * WorkloadGenerator.KEY_STRIDE;
            long key = Math.floorMod(op.key(), maxKey + WorkloadGenerator.KEY_STRIDE);
            long to = Math.max(key, Math.min(maxKey + WorkloadGenerator.KEY_STRIDE - 1,
                    key + 8 * WorkloadGenerator.KEY_STRIDE));
            warm.execute(new ExperimentOperation(op.type(), key, op.value(), to, op.phase(), op.expectedDiagnosis()));
        }
    }

    private static IndexAdapter createAdapter(ExperimentVariant variant,
                                              List<BPlusTree.Entry> initial,
                                              CampaignProfile profile) {
        BPlusTree tree = BPlusTree.bulkLoadSorted(initial, 64, 64, 0.75);
        return switch (variant) {
            case STATIC_BPLUS -> new PlainAdapter(tree, false, false, profile);
            case PERIODIC_GLOBAL_REBUILD -> new PlainAdapter(tree, true, false, profile);
            case THRESHOLD_GLOBAL_REBUILD -> new PlainAdapter(tree, false, true, profile);
            case LOCAL_COST_ADAPTIVE -> new LarsAdapter(tree, LarsVariantPolicy.localCostAdaptive());
            case LARS_MONITOR_ONLY -> new LarsAdapter(tree, LarsVariantPolicy.full(), true);
            case LARS_FULL -> new LarsAdapter(tree, LarsVariantPolicy.full());
            case LARS_NO_DIAGNOSIS -> new LarsAdapter(tree, LarsVariantPolicy.noDiagnosis());
            case LARS_NO_LOCALITY -> new LarsAdapter(tree, LarsVariantPolicy.noLocality());
            case LARS_NO_RUNTIME_VALIDATION -> new LarsAdapter(tree, LarsVariantPolicy.noRuntimeValidation());
            case LARS_NO_ROLLBACK -> new LarsAdapter(tree, LarsVariantPolicy.noRollback());
        };
    }

    private interface IndexAdapter {
        void execute(ExperimentOperation op);
        BPlusTree tree();
        AdapterCounters counters();
        List<GenericEvent> drainNewEvents();
        List<PostCommitObservation> postCommitObservations();
        void endPostCommitObservationScope(String boundary);
        void validate();
    }

    private static final class LarsAdapter implements IndexAdapter {
        private final LarsBPlusIndex index;
        private int eventCursor;

        LarsAdapter(BPlusTree tree, LarsVariantPolicy policy) {
            this(tree, policy, false);
        }

        LarsAdapter(BPlusTree tree, LarsVariantPolicy policy, boolean monitorOnly) {
            this.index = new LarsBPlusIndex(tree, LarsConfig.pilotDefaults(), policy);
            if (monitorOnly) {
                this.index.controller().setControlMode(LarsController.ControlMode.MONITOR_ONLY);
            }
        }

        @Override public void execute(ExperimentOperation op) {
            switch (op.type()) {
                case SEARCH -> index.search(op.key());
                case INSERT -> index.insert(op.key(), op.value());
                case DELETE -> index.delete(op.key());
                case UPDATE -> index.update(op.key(), op.value());
                case RANGE_SEARCH -> index.rangeSearch(op.key(), op.toKey());
            }
        }

        @Override public BPlusTree tree() { return index.tree(); }

        @Override public AdapterCounters counters() {
            LarsBPlusIndex.RuntimeCounters rc = index.runtimeCounters();
            long maintenanceCount = 0, maintenanceNanos = 0, moved = 0, touched = 0;
            long commits = 0, rollbacks = 0, abstains = 0, noTrial = 0, kept = 0;
            for (int i = 0, n = index.adaptationEventCount(); i < n; i++) {
                AdaptationEvent e = index.adaptationEventAt(i);
                if (!"ABSTAIN".equals(e.outcome())) {
                    maintenanceCount++;
                    maintenanceNanos += e.actionNanos();
                    moved += e.recordsMoved();
                    touched += e.leavesTouched();
                }
                switch (e.outcome()) {
                    case "COMMIT" -> commits++;
                    case "ROLLBACK" -> rollbacks++;
                    case "ABSTAIN" -> abstains++;
                    case "COMMIT_NO_TRIAL" -> { commits++; noTrial++; }
                    case "KEEP_FAILED_TRIAL" -> kept++;
                    default -> { }
                }
            }
            return new AdapterCounters(rc.controllerNanos(), maintenanceCount, maintenanceNanos,
                    moved, touched, 0, commits, rollbacks, abstains, noTrial, kept,
                    rc.observationWindows(), rc.referenceReadyWindows(), rc.degradedWindows(),
                    rc.persistentTriggers(), rc.diagnosisAttempts(), rc.confidentDiagnoses(),
                    rc.candidateSelections(), rc.trialStarts());
        }

        @Override public List<GenericEvent> drainNewEvents() {
            int size = index.adaptationEventCount();
            if (eventCursor >= size) return List.of();
            ArrayList<GenericEvent> out = new ArrayList<>(size - eventCursor);
            for (int i = eventCursor; i < size; i++) out.add(GenericEvent.from(index.adaptationEventAt(i)));
            eventCursor = size;
            return out;
        }

        @Override public List<PostCommitObservation> postCommitObservations() {
            return index.postCommitObservations();
        }

        @Override public void endPostCommitObservationScope(String boundary) {
            index.endPostCommitObservationScope(boundary);
        }

        @Override public void validate() { index.validate(); }
    }

    private static final class PlainAdapter implements IndexAdapter {
        private BPlusTree tree;
        private final boolean periodic;
        private final boolean threshold;
        private final int periodicInterval;
        private final int thresholdInterval;
        private long operations;
        private long intervalInserts;
        private long intervalRanges;
        private long maintenanceCount;
        private long maintenanceNanos;
        private long recordsMoved;
        private long leavesTouched;
        private long fullRebuilds;
        private long eventSequence;
        private final ArrayList<GenericEvent> pendingEvents = new ArrayList<>();

        PlainAdapter(BPlusTree tree, boolean periodic, boolean threshold, CampaignProfile profile) {
            this.tree = tree;
            this.periodic = periodic;
            this.threshold = threshold;
            this.periodicInterval = Math.max(10_000, profile.measuredOperations() / 6);
            this.thresholdInterval = Math.max(5_000, profile.epochOperations());
        }

        @Override public void execute(ExperimentOperation op) {
            switch (op.type()) {
                case SEARCH -> tree.search(op.key());
                case INSERT -> { tree.insert(op.key(), op.value()); intervalInserts++; }
                case DELETE -> tree.delete(op.key());
                case UPDATE -> tree.update(op.key(), op.value());
                case RANGE_SEARCH -> { tree.rangeSearch(op.key(), op.toKey()); intervalRanges++; }
            }
            operations++;
            if (periodic && operations % periodicInterval == 0) rebuild("periodic interval");
            if (threshold && operations % thresholdInterval == 0) thresholdCheck();
        }

        private void thresholdCheck() {
            long splits = 0;
            for (BPlusTree.StructuralCounters c : tree.drainStructuralCounters().values()) splits += c.leafSplits();
            double splitRate = splits / (double) Math.max(1L, intervalInserts);
            double occupancy = tree.size() / (double) Math.max(1L, tree.leafCount() * tree.maxLeafKeys());
            double rangeShare = intervalRanges / (double) thresholdInterval;
            if (splitRate >= 0.015 || (rangeShare >= 0.20 && occupancy <= 0.58)) {
                rebuild("global threshold");
            }
            intervalInserts = 0;
            intervalRanges = 0;
        }

        private void rebuild(String reason) {
            List<BPlusTree.Entry> entries = tree.snapshotEntries();
            long oldLeaves = tree.leafCount();
            long t0 = System.nanoTime();
            BPlusTree rebuilt = BPlusTree.bulkLoadSorted(entries, tree.maxLeafKeys(), tree.maxInternalChildren(), 0.75);
            long nanos = System.nanoTime() - t0;
            tree = rebuilt;
            maintenanceCount++;
            maintenanceNanos += nanos;
            recordsMoved += entries.size();
            leavesTouched += oldLeaves + rebuilt.leafCount();
            fullRebuilds++;
            pendingEvents.add(GenericEvent.global(++eventSequence, operations, reason,
                    entries.size(), oldLeaves + rebuilt.leafCount(), nanos));
        }

        @Override public BPlusTree tree() { return tree; }
        @Override public AdapterCounters counters() {
            return new AdapterCounters(0, maintenanceCount, maintenanceNanos, recordsMoved,
                    leavesTouched, fullRebuilds, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0);
        }
        @Override public List<GenericEvent> drainNewEvents() {
            if (pendingEvents.isEmpty()) return List.of();
            List<GenericEvent> out = List.copyOf(pendingEvents);
            pendingEvents.clear();
            return out;
        }
        @Override public List<PostCommitObservation> postCommitObservations() { return List.of(); }
        @Override public void endPostCommitObservationScope(String boundary) { }
        @Override public void validate() { tree.validate(); }
    }

    record AdapterCounters(long controllerNanos,
                           long maintenanceCount,
                           long maintenanceNanos,
                           long recordsMoved,
                           long leavesTouched,
                           long fullRebuilds,
                           long commits,
                           long rollbacks,
                           long abstains,
                           long noTrialCommits,
                           long failedTrialsKept,
                           long observationWindows,
                           long referenceReadyWindows,
                           long degradedWindows,
                           long persistentTriggers,
                           long diagnosisAttempts,
                           long confidentDiagnoses,
                           long candidateSelections,
                           long trialStarts) {}

    record GenericEvent(long eventId, long regionId, long eventOperationIndex,
                        long actionOperationIndex, long trialStartOperation,
                        long trialEndOperation, long commitOperationIndex,
                        long triggerWindowSequence,
                        String diagnosis, String action, String outcome, String reason,
                        int fromLeaf, int toLeaf, long recordsMoved, long leavesTouched,
                        double movedRecordFraction, double touchedLeafFraction,
                        double regionExposureFraction,
                        double predictedBenefitNanos, double estimatedActionCostNanos,
                        double safetyAdjustedCostNanos, double predictedBreakEvenOperations,
                        long actionNanos, int trialOperations, int comparableTrialSamples,
                        double baselineObjectiveNanos, double candidateObjectiveNanos,
                        double baselineP99Nanos, double candidateP99Nanos,
                        String baselineOperationMix, String trialOperationMix, double rawGain,
                        double netGain, double operationMixDrift) {
        static GenericEvent from(AdaptationEvent e) {
            return new GenericEvent(e.sequence(), e.regionId(), e.eventOperationIndex(),
                    e.actionOperationIndex(), e.trialStartOperation(), e.trialEndOperation(),
                    e.commitOperationIndex(), e.triggerWindowSequence(),
                    e.diagnosis().name(), e.action() == null ? "" : e.action().name(),
                    e.outcome(), e.reason(), e.fromLeaf(), e.toLeaf(), e.recordsMoved(),
                    e.leavesTouched(), e.movedRecordFraction(), e.touchedLeafFraction(),
                    e.regionExposureFraction(),
                    e.predictedBenefitNanos(), e.estimatedActionCostNanos(), e.safetyAdjustedCostNanos(),
                    e.predictedBreakEvenOperations(), e.actionNanos(), e.trialOperations(),
                    e.comparableTrialSamples(), e.baselineObjectiveNanos(), e.candidateObjectiveNanos(),
                    e.baselineP99Nanos(), e.candidateP99Nanos(), e.baselineOperationMix(),
                    e.trialOperationMix(), e.rawGain(), e.netGain(), e.operationMixDrift());
        }
        static GenericEvent global(long eventId, long operationIndex, String reason,
                                   long moved, long touched, long nanos) {
            return new GenericEvent(eventId, -1L, operationIndex, operationIndex,
                    -1L, -1L, operationIndex, -1L,
                    "", "GLOBAL_REBUILD", "COMMIT", reason, -1, -1,
                    moved, touched, 1.0, 1.0,
                    1.0,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    nanos, 0, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    "", "", Double.NaN, Double.NaN, Double.NaN);
        }
    }

    private static final class LatencyBook {
        private final PrimitiveLatencies[] byType = new PrimitiveLatencies[OperationType.values().length];
        private final PrimitiveLatencies all = new PrimitiveLatencies();
        LatencyBook() { for (int i = 0; i < byType.length; i++) byType[i] = new PrimitiveLatencies(); }
        void record(OperationType t, long nanos) { byType[t.ordinal()].add(nanos); all.add(nanos); }
        int totalCount() { return all.size(); }
        double mean() { return all.mean(); }
        double p50() { return all.percentile(.50); }
        double p95() { return all.percentile(.95); }
        double p99() { return all.percentile(.99); }
        long max() { return all.max(); }
        double p95(OperationType t) { return byType[t.ordinal()].percentile(.95); }
    }

    record EpochRow(String profile, String workload, String variant, int repetition, long seed,
                    int epochIndex, String phase, int startOperation, int endOperation,
                    int operations, long elapsedNanos, double throughputOpsS,
                    double p50LatencyNs, double p95LatencyNs, double p99LatencyNs,
                    long maintenanceCount, long maintenanceNanos, long controllerNanos,
                    long recordsMoved, long leavesTouched, long fullRebuilds) {
        static EpochRow from(CampaignProfile profile, WorkloadName workload, ExperimentVariant variant,
                             int repetition, long seed, int epochIndex, String phase,
                             int start, int end, long elapsed, LatencyBook lat,
                             AdapterCounters before, AdapterCounters after) {
            int ops = lat.totalCount();
            return new EpochRow(profile.name(), workload.name(), variant.name(), repetition, seed,
                    epochIndex, phase, start, end, ops, elapsed,
                    ops * 1_000_000_000.0 / Math.max(1L, elapsed), lat.p50(), lat.p95(), lat.p99(),
                    after.maintenanceCount() - before.maintenanceCount(),
                    after.maintenanceNanos() - before.maintenanceNanos(),
                    after.controllerNanos() - before.controllerNanos(),
                    after.recordsMoved() - before.recordsMoved(),
                    after.leavesTouched() - before.leavesTouched(),
                    after.fullRebuilds() - before.fullRebuilds());
        }
    }

    record EventRow(String profile, String workload, String variant, int repetition, long seed,
                    String phase, String expectedDiagnosis,
                    long eventId, long regionId, long eventOperationIndex,
                    long actionOperationIndex, long trialStartOperation,
                    long trialEndOperation, long commitOperationIndex,
                    long triggerWindowSequence,
                    String diagnosis, String action,
                    String outcome, String reason, int fromLeaf, int toLeaf,
                    long recordsMoved, long leavesTouched, double movedRecordFraction,
                    double touchedLeafFraction, double regionExposureFraction,
                    double predictedBenefitNanos,
                    double estimatedActionCostNanos, double safetyAdjustedCostNanos,
                    double predictedBreakEvenOperations, long actionNanos, int trialOperations,
                    int comparableTrialSamples, double baselineObjectiveNanos,
                    double candidateObjectiveNanos, double baselineP99Nanos,
                    double candidateP99Nanos, String baselineOperationMix,
                    String trialOperationMix,
                    double rawGain, double netGain, double operationMixDrift) {
        static EventRow from(CampaignProfile profile, WorkloadName workload, ExperimentVariant variant,
                             int repetition, long seed, String phase, Diagnosis expected, GenericEvent e) {
            return new EventRow(profile.name(), workload.name(), variant.name(), repetition, seed,
                    phase, expected == null ? "" : expected.name(),
                    e.eventId(), e.regionId(), e.eventOperationIndex(), e.actionOperationIndex(),
                    e.trialStartOperation(), e.trialEndOperation(), e.commitOperationIndex(),
                    e.triggerWindowSequence(), e.diagnosis(), e.action(),
                    e.outcome(), e.reason(), e.fromLeaf(), e.toLeaf(), e.recordsMoved(), e.leavesTouched(),
                    e.movedRecordFraction(), e.touchedLeafFraction(), e.regionExposureFraction(),
                    e.predictedBenefitNanos(),
                    e.estimatedActionCostNanos(), e.safetyAdjustedCostNanos(),
                    e.predictedBreakEvenOperations(), e.actionNanos(), e.trialOperations(),
                    e.comparableTrialSamples(), e.baselineObjectiveNanos(), e.candidateObjectiveNanos(),
                    e.baselineP99Nanos(), e.candidateP99Nanos(), e.baselineOperationMix(),
                    e.trialOperationMix(), e.rawGain(), e.netGain(), e.operationMixDrift());
        }
    }

    private static void writeRunMetrics(Path path, CampaignProfile profile, WorkloadName workload,
                                        ExperimentVariant variant, int repetition, long seed,
                                        LatencyBook lat, long elapsedNanos, long peakHeap,
                                        BPlusTree tree, double avgOccupancy, AdapterCounters c,
                                        List<EventRow> events,
                                        RuntimeDiagnostics diagnostics) throws IOException {
        long structuralAttempts = events.stream().filter(e -> e.outcome().equals("COMMIT")
                || e.outcome().equals("ROLLBACK") || e.outcome().equals("COMMIT_NO_TRIAL")
                || e.outcome().equals("KEEP_FAILED_TRIAL")).count();
        long diagnosisMatches = events.stream().filter(e -> !e.expectedDiagnosis().isBlank()
                && !e.expectedDiagnosis().equals(Diagnosis.NO_CONFIDENT_DIAGNOSIS.name())
                && e.expectedDiagnosis().equals(e.diagnosis())).count();
        long diagnosisMismatches = events.stream().filter(e -> !e.expectedDiagnosis().isBlank()
                && !e.expectedDiagnosis().equals(Diagnosis.NO_CONFIDENT_DIAGNOSIS.name())
                && !e.diagnosis().isBlank() && !e.expectedDiagnosis().equals(e.diagnosis())).count();
        double throughput = lat.totalCount() * 1_000_000_000.0 / Math.max(1L, elapsedNanos);
        double overheadPct = c.controllerNanos() * 100.0 / Math.max(1L, elapsedNanos);

        try (BufferedWriter out = CsvUtil.writer(path)) {
            CsvUtil.row(out, "profile","workload","variant","repetition","seed","initial_records","operations",
                    "elapsed_nanos","throughput_ops_s","mean_latency_ns","p50_latency_ns","p95_latency_ns",
                    "p99_latency_ns","max_latency_ns","search_p95_ns","insert_p95_ns","delete_p95_ns",
                    "update_p95_ns","range_p95_ns","final_records","leaf_count","avg_occupancy","peak_heap_bytes",
                    "process_cpu_nanos","current_thread_cpu_nanos","current_thread_allocated_bytes",
                    "gc_collections","gc_time_millis","jit_compilation_millis",
                    "controller_nanos","controller_time_share_pct","maintenance_count","maintenance_nanos",
                    "records_moved","leaves_touched","full_rebuilds","commit_count","rollback_count","abstain_count",
                    "no_trial_commits","failed_trials_kept","structural_attempts","expected_diagnosis_matches",
                    "expected_diagnosis_mismatches","observation_windows","reference_ready_windows",
                    "degraded_windows","persistent_triggers","diagnosis_attempts","confident_diagnoses",
                    "candidate_selections","trial_starts");
            CsvUtil.row(out, profile, workload, variant, repetition, seed, profile.initialRecords(), lat.totalCount(),
                    elapsedNanos, throughput, lat.mean(), lat.p50(), lat.p95(), lat.p99(), lat.max(),
                    lat.p95(OperationType.SEARCH), lat.p95(OperationType.INSERT), lat.p95(OperationType.DELETE),
                    lat.p95(OperationType.UPDATE), lat.p95(OperationType.RANGE_SEARCH), tree.size(), tree.leafCount(),
                    avgOccupancy, peakHeap, diagnostics.processCpuNanos(), diagnostics.currentThreadCpuNanos(),
                    diagnostics.currentThreadAllocatedBytes(), diagnostics.gcCollections(), diagnostics.gcTimeMillis(),
                    diagnostics.jitCompilationMillis(), c.controllerNanos(), overheadPct,
                    c.maintenanceCount(), c.maintenanceNanos(),
                    c.recordsMoved(), c.leavesTouched(), c.fullRebuilds(), c.commits(), c.rollbacks(), c.abstains(),
                    c.noTrialCommits(), c.failedTrialsKept(), structuralAttempts, diagnosisMatches, diagnosisMismatches,
                    c.observationWindows(), c.referenceReadyWindows(), c.degradedWindows(), c.persistentTriggers(),
                    c.diagnosisAttempts(), c.confidentDiagnoses(), c.candidateSelections(), c.trialStarts());
        }
    }

    private static void writeEpochMetrics(Path path, List<EpochRow> rows) throws IOException {
        try (BufferedWriter out = CsvUtil.writer(path)) {
            CsvUtil.row(out, "profile","workload","variant","repetition","seed","epoch_index","phase",
                    "start_operation","end_operation","operations","elapsed_nanos","throughput_ops_s",
                    "p50_latency_ns","p95_latency_ns","p99_latency_ns","maintenance_count","maintenance_nanos",
                    "controller_nanos","records_moved","leaves_touched","full_rebuilds");
            for (EpochRow r : rows) CsvUtil.row(out, r.profile(), r.workload(), r.variant(), r.repetition(), r.seed(),
                    r.epochIndex(), r.phase(), r.startOperation(), r.endOperation(), r.operations(), r.elapsedNanos(),
                    r.throughputOpsS(), r.p50LatencyNs(), r.p95LatencyNs(), r.p99LatencyNs(), r.maintenanceCount(),
                    r.maintenanceNanos(), r.controllerNanos(), r.recordsMoved(), r.leavesTouched(), r.fullRebuilds());
        }
    }

    private static void writeEvents(Path path, List<EventRow> rows) throws IOException {
        try (BufferedWriter out = CsvUtil.writer(path)) {
            CsvUtil.row(out, "profile","workload","variant","repetition","seed","phase","expected_diagnosis",
                    "event_id","region_id","event_operation_index","action_operation_index",
                    "action_start_operation","action_end_operation",
                    "trial_start_operation","trial_end_operation","commit_operation_index",
                    "trigger_window_sequence","diagnosis","action","outcome","reason","from_leaf","to_leaf",
                    "records_moved","leaves_touched","moved_record_fraction","touched_leaf_fraction",
                    "region_exposure_fraction",
                    "predicted_benefit_nanos","estimated_action_cost_nanos","safety_adjusted_cost_nanos",
                    "predicted_break_even_operations","action_nanos","trial_operations",
                    "comparable_trial_samples","baseline_objective_nanos","candidate_objective_nanos",
                    "baseline_p99_nanos","candidate_p99_nanos","baseline_operation_mix",
                    "trial_operation_mix","raw_gain","net_gain","operation_mix_drift");
            for (EventRow r : rows) CsvUtil.row(out, r.profile(), r.workload(), r.variant(), r.repetition(), r.seed(),
                    r.phase(), r.expectedDiagnosis(), r.eventId(), r.regionId(), r.eventOperationIndex(),
                    r.actionOperationIndex(), r.actionOperationIndex(), r.actionOperationIndex(),
                    r.trialStartOperation(), r.trialEndOperation(),
                    r.commitOperationIndex(), r.triggerWindowSequence(), r.diagnosis(), r.action(), r.outcome(),
                    r.reason(), r.fromLeaf(), r.toLeaf(), r.recordsMoved(), r.leavesTouched(),
                    r.movedRecordFraction(), r.touchedLeafFraction(), r.regionExposureFraction(),
                    r.predictedBenefitNanos(),
                    r.estimatedActionCostNanos(), r.safetyAdjustedCostNanos(),
                    r.predictedBreakEvenOperations(), r.actionNanos(), r.trialOperations(),
                    r.comparableTrialSamples(), r.baselineObjectiveNanos(), r.candidateObjectiveNanos(),
                    r.baselineP99Nanos(), r.candidateP99Nanos(), r.baselineOperationMix(),
                    r.trialOperationMix(), r.rawGain(), r.netGain(), r.operationMixDrift());
        }
    }

    private static void writePostCommitMetrics(Path path,
                                               CampaignProfile profile,
                                               WorkloadName workload,
                                               ExperimentVariant variant,
                                               int repetition,
                                               long seed,
                                               List<ExperimentOperation> operations,
                                               List<PostCommitObservation> rows) throws IOException {
        try (BufferedWriter out = CsvUtil.writer(path)) {
            CsvUtil.row(out, "profile","workload","variant","repetition","seed","phase",
                    "event_id","region_id","commit_operation_index","observation_index_after_commit",
                    "region_window_sequence","window_start_operation","window_end_operation","boundary",
                    "operations","operation_mix","latency_sample_mix","search_p95_ns","insert_p99_ns",
                    "delete_p95_ns","update_p95_ns","range_p95_ns","overall_p99_ns",
                    "normalized_service_cost","average_scan_amplification","leaf_splits","leaf_merges",
                    "average_occupancy","minimum_occupancy","leaf_count","record_count");
            for (PostCommitObservation r : rows) {
                CsvUtil.row(out, profile, workload, variant, repetition, seed,
                        phaseAt(operations, r.windowEndOperation()), r.eventId(), r.regionId(),
                        r.commitOperationIndex(), r.observationIndexAfterCommit(), r.regionWindowSequence(),
                        r.windowStartOperation(), r.windowEndOperation(), r.boundary(), r.totalOperations(),
                        r.operationMix(), r.latencySampleMix(), r.searchP95Nanos(), r.insertP99Nanos(),
                        r.deleteP95Nanos(), r.updateP95Nanos(), r.rangeP95Nanos(), r.overallP99Nanos(),
                        r.normalizedServiceCost(), r.averageScanAmplification(), r.leafSplits(), r.leafMerges(),
                        r.averageOccupancy(), r.minimumOccupancy(), r.leafCount(), r.recordCount());
            }
        }
    }

    private static String phaseAt(List<ExperimentOperation> operations, long oneBasedOperationIndex) {
        if (operations.isEmpty() || oneBasedOperationIndex <= 0) return "";
        int index = (int) Math.min(operations.size() - 1L, oneBasedOperationIndex - 1L);
        return operations.get(index).phase();
    }

    private static long usedHeap() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    private static Map<String, String> parseArgs(String[] args) {
        HashMap<String, String> m = new HashMap<>();
        for (String a : args) {
            int eq = a.indexOf('=');
            if (!a.startsWith("--") || eq < 3) throw new IllegalArgumentException("expected --key=value: " + a);
            m.put(a.substring(2, eq), a.substring(eq + 1));
        }
        return m;
    }

    private static String required(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("missing --" + key + "=...");
        return v;
    }
}
