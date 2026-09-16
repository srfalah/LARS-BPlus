package edu.jouf.larsbplus.lars;

import edu.jouf.larsbplus.core.BPlusTree;

import java.util.*;

/**
 * User-facing research index that wraps a conventional B+ tree with LARS telemetry and control.
 * All timing is measured around the tree operation only; controller bookkeeping is tracked
 * separately so experiment code can report foreground latency and control overhead independently.
 */
public final class LarsBPlusIndex {

    public record RuntimeCounters(long foregroundOperations,
                                  long controllerNanos,
                                  long observationWindows,
                                  long trialOperations,
                                  long referenceReadyWindows,
                                  long degradedWindows,
                                  long persistentTriggers,
                                  long diagnosisAttempts,
                                  long confidentDiagnoses,
                                  long candidateSelections,
                                  long trialStarts) {}

    private final BPlusTree tree;
    private final BPlusTree.PointSearchResult searchResult = new BPlusTree.PointSearchResult();
    private final BPlusTree.RangeTrace rangeTrace = new BPlusTree.RangeTrace();
    private final LarsConfig config;
    private final LarsController controller;
    private final int observationWindowSize;
    // Region ids are monotonically assigned node ids and remain compact in the experiment
    // trees. A primitive-indexed array avoids boxing a Long on every foreground operation.
    private RegionWindowAccumulator[] accumulatorsByRegionId = new RegionWindowAccumulator[1_024];
    private final Map<Long, RegionWindowAccumulator> overflowAccumulators = new HashMap<>();
    private long[] regionWindowSequenceById = new long[1_024];
    private final Map<Long, Long> overflowRegionWindowSequence = new HashMap<>();
    private final BPlusTree.RegionMetrics regionMetricsScratch = new BPlusTree.RegionMetrics();

    private long foregroundOperations;
    private final int[] latencySampleRemaining = new int[OperationType.values().length];
    private final int[] latencySampleStrides = new int[OperationType.values().length];
    private long controllerNanos;
    private long observationWindows;
    private long trialOperations;

    public LarsBPlusIndex(BPlusTree tree, LarsConfig config) {
        this(tree, config, LarsVariantPolicy.full());
    }

    public LarsBPlusIndex(BPlusTree tree, LarsConfig config, LarsVariantPolicy policy) {
        this.tree = Objects.requireNonNull(tree, "tree");
        this.config = Objects.requireNonNull(config, "config");
        this.controller = new LarsController(tree, config, Objects.requireNonNull(policy, "policy"));
        this.observationWindowSize = config.observationOperationsPerRegion();
        int base = config.latencySampleStride();
        for (OperationType type : OperationType.values()) {
            latencySampleStrides[type.ordinal()] = base == 1 || type == OperationType.INSERT
                    || type == OperationType.RANGE_SEARCH ? base : Math.multiplyExact(base, 4);
        }
    }

    public static LarsBPlusIndex createDefault() {
        return new LarsBPlusIndex(new BPlusTree(), LarsConfig.pilotDefaults());
    }

    public BPlusTree tree() { return tree; }
    public LarsController controller() { return controller; }
    public LarsConfig config() { return config; }

    private boolean shouldSampleLatency(OperationType type) {
        int ordinal = type.ordinal();
        int remaining = latencySampleRemaining[ordinal];
        if (remaining == 0) {
            latencySampleRemaining[ordinal] = latencySampleStrides[ordinal] - 1;
            return true;
        }
        latencySampleRemaining[ordinal] = remaining - 1;
        return false;
    }

    public OptionalLong search(long key) {
        return searchAndRecord(key) ? OptionalLong.of(searchResult.value()) : OptionalLong.empty();
    }

    // Keep OptionalLong creation in the small public method. Callers can inline it and
    // scalar-replace the returned value even when telemetry itself cannot be inlined.
    private boolean searchAndRecord(long key) {
        long activeTrialRegion = controller.activeTrialRegionOrNegative();
        boolean sampleLatency = shouldSampleLatency(OperationType.SEARCH);
        long start = sampleLatency ? System.nanoTime() : 0L;
        tree.searchInto(key, searchResult);
        long elapsed = sampleLatency ? System.nanoTime() - start : -1L;
        foregroundOperations++;
        long regionId = searchResult.regionId();
        if (regionId >= 0) {
            if (regionId == activeTrialRegion) {
                recordTrialOperation(regionId, OperationType.SEARCH, elapsed, Double.NaN, null);
            } else {
                recordNormalPoint(regionId, OperationType.SEARCH.ordinal(), elapsed);
            }
        }
        return searchResult.found();
    }

    public boolean insert(long key, long value) {
        BPlusTree.InsertMutation mutation = null;
        long regionBefore = -1L;
        boolean rolledBackBefore = false;
        long activeTrialRegion = controller.activeTrialRegionOrNegative();
        if (activeTrialRegion >= 0) {
            regionBefore = tree.regionIdForKey(key);
            if (regionBefore == activeTrialRegion) {
                mutation = new BPlusTree.InsertMutation(key, value);
                rolledBackBefore = prepareMutationForTrial(regionBefore, mutation);
            }
        }

        boolean sampleLatency = shouldSampleLatency(OperationType.INSERT);
        long start = sampleLatency ? System.nanoTime() : 0L;
        BPlusTree.MutationTrace trace = tree.insertWithRegion(key, value);
        long elapsed = sampleLatency ? System.nanoTime() - start : -1L;
        foregroundOperations++;

        boolean changed = trace.changed();
        BPlusTree.Mutation logged = changed ? mutation : null;
        long regionForAccounting = rolledBackBefore ? trace.regionIdBefore()
                : (activeTrialRegion >= 0 ? regionBefore : trace.regionIdBefore());
        recordPointOperation(regionForAccounting, OperationType.INSERT, elapsed, Double.NaN, logged,
                !rolledBackBefore, activeTrialRegion);
        return changed;
    }

    public boolean update(long key, long value) {
        BPlusTree.UpdateMutation mutation = null;
        long regionBefore = -1L;
        boolean rolledBackBefore = false;
        long activeTrialRegion = controller.activeTrialRegionOrNegative();
        if (activeTrialRegion >= 0) {
            regionBefore = tree.regionIdForKey(key);
            if (regionBefore == activeTrialRegion) {
                mutation = new BPlusTree.UpdateMutation(key, value);
                rolledBackBefore = prepareMutationForTrial(regionBefore, mutation);
            }
        }

        boolean sampleLatency = shouldSampleLatency(OperationType.UPDATE);
        long start = sampleLatency ? System.nanoTime() : 0L;
        BPlusTree.MutationTrace trace = tree.updateWithRegion(key, value);
        long elapsed = sampleLatency ? System.nanoTime() - start : -1L;
        foregroundOperations++;

        boolean changed = trace.changed();
        BPlusTree.Mutation logged = changed ? mutation : null;
        long regionForAccounting = rolledBackBefore ? trace.regionIdBefore()
                : (activeTrialRegion >= 0 ? regionBefore : trace.regionIdBefore());
        recordPointOperation(regionForAccounting, OperationType.UPDATE, elapsed, Double.NaN, logged,
                !rolledBackBefore, activeTrialRegion);
        return changed;
    }

    public boolean delete(long key) {
        BPlusTree.DeleteMutation mutation = null;
        long regionBefore = -1L;
        boolean rolledBackBefore = false;
        long activeTrialRegion = controller.activeTrialRegionOrNegative();
        if (activeTrialRegion >= 0) {
            regionBefore = tree.regionIdForKey(key);
            if (regionBefore == activeTrialRegion) {
                mutation = new BPlusTree.DeleteMutation(key);
                rolledBackBefore = prepareMutationForTrial(regionBefore, mutation);
            }
        }

        boolean sampleLatency = shouldSampleLatency(OperationType.DELETE);
        long start = sampleLatency ? System.nanoTime() : 0L;
        BPlusTree.MutationTrace trace = tree.deleteWithRegion(key);
        long elapsed = sampleLatency ? System.nanoTime() - start : -1L;
        foregroundOperations++;

        boolean changed = trace.changed();
        BPlusTree.Mutation logged = changed ? mutation : null;
        long regionForAccounting = rolledBackBefore ? trace.regionIdBefore()
                : (activeTrialRegion >= 0 ? regionBefore : trace.regionIdBefore());
        recordPointOperation(regionForAccounting, OperationType.DELETE, elapsed, Double.NaN, logged,
                !rolledBackBefore, activeTrialRegion);
        return changed;
    }

    public List<BPlusTree.Entry> rangeSearch(long fromInclusive, long toInclusive) {
        long activeTrialRegion = controller.activeTrialRegionOrNegative();
        boolean sampleLatency = shouldSampleLatency(OperationType.RANGE_SEARCH);
        long start = sampleLatency ? System.nanoTime() : 0L;
        List<BPlusTree.Entry> entries = tree.rangeSearchWithTrace(fromInclusive, toInclusive, rangeTrace);
        long elapsed = sampleLatency ? System.nanoTime() - start : -1L;
        foregroundOperations++;

        if (rangeTrace.regionCount() == 0) return entries;

        for (int i = 0; i < rangeTrace.regionCount(); i++) {
            long regionId = rangeTrace.regionId(i);
            if (regionId < 0) continue;
            int leaves = rangeTrace.leavesVisited(i);
            int useful = rangeTrace.results(i);
            double amp = leaves / (double) Math.max(1, useful);
            long attributedLatency = elapsed < 0 ? -1L : Math.max(1L,
                    Math.round(elapsed * (leaves / (double) Math.max(1, rangeTrace.totalLeavesVisited()))));

            if (regionId == activeTrialRegion) {
                recordTrialOperation(regionId, OperationType.RANGE_SEARCH, attributedLatency, amp, null);
            } else {
                recordNormalRange(regionId, attributedLatency, leaves, useful);
            }
        }
        return entries;
    }

    public RuntimeCounters runtimeCounters() {
        LarsController.DecisionCounters dc = controller.decisionCounters();
        return new RuntimeCounters(foregroundOperations, controllerNanos,
                observationWindows, trialOperations, dc.referenceReadyWindows(), dc.degradedWindows(),
                dc.persistentTriggers(), dc.diagnosisAttempts(), dc.confidentDiagnoses(),
                dc.candidateSelections(), dc.trialStarts());
    }

    public List<AdaptationEvent> adaptationEvents() {
        return controller.events();
    }

    public List<PostCommitObservation> postCommitObservations() {
        return controller.postCommitObservations();
    }

    public int adaptationEventCount() { return controller.eventCount(); }
    public AdaptationEvent adaptationEventAt(int index) { return controller.eventAt(index); }

    public void validate() { tree.validate(); }

    /**
     * Diagnostic-calibration hook: learn regional references without allowing adaptation.
     * This is intentionally separate from normal publication workloads.
     */
    public void beginReferenceCalibration() {
        if (controller.hasActiveTrial()) {
            throw new IllegalStateException("cannot begin reference calibration during an active trial");
        }
        resetObservationPhaseBoundary();
        controller.beginReferenceCalibration();
    }

    /**
     * Diagnostic-calibration hook: freeze the learned reference and enable adaptation for the
     * labeled ground-truth phase. Partial windows and structural counters are cleared first so
     * no evidence leaks across the phase boundary.
     */
    public void beginGroundTruthCalibration(long regionId) {
        if (controller.hasActiveTrial()) {
            throw new IllegalStateException("cannot begin ground-truth calibration during an active trial");
        }
        resetObservationPhaseBoundary();
        controller.beginGroundTruthCalibration(regionId);
    }

    public void endCalibration() {
        if (controller.hasActiveTrial()) {
            throw new IllegalStateException("cannot end calibration during an active trial");
        }
        resetObservationPhaseBoundary();
        controller.endCalibration();
    }


    private void resetObservationPhaseBoundary() {
        Arrays.fill(accumulatorsByRegionId, null);
        overflowAccumulators.clear();
        for (BPlusTree.RegionView region : tree.regions()) {
            tree.consumeStructuralCounters(region.regionId());
        }
    }

    private boolean prepareMutationForTrial(long regionBefore, BPlusTree.Mutation mutation) {
        if (regionBefore < 0) return false;
        long c0 = System.nanoTime();
        LarsController.TrialMutationDecision decision = controller.beforeTrialMutation(
                mutation, regionBefore, foregroundOperations + 1L);
        controllerNanos += System.nanoTime() - c0;
        return decision == LarsController.TrialMutationDecision.TRIAL_ROLLED_BACK_BEFORE_MUTATION;
    }

    private void recordPointOperation(long regionId,
                                      OperationType type,
                                      long latencyNanos,
                                      double scanAmplification,
                                      BPlusTree.Mutation mutation,
                                      boolean mayBelongToTrial) {
        recordPointOperation(regionId, type, latencyNanos, scanAmplification, mutation,
                mayBelongToTrial, controller.activeTrialRegionOrNegative());
    }

    private void recordPointOperation(long regionId,
                                      OperationType type,
                                      long latencyNanos,
                                      double scanAmplification,
                                      BPlusTree.Mutation mutation,
                                      boolean mayBelongToTrial,
                                      long activeTrialRegion) {
        if (regionId < 0) return;
        if (mayBelongToTrial && regionId == activeTrialRegion) {
            recordTrialOperation(regionId, type, latencyNanos, scanAmplification, mutation);
        } else {
            recordNormalPoint(regionId, type.ordinal(), latencyNanos);
        }
    }

    private void recordTrialOperation(long regionId,
                                      OperationType type,
                                      long latencyNanos,
                                      double scanAmplification,
                                      BPlusTree.Mutation mutation) {
        long c0 = System.nanoTime();
        controller.onTrialOperation(regionId, type, latencyNanos, scanAmplification, mutation,
                foregroundOperations);
        controllerNanos += System.nanoTime() - c0;
        trialOperations++;
    }

    private RegionWindowAccumulator accumulatorFor(long regionId) {
        if (regionId >= 0 && regionId < accumulatorsByRegionId.length) {
            int index = (int) regionId;
            RegionWindowAccumulator acc = accumulatorsByRegionId[index];
            return acc != null ? acc : createAccumulator(index, regionId);
        }
        return accumulatorForSlow(regionId);
    }

    private RegionWindowAccumulator createAccumulator(int index, long regionId) {
        RegionWindowAccumulator acc = new RegionWindowAccumulator(regionId);
        accumulatorsByRegionId[index] = acc;
        return acc;
    }

    private RegionWindowAccumulator accumulatorForSlow(long regionId) {
        if (regionId >= 0 && regionId <= Integer.MAX_VALUE) {
            int index = (int) regionId;
            ensureAccumulatorCapacity(index);
            return createAccumulator(index, regionId);
        }
        return overflowAccumulators.computeIfAbsent(regionId, RegionWindowAccumulator::new);
    }

    private void removeAccumulator(long regionId) {
        if (regionId >= 0 && regionId <= Integer.MAX_VALUE) {
            int index = (int) regionId;
            if (index < accumulatorsByRegionId.length) accumulatorsByRegionId[index] = null;
        } else {
            overflowAccumulators.remove(regionId);
        }
    }

    private void ensureAccumulatorCapacity(int index) {
        if (index < accumulatorsByRegionId.length) return;
        int newLength = accumulatorsByRegionId.length;
        while (newLength <= index) {
            int grown = newLength << 1;
            if (grown <= 0 || grown > Integer.MAX_VALUE - 8) {
                newLength = index + 1;
                break;
            }
            newLength = grown;
        }
        accumulatorsByRegionId = Arrays.copyOf(accumulatorsByRegionId, newLength);
        regionWindowSequenceById = Arrays.copyOf(regionWindowSequenceById, newLength);
    }

    private long nextRegionWindowSequence(long regionId) {
        if (regionId >= 0 && regionId <= Integer.MAX_VALUE) {
            int index = (int) regionId;
            ensureAccumulatorCapacity(index);
            return ++regionWindowSequenceById[index];
        }
        long next = overflowRegionWindowSequence.getOrDefault(regionId, 0L) + 1L;
        overflowRegionWindowSequence.put(regionId, next);
        return next;
    }

    private void recordNormalPoint(long regionId, int operationTypeOrdinal, long latencyNanos) {
        // Do not materialize RegionView on every foreground operation. The tree keeps an
        // O(1) leaf-level region registry, but RegionView construction still scans the local
        // region's leaves, so it is deferred until an observation window closes.
        RegionWindowAccumulator acc = accumulatorFor(regionId);
        acc.recordPoint(operationTypeOrdinal, latencyNanos, foregroundOperations);
        if (acc.operationCount() < observationWindowSize) return;

        closeWindow(regionId, acc);
    }

    private void recordNormalRange(long regionId, long latencyNanos,
                                   int leavesVisited, int usefulResults) {
        RegionWindowAccumulator acc = accumulatorFor(regionId);
        acc.recordRange(latencyNanos, leavesVisited, usefulResults, foregroundOperations);
        if (acc.operationCount() < observationWindowSize) return;

        closeWindow(regionId, acc);
    }

    // Keep allocation, sorting, structural inspection and controller work outside the
    // small recordNormal method so the ordinary operation path can be inlined.
    private void closeWindow(long regionId, RegionWindowAccumulator acc) {
        if (!tree.fillRegionMetrics(regionId, regionMetricsScratch)) {
            removeAccumulator(regionId);
            return;
        }
        BPlusTree.StructuralCounters counters = tree.consumeStructuralCounters(regionId);
        long sequence = nextRegionWindowSequence(regionId);
        RegionWindowStats stats = acc.finish(sequence, regionMetricsScratch, counters);
        acc.reset();
        observationWindows++;

        long c0 = System.nanoTime();
        controller.onObservationWindow(stats, foregroundOperations);
        controllerNanos += System.nanoTime() - c0;
    }

    /**
     * Resolves an incomplete live trial, captures any partial affected-region window, and
     * stops post-COMMIT attribution at a phase/run boundary. A full-policy trial is rolled
     * back so an unvalidated candidate can never leak into the next phase or out of a run.
     */
    public void endPostCommitObservationScope(String boundary) {
        long c0 = System.nanoTime();
        controller.endActiveTrialAtBoundary(boundary, foregroundOperations);
        controllerNanos += System.nanoTime() - c0;
        for (long regionId : controller.trackedPostCommitRegionIds()) {
            RegionWindowAccumulator acc = existingAccumulator(regionId);
            if (acc == null || acc.operationCount() == 0) continue;
            if (!tree.fillRegionMetrics(regionId, regionMetricsScratch)) continue;
            RegionWindowStats stats = acc.finish(peekNextRegionWindowSequence(regionId),
                    regionMetricsScratch, null);
            controller.recordPostCommitBoundarySnapshot(stats, boundary);
        }
        controller.endPostCommitTracking();
    }

    private RegionWindowAccumulator existingAccumulator(long regionId) {
        if (regionId >= 0 && regionId <= Integer.MAX_VALUE) {
            int index = (int) regionId;
            return index < accumulatorsByRegionId.length ? accumulatorsByRegionId[index] : null;
        }
        return overflowAccumulators.get(regionId);
    }

    private long peekNextRegionWindowSequence(long regionId) {
        if (regionId >= 0 && regionId <= Integer.MAX_VALUE) {
            int index = (int) regionId;
            return index < regionWindowSequenceById.length ? regionWindowSequenceById[index] + 1L : 1L;
        }
        return overflowRegionWindowSequence.getOrDefault(regionId, 0L) + 1L;
    }
}
