package edu.jouf.larsbplus.experiment;

import edu.jouf.larsbplus.core.BPlusTree;
import edu.jouf.larsbplus.lars.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Actual-operation diagnostic controls used for labeled cause-detection evaluation. */
final class DiagnosticExperimentRun {
    private DiagnosticExperimentRun() {}

    static void run(CampaignProfile profile,
                    WorkloadName workload,
                    int repetition,
                    long seed,
                    Path outDir) throws Exception {
        if (workload != WorkloadName.INSERT_PRESSURE_CONTROL
                && workload != WorkloadName.SCAN_FRAGMENTATION_CONTROL) {
            throw new IllegalArgumentException("unsupported diagnostic workload " + workload);
        }
        Files.createDirectories(outDir);
        int initialRecords = profile == CampaignProfile.SMOKE ? 4_000 : 10_000;
        // Disposable repetitions let the same hot methods reach optimized compilation before
        // the measured reference is learned. No operations touch the measured tree.
        for (int warmRound = 0; warmRound < 10; warmRound++) warmDiagnosticJit(workload);
        BPlusTree tree = BPlusTree.bulkLoadSorted(WorkloadGenerator.initialEntries(initialRecords), 64, 64, .75);
        LarsBPlusIndex index = new LarsBPlusIndex(tree, diagnosticConfig(), LarsVariantPolicy.full());
        long regionId = selectRegion(tree, workload == WorkloadName.INSERT_PRESSURE_CONTROL);
        BPlusTree.RegionView region = tree.region(regionId).orElseThrow();
        BPlusTree.ActionEstimate scanFragmentation = null;
        long diagnosticScanFrom = Long.MIN_VALUE;
        long diagnosticScanTo = Long.MIN_VALUE;
        if (workload == WorkloadName.SCAN_FRAGMENTATION_CONTROL) {
            scanFragmentation = chooseLocalizedSlacken(tree, regionId);
            BPlusTree.LeafView first = region.leaves().get(scanFragmentation.fromLeafInclusive());
            BPlusTree.LeafView last = region.leaves().get(scanFragmentation.toLeafInclusive());
            diagnosticScanFrom = first.firstKey();
            diagnosticScanTo = last.lastKey();
        }

        ArrayList<Long> latencies = new ArrayList<>();
        ArrayList<DiagEvent> events = new ArrayList<>();
        int eventCursor = 0;
        long peakHeap = usedHeap();
        RuntimeDiagnostics.Snapshot diagnosticStart = RuntimeDiagnostics.snapshot();
        long start = System.nanoTime();

        index.beginReferenceCalibration();
        int referenceOps = diagnosticConfig().observationOperationsPerRegion()
                * diagnosticConfig().referenceWarmupWindows();
        if (workload == WorkloadName.INSERT_PRESSURE_CONTROL) {
            List<BPlusTree.LeafView> leaves = region.leaves();
            for (int i = 0; i < referenceOps; i++) {
                BPlusTree.LeafView leaf = leaves.get(i % leaves.size());
                long key = leaf.firstKey() + 1 + (i / leaves.size());
                long t0 = System.nanoTime();
                index.insert(key, seed ^ i);
                latencies.add(System.nanoTime() - t0);
            }
        } else {
            for (int i = 0; i < referenceOps; i++) {
                long t0 = System.nanoTime();
                index.rangeSearch(diagnosticScanFrom, diagnosticScanTo);
                latencies.add(System.nanoTime() - t0);
            }
        }
        require(index.controller().referenceReady(regionId), "diagnostic reference not ready");
        require(index.controller().referenceStructurallyStable(regionId), "diagnostic reference structurally contaminated");
        index.beginGroundTruthCalibration(regionId);

        if (workload == WorkloadName.SCAN_FRAGMENTATION_CONTROL) {
            tree.applyAction(scanFragmentation);
            tree.validate();
        }

        int groundTruthOps = profile == CampaignProfile.SMOKE ? 1_000 : 2_000;
        Diagnosis expected = workload == WorkloadName.INSERT_PRESSURE_CONTROL
                ? Diagnosis.INSERT_SPLIT_PRESSURE : Diagnosis.SCAN_FRAGMENTATION;
        region = tree.region(regionId).orElseThrow();
        long hotBase = region.leaves().get(region.leafCount() / 2).firstKey();
        long scanFrom = workload == WorkloadName.SCAN_FRAGMENTATION_CONTROL ? diagnosticScanFrom : region.firstKey();
        long scanTo = workload == WorkloadName.SCAN_FRAGMENTATION_CONTROL ? diagnosticScanTo : region.lastKey();

        for (int i = 0; i < groundTruthOps; i++) {
            long t0 = System.nanoTime();
            if (workload == WorkloadName.INSERT_PRESSURE_CONTROL) {
                index.insert(hotBase + 10_000 + i, seed + i);
            } else {
                index.rangeSearch(scanFrom, scanTo);
            }
            latencies.add(System.nanoTime() - t0);
            for (int eventCount = index.adaptationEventCount(); eventCursor < eventCount; eventCursor++) {
                AdaptationEvent e = index.adaptationEventAt(eventCursor);
                events.add(new DiagEvent("GROUND_TRUTH", expected, e));
            }
            if ((i & 511) == 0) peakHeap = Math.max(peakHeap, usedHeap());
        }

        // If a live trial is still active, feed bounded same-region reads to complete it.
        for (int i = 0; i < diagnosticConfig().maxTrialOperations() * 2 && index.controller().hasActiveTrial(); i++) {
            long t0 = System.nanoTime();
            if (workload == WorkloadName.INSERT_PRESSURE_CONTROL) index.search(hotBase);
            else index.rangeSearch(scanFrom, scanTo);
            latencies.add(System.nanoTime() - t0);
            for (int eventCount = index.adaptationEventCount(); eventCursor < eventCount; eventCursor++) {
                events.add(new DiagEvent("GROUND_TRUTH", expected, index.adaptationEventAt(eventCursor)));
            }
        }

        index.endPostCommitObservationScope("RUN_END");
        for (int eventCount = index.adaptationEventCount(); eventCursor < eventCount; eventCursor++) {
            events.add(new DiagEvent("GROUND_TRUTH", expected, index.adaptationEventAt(eventCursor)));
        }
        long elapsed = System.nanoTime() - start;
        RuntimeDiagnostics runtimeDiagnostics = RuntimeDiagnostics.since(diagnosticStart);
        peakHeap = Math.max(peakHeap, usedHeap());
        index.validate();
        write(profile, workload, repetition, seed, initialRecords, elapsed, peakHeap,
                index, latencies, events, outDir, runtimeDiagnostics);
    }

    private static void warmDiagnosticJit(WorkloadName workload) {
        BPlusTree warmTree = BPlusTree.bulkLoadSorted(WorkloadGenerator.initialEntries(4_000), 64, 64, .75);
        LarsBPlusIndex warm = new LarsBPlusIndex(warmTree, diagnosticConfig(), LarsVariantPolicy.full());
        BPlusTree.RegionView r = warmTree.regions().stream().filter(v -> v.leafCount() >= 8).findFirst().orElseThrow();
        if (workload == WorkloadName.INSERT_PRESSURE_CONTROL) {
            List<BPlusTree.LeafView> leaves = r.leaves();
            for (int i = 0; i < 3_000; i++) {
                BPlusTree.LeafView leaf = leaves.get(i % leaves.size());
                long key = leaf.firstKey() + 50_000 + i;
                warm.insert(key, i);
            }
        } else {
            long from = r.firstKey();
            long to = r.lastKey();
            for (int i = 0; i < 2_000; i++) warm.rangeSearch(from, to);
        }
    }

    private static LarsConfig diagnosticConfig() {
        LarsConfig d = LarsConfig.pilotDefaults();
        return new LarsConfig(
                128, 2, 3, 20, 1, .20,
                .12, .15, .03,
                2, 6, .05, .05,
                20_000, d.estimatedRecordMoveNanos(), d.estimatedLeafTouchNanos(), 1.10,
                128, 384, .03, .15, .30,
                2, .04,
                d.insertLatencyWeight(), d.splitRateWeight(), d.headroomWeight(),
                d.scanLatencyWeight(), d.scanAmplificationWeight(), d.sparsityWeight());
    }

    private static long selectRegion(BPlusTree tree, boolean needSlacken) {
        for (BPlusTree.RegionView r : tree.regions()) {
            if (r.leafCount() < 8) continue;
            if (!needSlacken) return r.regionId();
            if (!r.canAddLeafWithoutParentSplit()) continue;
            for (int width = 2; width <= Math.min(6, r.leafCount()); width++) {
                for (int from = 0; from + width <= r.leafCount(); from++) {
                    if (tree.estimateAction(BPlusTree.LocalAction.SLACKEN, r.regionId(), from, from + width - 1).feasible()) {
                        return r.regionId();
                    }
                }
            }
        }
        throw new IllegalStateException("no suitable diagnostic region");
    }

    private static BPlusTree.ActionEstimate chooseLocalizedSlacken(BPlusTree tree, long regionId) {
        BPlusTree.RegionView r = tree.region(regionId).orElseThrow();
        for (int width = 2; width <= Math.min(4, r.leafCount()); width++) {
            for (int from = 0; from + width <= r.leafCount(); from++) {
                BPlusTree.ActionEstimate e = tree.estimateAction(BPlusTree.LocalAction.SLACKEN,
                        regionId, from, from + width - 1);
                if (e.feasible()) return e;
            }
        }
        throw new IllegalStateException("unable to inject localized legal scan fragmentation");
    }

    private static void write(CampaignProfile profile, WorkloadName workload, int repetition, long seed,
                              int initialRecords, long elapsed, long peakHeap, LarsBPlusIndex index,
                              List<Long> latencies, List<DiagEvent> events, Path outDir,
                              RuntimeDiagnostics diagnostics) throws IOException {
        LarsBPlusIndex.RuntimeCounters rc = index.runtimeCounters();
        long maintenanceCount = 0, maintenanceNanos = 0, moved = 0, touched = 0;
        long commits = 0, rollbacks = 0, abstains = 0;
        for (int i = 0, n = index.adaptationEventCount(); i < n; i++) {
            AdaptationEvent e = index.adaptationEventAt(i);
            if (!e.outcome().equals("ABSTAIN")) {
                maintenanceCount++; maintenanceNanos += e.actionNanos(); moved += e.recordsMoved(); touched += e.leavesTouched();
            }
            if (e.outcome().equals("COMMIT")) commits++;
            else if (e.outcome().equals("ROLLBACK")) rollbacks++;
            else if (e.outcome().equals("ABSTAIN")) abstains++;
        }
        long matches = events.stream().anyMatch(e -> e.expected == e.event.diagnosis()) ? 1L : 0L;
        long mismatches = events.stream().anyMatch(e ->
                e.event.diagnosis() != Diagnosis.NO_CONFIDENT_DIAGNOSIS
                        && e.expected != e.event.diagnosis()) ? 1L : 0L;
        BPlusTree tree = index.tree();
        double occupancy = tree.size() / (double) Math.max(1L, tree.leafCount() * tree.maxLeafKeys());
        double throughput = latencies.size() * 1_000_000_000.0 / Math.max(1L, elapsed);

        try (BufferedWriter out = CsvUtil.writer(outDir.resolve("run_metrics.csv"))) {
            CsvUtil.row(out, "profile","workload","variant","repetition","seed","initial_records","operations",
                    "elapsed_nanos","throughput_ops_s","mean_latency_ns","p50_latency_ns","p95_latency_ns","p99_latency_ns",
                    "max_latency_ns","search_p95_ns","insert_p95_ns","delete_p95_ns","update_p95_ns","range_p95_ns",
                    "final_records","leaf_count","avg_occupancy","peak_heap_bytes",
                    "process_cpu_nanos","current_thread_cpu_nanos","current_thread_allocated_bytes",
                    "gc_collections","gc_time_millis","jit_compilation_millis",
                    "controller_nanos","controller_time_share_pct",
                    "maintenance_count","maintenance_nanos","records_moved","leaves_touched","full_rebuilds","commit_count",
                    "rollback_count","abstain_count","no_trial_commits","failed_trials_kept","structural_attempts",
                    "expected_diagnosis_matches","expected_diagnosis_mismatches","observation_windows",
                    "reference_ready_windows","degraded_windows","persistent_triggers","diagnosis_attempts",
                    "confident_diagnoses","candidate_selections","trial_starts");
            long max = 0; for (long v : latencies) max = Math.max(max, v);
            CsvUtil.row(out, profile, workload, ExperimentVariant.LARS_FULL, repetition, seed, initialRecords, latencies.size(),
                    elapsed, throughput, CsvUtil.mean(latencies), CsvUtil.percentile(latencies,.50), CsvUtil.percentile(latencies,.95),
                    CsvUtil.percentile(latencies,.99), max, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    tree.size(), tree.leafCount(), occupancy, peakHeap,
                    diagnostics.processCpuNanos(), diagnostics.currentThreadCpuNanos(),
                    diagnostics.currentThreadAllocatedBytes(), diagnostics.gcCollections(),
                    diagnostics.gcTimeMillis(), diagnostics.jitCompilationMillis(), rc.controllerNanos(),
                    rc.controllerNanos()*100.0/Math.max(1L,elapsed), maintenanceCount, maintenanceNanos, moved, touched, 0,
                    commits, rollbacks, abstains, 0, 0, commits+rollbacks, matches, mismatches,
                    rc.observationWindows(), rc.referenceReadyWindows(), rc.degradedWindows(),
                    rc.persistentTriggers(), rc.diagnosisAttempts(), rc.confidentDiagnoses(),
                    rc.candidateSelections(), rc.trialStarts());
        }

        try (BufferedWriter out = CsvUtil.writer(outDir.resolve("epoch_metrics.csv"))) {
            CsvUtil.row(out, "profile","workload","variant","repetition","seed","epoch_index","phase","start_operation",
                    "end_operation","operations","elapsed_nanos","throughput_ops_s","p50_latency_ns","p95_latency_ns",
                    "p99_latency_ns","maintenance_count","maintenance_nanos","controller_nanos","records_moved","leaves_touched","full_rebuilds");
            CsvUtil.row(out, profile, workload, ExperimentVariant.LARS_FULL, repetition, seed, 0, "DIAGNOSTIC_CONTROL", 0,
                    latencies.size(), latencies.size(), elapsed, throughput, CsvUtil.percentile(latencies,.50),
                    CsvUtil.percentile(latencies,.95), CsvUtil.percentile(latencies,.99), maintenanceCount, maintenanceNanos,
                    rc.controllerNanos(), moved, touched, 0);
        }

        try (BufferedWriter out = CsvUtil.writer(outDir.resolve("adaptation_events.csv"))) {
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
            for (DiagEvent d : events) {
                AdaptationEvent e = d.event;
                CsvUtil.row(out, profile, workload, ExperimentVariant.LARS_FULL, repetition, seed, d.phase, d.expected,
                        e.sequence(), e.regionId(), e.eventOperationIndex(), e.actionOperationIndex(),
                        e.actionOperationIndex(), e.actionOperationIndex(),
                        e.trialStartOperation(), e.trialEndOperation(), e.commitOperationIndex(),
                        e.triggerWindowSequence(), e.diagnosis(), e.action(), e.outcome(), e.reason(), e.fromLeaf(), e.toLeaf(),
                        e.recordsMoved(), e.leavesTouched(), e.movedRecordFraction(), e.touchedLeafFraction(),
                        e.regionExposureFraction(),
                        e.predictedBenefitNanos(), e.estimatedActionCostNanos(), e.safetyAdjustedCostNanos(),
                        e.predictedBreakEvenOperations(), e.actionNanos(), e.trialOperations(),
                        e.comparableTrialSamples(), e.baselineObjectiveNanos(), e.candidateObjectiveNanos(),
                        e.baselineP99Nanos(), e.candidateP99Nanos(), e.baselineOperationMix(),
                        e.trialOperationMix(), e.rawGain(), e.netGain(), e.operationMixDrift());
            }
        }

        try (BufferedWriter out = CsvUtil.writer(outDir.resolve("post_commit_region_metrics.csv"))) {
            CsvUtil.row(out, "profile","workload","variant","repetition","seed","phase",
                    "event_id","region_id","commit_operation_index","observation_index_after_commit",
                    "region_window_sequence","window_start_operation","window_end_operation","boundary",
                    "operations","operation_mix","latency_sample_mix","search_p95_ns","insert_p99_ns",
                    "delete_p95_ns","update_p95_ns","range_p95_ns","overall_p99_ns",
                    "normalized_service_cost","average_scan_amplification","leaf_splits","leaf_merges",
                    "average_occupancy","minimum_occupancy","leaf_count","record_count");
            for (PostCommitObservation r : index.postCommitObservations()) {
                CsvUtil.row(out, profile, workload, ExperimentVariant.LARS_FULL, repetition, seed,
                        "GROUND_TRUTH", r.eventId(), r.regionId(), r.commitOperationIndex(),
                        r.observationIndexAfterCommit(), r.regionWindowSequence(), r.windowStartOperation(),
                        r.windowEndOperation(), r.boundary(), r.totalOperations(), r.operationMix(),
                        r.latencySampleMix(), r.searchP95Nanos(), r.insertP99Nanos(), r.deleteP95Nanos(),
                        r.updateP95Nanos(), r.rangeP95Nanos(), r.overallP99Nanos(),
                        r.normalizedServiceCost(), r.averageScanAmplification(), r.leafSplits(), r.leafMerges(),
                        r.averageOccupancy(), r.minimumOccupancy(), r.leafCount(), r.recordCount());
            }
        }
    }

    private record DiagEvent(String phase, Diagnosis expected, AdaptationEvent event) {}

    private static long usedHeap() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
