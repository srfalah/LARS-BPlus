package edu.jouf.larsbplus.lars;

import edu.jouf.larsbplus.core.BPlusTree;

import java.util.*;

/**
 * LARS-B+ regional adaptation controller.
 *
 * <p>Research contract:</p>
 * <ol>
 *   <li>Detect persistent region-level degradation.</li>
 *   <li>Diagnose one of two supported causes or abstain.</li>
 *   <li>Localize a bounded contiguous leaf window.</li>
 *   <li>Reject candidates that violate hard locality budgets.</li>
 *   <li>Use a lightweight payback gate before trial.</li>
 *   <li>Apply SLACKEN or COMPACT.</li>
 *   <li>Observe live regional operations.</li>
 *   <li>Commit only on realized improvement; otherwise restore + replay writes.</li>
 * </ol>
 */
public final class LarsController {

    public enum TrialMutationDecision {
        NOT_IN_TRIAL,
        SAFE_ON_CANDIDATE,
        TRIAL_ROLLED_BACK_BEFORE_MUTATION
    }

    public record DecisionCounters(long observationWindows,
                                   long referenceReadyWindows,
                                   long degradedWindows,
                                   long persistentTriggers,
                                   long diagnosisAttempts,
                                   long confidentDiagnoses,
                                   long candidateSelections,
                                   long trialStarts) {}

    /**
     * NORMAL: ordinary controller behavior.
     * MONITOR_ONLY: learn stable references but never start an adaptation.
     * FROZEN_REFERENCE: do not update stable references; detection/adaptation is enabled.
     *
     * MONITOR_ONLY and FROZEN_REFERENCE exist for diagnostic calibration only.
     */
    public enum ControlMode {
        NORMAL,
        MONITOR_ONLY,
        FROZEN_REFERENCE
    }

    private static final double EPS = 1e-9;

    private final BPlusTree tree;
    private final LarsConfig config;
    private final LarsVariantPolicy policy;
    private final Map<Long, RegionState> regionStates = new HashMap<>();
    private final List<AdaptationEvent> events = new ArrayList<>();
    private final List<PostCommitObservation> postCommitObservations = new ArrayList<>();
    private final Map<Long, PostCommitTracking> postCommitTrackingByRegion = new HashMap<>();
    private long eventSequence;
    private long currentOperationIndex = -1L;
    private TrialState activeTrial;
    private ControlMode controlMode = ControlMode.NORMAL;
    private long observationWindowsSeen;
    private long referenceReadyWindows;
    private long degradedWindows;
    private long persistentTriggers;
    private long diagnosisAttempts;
    private long confidentDiagnoses;
    private long candidateSelections;
    private long trialStarts;

    public LarsController(BPlusTree tree, LarsConfig config) {
        this(tree, config, LarsVariantPolicy.full());
    }

    public LarsController(BPlusTree tree, LarsConfig config, LarsVariantPolicy policy) {
        this.tree = Objects.requireNonNull(tree, "tree");
        this.config = Objects.requireNonNull(config, "config");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public LarsConfig config() { return config; }
    public LarsVariantPolicy policy() { return policy; }
    public ControlMode controlMode() { return controlMode; }

    public void setControlMode(ControlMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (activeTrial != null) {
            throw new IllegalStateException("cannot change control mode while a trial is active");
        }
        this.controlMode = mode;
    }

    /**
     * Clears transient detection/cooldown state while preserving learned references.
     * Used at a diagnostic phase boundary so evidence cannot leak across phases.
     */
    public void resetTransientDecisionState() {
        if (activeTrial != null) {
            throw new IllegalStateException("cannot reset decision state while a trial is active");
        }
        for (RegionState state : regionStates.values()) {
            state.persistentDegradation = 0;
            state.cooldownRemaining = 0;
            state.lastServiceCost = Double.NaN;
            state.lastWindow = null;
            state.lastDiagnosis = Diagnosis.NO_CONFIDENT_DIAGNOSIS;
            state.lastFailedSignature = null;
        }
    }

    public boolean referenceReady(long regionId) {
        RegionState state = regionStates.get(regionId);
        return state != null && state.reference.ready(config.referenceWarmupWindows());
    }

    public boolean referenceStructurallyStable(long regionId) {
        RegionState state = regionStates.get(regionId);
        return state != null && state.referenceStructuralEvents == 0;
    }

    /** Marks the start of a monitor-only reference-learning phase. */
    public void beginReferenceCalibration() {
        setControlMode(ControlMode.MONITOR_ONLY);
        resetTransientDecisionState();
        for (RegionState state : regionStates.values()) state.referenceStructuralEvents = 0;
    }

    /**
     * Freezes the learned reference and enables adaptation for a labeled ground-truth phase.
     * A structurally contaminated reference is rejected instead of silently calibrated.
     */
    public void beginGroundTruthCalibration(long regionId) {
        if (!referenceReady(regionId)) {
            throw new IllegalStateException("stable reference is not ready for region " + regionId);
        }
        if (!referenceStructurallyStable(regionId)) {
            throw new IllegalStateException("reference phase changed tree structure for region " + regionId);
        }
        setControlMode(ControlMode.FROZEN_REFERENCE);
        resetTransientDecisionState();
    }

    public void endCalibration() {
        setControlMode(ControlMode.NORMAL);
        resetTransientDecisionState();
    }

    public boolean hasActiveTrial() { return activeTrial != null; }
    long activeTrialRegionOrNegative() { return activeTrial == null ? -1L : activeTrial.regionId; }
    public boolean isActiveTrialRegion(long regionId) {
        return activeTrial != null && activeTrial.regionId == regionId;
    }
    public OptionalLong activeTrialRegionId() {
        return activeTrial == null ? OptionalLong.empty() : OptionalLong.of(activeTrial.regionId);
    }
    public List<AdaptationEvent> events() { return List.copyOf(events); }
    public int eventCount() { return events.size(); }
    public AdaptationEvent eventAt(int index) { return events.get(index); }
    public List<PostCommitObservation> postCommitObservations() { return List.copyOf(postCommitObservations); }
    public Set<Long> trackedPostCommitRegionIds() { return Set.copyOf(postCommitTrackingByRegion.keySet()); }
    public boolean isTrackingPostCommit(long regionId) { return postCommitTrackingByRegion.containsKey(regionId); }
    public DecisionCounters decisionCounters() {
        return new DecisionCounters(observationWindowsSeen, referenceReadyWindows, degradedWindows,
                persistentTriggers, diagnosisAttempts, confidentDiagnoses, candidateSelections, trialStarts);
    }

    public boolean isRegionInCooldown(long regionId) {
        RegionState state = regionStates.get(regionId);
        return state != null && state.cooldownRemaining > 0;
    }

    /** Called when a normal (non-trial) observation window completes. */
    public void onObservationWindow(RegionWindowStats stats) {
        onObservationWindow(stats, stats.windowEndOperation());
    }

    /** Called when a normal observation window completes at an exact global operation. */
    public void onObservationWindow(RegionWindowStats stats, long operationIndex) {
        currentOperationIndex = operationIndex;
        observationWindowsSeen++;
        recordPostCommitObservation(stats, "WINDOW_COMPLETE");
        if (!tree.hasLeafLevelRegion(stats.regionId())) {
            regionStates.remove(stats.regionId());
            postCommitTrackingByRegion.remove(stats.regionId());
            return;
        }

        RegionState state = regionStates.computeIfAbsent(stats.regionId(), id -> new RegionState());
        state.lastWindow = stats;

        if (state.cooldownRemaining > 0) {
            state.cooldownRemaining--;
            state.persistentDegradation = 0;
            return;
        }

        if (controlMode == ControlMode.MONITOR_ONLY) {
            state.referenceStructuralEvents += Math.abs(stats.leafSplits()) + Math.abs(stats.leafMerges());
        }

        if (!state.reference.ready(config.referenceWarmupWindows())) {
            if (controlMode != ControlMode.FROZEN_REFERENCE) {
                state.reference.update(stats, config.stableReferenceAlpha(), config.minSamplesPerOperationType());
            }
            return;
        }

        referenceReadyWindows++;
        double serviceCost = normalizedServiceCost(stats, state.reference);
        state.lastServiceCost = serviceCost;
        if (!Double.isFinite(serviceCost)) {
            return;
        }

        if (serviceCost > 1.0 + config.degradationThreshold()) {
            degradedWindows++;
            state.persistentDegradation++;
        } else {
            state.persistentDegradation = 0;
            if (controlMode != ControlMode.FROZEN_REFERENCE) {
                state.reference.update(stats, config.stableReferenceAlpha(), config.minSamplesPerOperationType());
            }
            return;
        }

        if (state.persistentDegradation < config.persistenceWindows()) return;
        persistentTriggers++;
        if (controlMode == ControlMode.MONITOR_ONLY) return;
        if (activeTrial != null) return;

        Optional<Candidate> candidate;
        if (policy.useDiagnosis()) {
            diagnosisAttempts++;
            DiagnosisResult diagnosis = diagnose(stats, state.reference);
            state.lastDiagnosis = diagnosis.diagnosis;
            if (diagnosis.diagnosis == Diagnosis.NO_CONFIDENT_DIAGNOSIS) {
                logAbstention(stats.regionId(), Diagnosis.NO_CONFIDENT_DIAGNOSIS,
                        "diagnostic confidence not satisfied: insertScore=" + diagnosis.insertScore
                                + ", scanScore=" + diagnosis.scanScore);
                return;
            }
            confidentDiagnoses++;
            candidate = selectCandidate(stats, state, diagnosis);
        } else {
            state.lastDiagnosis = Diagnosis.NO_CONFIDENT_DIAGNOSIS;
            candidate = selectCandidateWithoutDiagnosis(stats, state);
        }
        if (candidate.isEmpty()) return;
        candidateSelections++;
        startTrial(candidate.get(), stats, state);
    }

    /**
     * Called before a mutating operation that would touch the active trial region.
     * If the mutation would escape the local region boundary, the candidate is rolled back first.
     */
    public TrialMutationDecision beforeTrialMutation(BPlusTree.Mutation mutation, long regionIdBeforeOperation) {
        return beforeTrialMutation(mutation, regionIdBeforeOperation, -1L);
    }

    public TrialMutationDecision beforeTrialMutation(BPlusTree.Mutation mutation,
                                                      long regionIdBeforeOperation,
                                                      long operationIndex) {
        currentOperationIndex = operationIndex;
        if (activeTrial == null || activeTrial.regionId != regionIdBeforeOperation) {
            return TrialMutationDecision.NOT_IN_TRIAL;
        }
        if (tree.canApplyMutationWithinRegion(activeTrial.regionId, mutation)) {
            return TrialMutationDecision.SAFE_ON_CANDIDATE;
        }
        if (policy.useRollback()) {
            rollbackActiveTrial("trial mutation would escape the monitored leaf-level region",
                    Double.NaN, Double.NaN, Double.NaN);
        } else {
            keepFailedTrial("trial mutation would escape the monitored leaf-level region",
                    Double.NaN, Double.NaN, Double.NaN);
        }
        return TrialMutationDecision.TRIAL_ROLLED_BACK_BEFORE_MUTATION;
    }

    /**
     * Records one live operation that touched the active trial region.
     * mutation is non-null only when a mutating operation actually changed the index state.
     */
    public void onTrialOperation(long regionId,
                                 OperationType type,
                                 long latencyNanos,
                                 double scanAmplification,
                                 BPlusTree.Mutation mutation) {
        onTrialOperation(regionId, type, latencyNanos, scanAmplification, mutation, -1L);
    }

    public void onTrialOperation(long regionId,
                                 OperationType type,
                                 long latencyNanos,
                                 double scanAmplification,
                                 BPlusTree.Mutation mutation,
                                 long operationIndex) {
        currentOperationIndex = operationIndex;
        TrialState trial = activeTrial;
        if (trial == null || trial.regionId != regionId) return;

        if (trial.trialStartOperation < 0) trial.trialStartOperation = operationIndex;
        trial.trialEndOperation = operationIndex;

        trial.metrics.record(type, latencyNanos, scanAmplification);
        if (mutation != null) trial.mutations.add(mutation);

        int trialOperations = trial.metrics.totalOperations();
        if (trialOperations < config.trialOperations()) return;

        double drift = operationMixDrift(trial.triggerStats, trial.metrics);
        if (drift > config.maxOperationMixDrift() && trialOperations < config.maxTrialOperations()) {
            return; // bounded extension to seek a more comparable live sample
        }

        if (drift > config.maxOperationMixDrift()) {
            trial.lastEvaluation = evaluateTrial(trial, drift);
            rejectOrKeepTrial("workload drift remained above threshold at maximum trial length",
                    Double.NaN, Double.NaN, drift);
            return;
        }

        // At the nominal boundary we must make the first decision. During a bounded extension,
        // however, an unsampled operation changes only the operation mix: re-sorting the same
        // latency samples cannot make the trial comparable and used to put evaluateTrial() on
        // every affected operation. Re-evaluate only when a new latency sample arrives, or once
        // at the hard maximum so every trial still receives a terminal decision.
        boolean nominalBoundary = trialOperations == config.trialOperations();
        boolean maximumBoundary = trialOperations >= config.maxTrialOperations();
        if (!nominalBoundary && latencyNanos < 0L && !maximumBoundary) return;

        TrialEvaluation eval = evaluateTrial(trial, drift);
        trial.lastEvaluation = eval;
        if (!eval.comparable) {
            if (trialOperations < config.maxTrialOperations()) {
                return; // bounded extension to collect enough deterministic latency samples
            }
            rejectOrKeepTrial(eval.reason, eval.rawGain, eval.netGain, drift);
            return;
        }

        if (eval.netGain >= config.minimumNetGain() && eval.tailGuardPassed) {
            commitActiveTrial(eval.rawGain, eval.netGain, drift);
        } else {
            rejectOrKeepTrial(eval.reason, eval.rawGain, eval.netGain, drift);
        }
    }

    /**
     * Terminates a censored trial at an experiment boundary. The ordinary full policy restores
     * the pre-action snapshot and replays live mutations; the no-rollback ablation preserves its
     * explicit keep semantics. Either way, every started trial receives a terminal event.
     */
    public void endActiveTrialAtBoundary(String boundary, long operationIndex) {
        currentOperationIndex = operationIndex;
        TrialState trial = activeTrial;
        if (trial == null) return;

        double drift = trial.metrics.totalOperations() == 0 ? Double.NaN
                : operationMixDrift(trial.triggerStats, trial.metrics);
        TrialEvaluation eval = trial.metrics.totalOperations() == 0 ? null
                : evaluateTrial(trial, drift);
        trial.lastEvaluation = eval;
        String reason = "trial censored at " + boundary + " before a terminal live-trial decision";
        rejectOrKeepTrial(reason,
                eval == null ? Double.NaN : eval.rawGain,
                eval == null ? Double.NaN : eval.netGain,
                drift);
    }

    private void startTrial(Candidate candidate, RegionWindowStats triggerStats, RegionState state) {
        // A new structural action on the same region ends attribution to the previous COMMIT.
        postCommitTrackingByRegion.remove(candidate.regionId);
        BPlusTree.RegionSnapshot snapshot = tree.snapshotRegion(candidate.regionId);
        BPlusTree.ActionResult result;
        try {
            result = tree.applyAction(candidate.estimate);
        } catch (RuntimeException ex) {
            try { tree.restoreRegion(snapshot); }
            catch (RuntimeException ignored) { /* end-of-run validation exposes a catastrophic implementation bug */ }
            logAbstention(candidate.regionId, candidate.diagnosis,
                    "candidate application failed: " + ex.getMessage());
            return;
        }

        trialStarts++;
        if (!policy.useRuntimeValidation()) {
            state.cooldownRemaining = config.cooldownWindows();
            state.persistentDegradation = 0;
            state.lastFailedSignature = null;
            AdaptationEvent event = toImmediateEvent(candidate, result.elapsedNanos(), "COMMIT_NO_TRIAL",
                    "runtime validation disabled by evaluation policy");
            events.add(event);
            postCommitTrackingByRegion.put(candidate.regionId,
                    new PostCommitTracking(event.sequence(), candidate.regionId, event.commitOperationIndex()));
            return;
        }

        activeTrial = new TrialState(
                candidate.regionId,
                candidate.diagnosis,
                candidate.estimate.action(),
                candidate.estimate,
                snapshot,
                triggerStats,
                result.elapsedNanos(),
                currentOperationIndex,
                candidate.costEstimate,
                new TrialMetrics(),
                new ArrayList<>()
        );
        state.persistentDegradation = 0;
    }

    private void commitActiveTrial(double rawGain, double netGain, double drift) {
        TrialState trial = activeTrial;
        if (trial == null) return;
        RegionState state = regionStates.computeIfAbsent(trial.regionId, id -> new RegionState());
        state.cooldownRemaining = config.cooldownWindows();
        state.persistentDegradation = 0;
        state.lastFailedSignature = null;

        AdaptationEvent event = toEvent(trial, "COMMIT", "trial acceptance rule satisfied",
                rawGain, netGain, drift);
        events.add(event);
        postCommitTrackingByRegion.put(trial.regionId,
                new PostCommitTracking(event.sequence(), trial.regionId, event.commitOperationIndex()));
        activeTrial = null;
    }

    private void rollbackActiveTrial(String reason, double rawGain, double netGain, double drift) {
        TrialState trial = activeTrial;
        if (trial == null) return;

        tree.restoreRegion(trial.snapshot);
        tree.replayMutations(trial.mutations);

        RegionState state = regionStates.computeIfAbsent(trial.regionId, id -> new RegionState());
        state.cooldownRemaining = config.cooldownWindows();
        state.persistentDegradation = 0;
        state.lastFailedSignature = new FailedSignature(trial.diagnosis, trial.action,
                structuralSignature(trial.regionId));

        events.add(toEvent(trial, "ROLLBACK", reason, rawGain, netGain, drift));
        activeTrial = null;
    }

    private AdaptationEvent toEvent(TrialState trial,
                                    String outcome,
                                    String reason,
                                    double rawGain,
                                    double netGain,
                                    double drift) {
        long records = Math.max(1L, tree.size());
        long leaves = Math.max(1L, tree.leafCount());
        return new AdaptationEvent(
                ++eventSequence,
                trial.regionId,
                currentOperationIndex,
                trial.actionOperationIndex,
                trial.trialStartOperation,
                trial.trialEndOperation,
                "COMMIT".equals(outcome) ? currentOperationIndex : -1L,
                trial.triggerStats.windowSequence(),
                trial.diagnosis,
                trial.action,
                outcome,
                reason,
                trial.estimate.fromLeafInclusive(),
                trial.estimate.toLeafInclusive(),
                trial.estimate.recordsMoved(),
                trial.estimate.leavesTouched(),
                trial.estimate.recordsMoved() / (double) records,
                trial.estimate.leavesTouched() / (double) leaves,
                trial.costEstimate.regionExposureFraction,
                trial.costEstimate.predictedBenefitNanos,
                trial.costEstimate.estimatedActionCostNanos,
                trial.costEstimate.safetyAdjustedCostNanos,
                trial.costEstimate.predictedBreakEvenOperations,
                trial.actionNanos,
                trial.metrics.totalOperations(),
                trial.lastEvaluation == null ? 0 : trial.lastEvaluation.comparableSamples,
                trial.lastEvaluation == null ? Double.NaN : trial.lastEvaluation.baselineObjectiveNanos,
                trial.lastEvaluation == null ? Double.NaN : trial.lastEvaluation.candidateObjectiveNanos,
                trial.lastEvaluation == null ? Double.NaN : trial.lastEvaluation.baselineP99Nanos,
                trial.lastEvaluation == null ? Double.NaN : trial.lastEvaluation.candidateP99Nanos,
                operationMix(trial.triggerStats),
                operationMix(trial.metrics),
                rawGain,
                netGain,
                drift
        );
    }

    private void rejectOrKeepTrial(String reason, double rawGain, double netGain, double drift) {
        if (policy.useRollback()) rollbackActiveTrial(reason, rawGain, netGain, drift);
        else keepFailedTrial(reason, rawGain, netGain, drift);
    }

    private void keepFailedTrial(String reason, double rawGain, double netGain, double drift) {
        TrialState trial = activeTrial;
        if (trial == null) return;
        RegionState state = regionStates.computeIfAbsent(trial.regionId, id -> new RegionState());
        state.cooldownRemaining = config.cooldownWindows();
        state.persistentDegradation = 0;
        state.lastFailedSignature = new FailedSignature(trial.diagnosis, trial.action,
                structuralSignature(trial.regionId));
        events.add(toEvent(trial, "KEEP_FAILED_TRIAL", reason, rawGain, netGain, drift));
        activeTrial = null;
    }

    private AdaptationEvent toImmediateEvent(Candidate candidate, long actionNanos,
                                             String outcome, String reason) {
        long records = Math.max(1L, tree.size());
        long leaves = Math.max(1L, tree.leafCount());
        BPlusTree.ActionEstimate e = candidate.estimate;
        return new AdaptationEvent(
                ++eventSequence, candidate.regionId,
                currentOperationIndex, currentOperationIndex, -1L, -1L,
                outcome.startsWith("COMMIT") ? currentOperationIndex : -1L,
                candidate.triggerWindowSequence,
                candidate.diagnosis, e.action(), outcome, reason,
                e.fromLeafInclusive(), e.toLeafInclusive(), e.recordsMoved(), e.leavesTouched(),
                e.recordsMoved() / (double) records, e.leavesTouched() / (double) leaves,
                candidate.costEstimate.regionExposureFraction,
                candidate.costEstimate.predictedBenefitNanos,
                candidate.costEstimate.estimatedActionCostNanos,
                candidate.costEstimate.safetyAdjustedCostNanos,
                candidate.costEstimate.predictedBreakEvenOperations,
                actionNanos, 0, 0,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                "", "", Double.NaN, Double.NaN, Double.NaN);
    }

    private Optional<Candidate> selectCandidateWithoutDiagnosis(RegionWindowStats stats, RegionState state) {
        BPlusTree.RegionView region = tree.region(stats.regionId()).orElse(null);
        if (region == null) return Optional.empty();

        Candidate best = null;
        double bestUtility = Double.NEGATIVE_INFINITY;
        for (BPlusTree.LocalAction action : BPlusTree.LocalAction.values()) {
            Optional<BPlusTree.ActionEstimate> estimate = localizeAndEstimate(region, action);
            if (estimate.isEmpty()) continue;
            BPlusTree.ActionEstimate e = estimate.get();
            double movedFraction = e.recordsMoved() / (double) Math.max(1L, tree.size());
            double touchedFraction = e.leavesTouched() / (double) Math.max(1L, tree.leafCount());
            if (policy.useLocalityBudget() && (movedFraction > config.maxMovedRecordFraction()
                    || touchedFraction > config.maxTouchedLeafFraction())) continue;
            CostModelEstimate costEstimate = costModelEstimate(stats, state.reference, e, action);
            if (!costEstimate.passes) continue;

            OperationType relevant = action == BPlusTree.LocalAction.SLACKEN
                    ? OperationType.INSERT : OperationType.RANGE_SEARCH;
            double current = observedPercentile(stats, relevant);
            double baseline = referencePercentile(state.reference, relevant);
            if (!Double.isFinite(current) || !Double.isFinite(baseline) || baseline <= 0) continue;
            double predictedExcess = Math.max(0.0, (current - baseline) / baseline);
            double estimatedCost = e.recordsMoved() * config.estimatedRecordMoveNanos()
                    + e.leavesTouched() * config.estimatedLeafTouchNanos();
            double utility = predictedExcess - estimatedCost / Math.max(1.0,
                    config.benefitHorizonOperations() * regionExposureFraction(stats) * baseline);
            if (utility > bestUtility) {
                bestUtility = utility;
                Diagnosis label = action == BPlusTree.LocalAction.SLACKEN
                        ? Diagnosis.INSERT_SPLIT_PRESSURE : Diagnosis.SCAN_FRAGMENTATION;
                best = new Candidate(stats.regionId(), label, e, stats.windowSequence(), costEstimate);
            }
        }
        if (best == null) {
            logAbstention(stats.regionId(), Diagnosis.NO_CONFIDENT_DIAGNOSIS,
                    "no cost-admissible candidate without explicit diagnosis");
            return Optional.empty();
        }
        return Optional.of(best);
    }

    private Optional<Candidate> selectCandidate(RegionWindowStats stats,
                                                RegionState state,
                                                DiagnosisResult diagnosis) {
        BPlusTree.RegionView region = tree.region(stats.regionId()).orElse(null);
        if (region == null) return Optional.empty();

        BPlusTree.LocalAction action = diagnosis.diagnosis == Diagnosis.INSERT_SPLIT_PRESSURE
                ? BPlusTree.LocalAction.SLACKEN
                : BPlusTree.LocalAction.COMPACT;

        Optional<BPlusTree.ActionEstimate> estimate = localizeAndEstimate(region, action);
        if (estimate.isEmpty()) {
            logAbstention(stats.regionId(), diagnosis.diagnosis,
                    "no feasible bounded repair window for " + action);
            return Optional.empty();
        }

        BPlusTree.ActionEstimate e = estimate.get();
        double movedFraction = e.recordsMoved() / (double) Math.max(1L, tree.size());
        double touchedFraction = e.leavesTouched() / (double) Math.max(1L, tree.leafCount());
        if (policy.useLocalityBudget() && (movedFraction > config.maxMovedRecordFraction()
                || touchedFraction > config.maxTouchedLeafFraction())) {
            logAbstention(stats.regionId(), diagnosis.diagnosis,
                    "hard locality budget rejected candidate: moved=" + movedFraction
                            + ", touchedLeaves=" + touchedFraction);
            return Optional.empty();
        }

        CostModelEstimate costEstimate = costModelEstimate(stats, state.reference, e, action);
        if (!costEstimate.passes) {
            logAbstention(stats.regionId(), diagnosis.diagnosis,
                    "predicted benefit does not repay estimated adaptation cost within configured horizon");
            return Optional.empty();
        }

        FailedSignature failed = state.lastFailedSignature;
        if (failed != null && failed.diagnosis == diagnosis.diagnosis
                && failed.action == action
                && Objects.equals(failed.structuralSignature, structuralSignature(stats.regionId()))) {
            logAbstention(stats.regionId(), diagnosis.diagnosis,
                    "same diagnosis/action recently failed under an unchanged region signature");
            return Optional.empty();
        }

        return Optional.of(new Candidate(stats.regionId(), diagnosis.diagnosis, e,
                stats.windowSequence(), costEstimate));
    }

    private Optional<BPlusTree.ActionEstimate> localizeAndEstimate(BPlusTree.RegionView region,
                                                                   BPlusTree.LocalAction action) {
        int n = region.leafCount();
        BPlusTree.ActionEstimate best = null;
        double bestScore = action == BPlusTree.LocalAction.SLACKEN
                ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;

        int maxWindow = Math.min(config.maxRepairLeaves(), n);
        for (int width = config.minRepairLeaves(); width <= maxWindow; width++) {
            for (int from = 0; from + width <= n; from++) {
                int to = from + width - 1;
                BPlusTree.ActionEstimate estimate = tree.estimateAction(action, region.regionId(), from, to);
                if (!estimate.feasible()) continue;

                double occupancy = windowAverageOccupancy(region, from, to);
                if (action == BPlusTree.LocalAction.SLACKEN) {
                    if (occupancy > bestScore || (occupancy == bestScore && betterLocality(estimate, best))) {
                        bestScore = occupancy;
                        best = estimate;
                    }
                } else {
                    if (occupancy < bestScore || (occupancy == bestScore && betterLocality(estimate, best))) {
                        bestScore = occupancy;
                        best = estimate;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean betterLocality(BPlusTree.ActionEstimate candidate, BPlusTree.ActionEstimate current) {
        if (current == null) return true;
        if (candidate.recordsMoved() != current.recordsMoved()) return candidate.recordsMoved() < current.recordsMoved();
        return candidate.leavesTouched() < current.leavesTouched();
    }

    private static double windowAverageOccupancy(BPlusTree.RegionView region, int from, int to) {
        double sum = 0;
        for (int i = from; i <= to; i++) sum += region.leaves().get(i).occupancy();
        return sum / (to - from + 1);
    }

    private CostModelEstimate costModelEstimate(RegionWindowStats stats,
                                                StableReference reference,
                                                BPlusTree.ActionEstimate estimate,
                                                BPlusTree.LocalAction action) {
        OperationType relevant = action == BPlusTree.LocalAction.SLACKEN
                ? OperationType.INSERT : OperationType.RANGE_SEARCH;
        int samples = stats.latencySampleCount(relevant);
        double estimatedCost = estimate.recordsMoved() * config.estimatedRecordMoveNanos()
                + estimate.leavesTouched() * config.estimatedLeafTouchNanos();
        double safetyAdjustedCost = estimatedCost * config.costSafetyFactor();
        double regionExposure = regionExposureFraction(stats);
        if (samples < config.minSamplesPerOperationType()) {
            return CostModelEstimate.rejected(regionExposure, estimatedCost, safetyAdjustedCost);
        }

        double relevantShare = Math.max(EPS, stats.operationShare(relevant));
        double expectedRelevantOps = config.benefitHorizonOperations()
                * regionExposure * relevantShare;
        double potentialSavings;
        double benefitPerRelevantOperation;

        if (action == BPlusTree.LocalAction.COMPACT) {
            // For scan fragmentation, the predictable benefit is less leaf work per future scan.
            // Use a conservative one-region estimate and let the empirical live trial make
            // the final commit/rollback decision.
            int removedLeaves = Math.max(0, -estimate.leafDelta());
            if (removedLeaves == 0 || !Double.isFinite(stats.averageScanAmplification())) {
                return CostModelEstimate.rejected(regionExposure, estimatedCost, safetyAdjustedCost);
            }
            benefitPerRelevantOperation = removedLeaves * config.estimatedLeafTouchNanos();
            potentialSavings = expectedRelevantOps * benefitPerRelevantOperation;
        } else {
            double current = observedPercentile(stats, relevant);
            double baseline = referencePercentile(reference, relevant);
            if (!Double.isFinite(current) || !Double.isFinite(baseline) || baseline <= 0) {
                return CostModelEstimate.rejected(regionExposure, estimatedCost, safetyAdjustedCost);
            }
            benefitPerRelevantOperation = Math.max(0.0, current - baseline);
            potentialSavings = benefitPerRelevantOperation * expectedRelevantOps;
        }

        double benefitPerGlobalOperation = benefitPerRelevantOperation
                * regionExposure * relevantShare;
        double breakEvenOperations = benefitPerGlobalOperation > 0.0
                ? safetyAdjustedCost / benefitPerGlobalOperation : Double.NaN;
        return new CostModelEstimate(potentialSavings > safetyAdjustedCost, regionExposure,
                potentialSavings, estimatedCost, safetyAdjustedCost, breakEvenOperations);
    }

    /**
     * Fraction of global operations represented by this affected region. Production windows
     * carry exact one-based global operation positions. Synthetic compatibility fixtures do not,
     * so they retain the historical local-horizon interpretation.
     */
    private static double regionExposureFraction(RegionWindowStats stats) {
        long start = stats.windowStartOperation();
        long end = stats.windowEndOperation();
        if (start < 0 || end < start || stats.totalOperations() <= 0) return 1.0;
        long globalSpan = end - start + 1L;
        if (globalSpan <= 0) return 1.0;
        return Math.min(1.0, Math.max(EPS, stats.totalOperations() / (double) globalSpan));
    }

    private DiagnosisResult diagnose(RegionWindowStats stats, StableReference reference) {
        double insertLatency = positiveLogRatio(
                observedPercentile(stats, OperationType.INSERT),
                referencePercentile(reference, OperationType.INSERT));
        double writes = writeCount(stats);
        double splitDeviation = positiveLogRatio(
                splitWorkFactor(stats, writes), referenceSplitWorkFactor(reference));
        double headroomPressure = positiveLogRatio(stats.averageOccupancy(), reference.averageOccupancy());

        double scanLatency = positiveLogRatio(
                observedPercentile(stats, OperationType.RANGE_SEARCH),
                referencePercentile(reference, OperationType.RANGE_SEARCH));
        double scanAmp = positiveLogRatio(stats.averageScanAmplification(), reference.scanAmplification());
        double sparsity = positiveLogRatio(reference.averageOccupancy(), stats.averageOccupancy());

        double insertScore = weightedScore(
                config.insertLatencyWeight(), insertLatency,
                config.splitRateWeight(), splitDeviation,
                config.headroomWeight(), headroomPressure);

        double scanScore = weightedScore(
                config.scanLatencyWeight(), scanLatency,
                config.scanAmplificationWeight(), scanAmp,
                config.sparsityWeight(), sparsity);

        boolean insertStructuralEvidence = maxFinite(splitDeviation, headroomPressure)
                >= config.structuralEvidenceThreshold();
        boolean scanStructuralEvidence = maxFinite(scanAmp, sparsity)
                >= config.structuralEvidenceThreshold();

        if (!Double.isFinite(insertScore)) insertScore = 0.0;
        if (!Double.isFinite(scanScore)) scanScore = 0.0;

        Diagnosis winner = insertScore >= scanScore
                ? Diagnosis.INSERT_SPLIT_PRESSURE : Diagnosis.SCAN_FRAGMENTATION;
        double winnerScore = Math.max(insertScore, scanScore);
        double margin = Math.abs(insertScore - scanScore);
        boolean structuralEvidence = winner == Diagnosis.INSERT_SPLIT_PRESSURE
                ? insertStructuralEvidence
                : scanStructuralEvidence;
        if (winnerScore < config.diagnosisThreshold()
                || margin < config.diagnosisMargin()
                || !structuralEvidence) {
            winner = Diagnosis.NO_CONFIDENT_DIAGNOSIS;
        }
        return new DiagnosisResult(winner, insertScore, scanScore);
    }

    private double normalizedServiceCost(RegionWindowStats stats, StableReference reference) {
        double weighted = 0.0;
        double weightSum = 0.0;
        for (OperationType type : OperationType.values()) {
            int count = stats.latencySampleCount(type);
            if (count < config.minSamplesPerOperationType()) continue;
            double current = observedPercentile(stats, type);
            double baseline = referencePercentile(reference, type);
            if (!Double.isFinite(current) || !Double.isFinite(baseline) || baseline <= 0) continue;
            double share = stats.operationShare(type);
            weighted += share * (current / baseline);
            weightSum += share;
        }
        double latencyCost = weightSum <= 0 ? Double.NaN : weighted / weightSum;

        // Range scans can become structurally more expensive even when short-run nanosecond
        // latency is masked by JIT/cache noise.  Treat realized leaf-work amplification as a
        // second service-efficiency signal, while leaving cause classification to diagnose().
        double scanWorkCost = Double.NaN;
        if (stats.count(OperationType.RANGE_SEARCH) >= config.minSamplesPerOperationType()
                && Double.isFinite(stats.averageScanAmplification())
                && Double.isFinite(reference.scanAmplification())
                && reference.scanAmplification() > 0) {
            scanWorkCost = stats.averageScanAmplification() / reference.scanAmplification();
        }

        double splitWorkCost = Double.NaN;
        double writes = writeCount(stats);
        if (writes >= config.minSamplesPerOperationType()) {
            double current = splitWorkFactor(stats, writes);
            double baseline = referenceSplitWorkFactor(reference);
            if (Double.isFinite(current) && Double.isFinite(baseline) && baseline > 0) {
                splitWorkCost = current / baseline;
            }
        }

        return maxFinite(latencyCost, scanWorkCost, splitWorkCost);
    }

    /**
     * Approximate structural record-touch work caused by leaf splits. A split moves roughly
     * half a leaf of records; expressing that work per write gives a zero-baseline-safe
     * service-cost factor without changing any configured threshold.
     */
    private double splitWorkFactor(RegionWindowStats stats, double writes) {
        if (writes <= 0) return Double.NaN;
        double movedRecordsPerSplit = (tree.maxLeafKeys() + 1) / 2.0;
        return 1.0 + stats.leafSplits() * movedRecordsPerSplit / writes;
    }

    private double referenceSplitWorkFactor(StableReference reference) {
        if (!Double.isFinite(reference.splitRate())) return Double.NaN;
        double movedRecordsPerSplit = (tree.maxLeafKeys() + 1) / 2.0;
        return 1.0 + reference.splitRate() * movedRecordsPerSplit;
    }

    private static double writeCount(RegionWindowStats stats) {
        return stats.count(OperationType.INSERT)
                + stats.count(OperationType.DELETE)
                + stats.count(OperationType.UPDATE);
    }

    private TrialEvaluation evaluateTrial(TrialState trial, double drift) {
        double preCost = 0.0;
        double trialCost = 0.0;
        double weightSum = 0.0;
        int comparableSamples = 0;
        double preP99 = trial.triggerStats.overallP99Nanos();
        double trialP99 = trial.metrics.overallP99();

        for (OperationType type : OperationType.values()) {
            int trialCount = trial.metrics.count(type);
            int sampleCount = trial.metrics.latencySampleCount(type);
            if (sampleCount < config.minSamplesPerOperationType()) continue;
            double trialPercentile = type == OperationType.INSERT
                    ? trial.metrics.percentile(type, 0.99)
                    : trial.metrics.percentile(type, 0.95);
            double prePercentile = observedPercentile(trial.triggerStats, type);
            if (!Double.isFinite(prePercentile)) {
                RegionState state = regionStates.get(trial.regionId);
                if (state != null) prePercentile = referencePercentile(state.reference, type);
            }
            if (!Double.isFinite(prePercentile) || !Double.isFinite(trialPercentile) || prePercentile <= 0) continue;
            double share = trialCount / (double) trial.metrics.totalOperations();
            preCost += share * prePercentile;
            trialCost += share * trialPercentile;
            weightSum += share;
            comparableSamples += sampleCount;
        }

        if (weightSum <= 0 || preCost <= 0) {
            return new TrialEvaluation(false, false, Double.NaN, Double.NaN,
                    "insufficient comparable operation types in trial", comparableSamples,
                    Double.NaN, Double.NaN, preP99, trialP99);
        }
        preCost /= weightSum;
        trialCost /= weightSum;
        double latencyGain = (preCost - trialCost) / preCost;
        double rawGain = latencyGain;
        if (trial.diagnosis == Diagnosis.SCAN_FRAGMENTATION) {
            double preAmp = trial.triggerStats.averageScanAmplification();
            double trialAmp = trial.metrics.averageScanAmplification();
            int rangeSamples = trial.metrics.count(OperationType.RANGE_SEARCH);
            if (!Double.isFinite(preAmp) || preAmp <= 0 || !Double.isFinite(trialAmp)
                    || rangeSamples < config.minSamplesPerOperationType()) {
                return new TrialEvaluation(false, false, Double.NaN, Double.NaN,
                        "insufficient scan-amplification evidence in trial", comparableSamples,
                        preCost, trialCost, preP99, trialP99);
            }
            double scanGain = (preAmp - trialAmp) / preAmp;
            // Empirical scan validation balances observed latency with observed leaf work.
            // The tail guard below prevents a structural win from hiding a latency regression.
            rawGain = 0.5 * latencyGain + 0.5 * scanGain;
        }
        double expectedRegionOperations = Math.max(1.0,
                config.benefitHorizonOperations() * regionExposureFraction(trial.triggerStats));
        double amortizedCostPerOp = trial.actionNanos / expectedRegionOperations;
        double netGain = rawGain - (amortizedCostPerOp / preCost);

        boolean tailGuard = Double.isFinite(preP99) && Double.isFinite(trialP99)
                && trialP99 <= (1.0 + config.p99Tolerance()) * preP99;

        String reason;
        if (!tailGuard) reason = "tail-latency guard failed";
        else if (netGain < config.minimumNetGain()) reason = "minimum net-gain threshold not met";
        else reason = "accepted";
        return new TrialEvaluation(true, tailGuard, rawGain, netGain, reason,
                comparableSamples, preCost, trialCost, preP99, trialP99);
    }

    private static double observedPercentile(RegionWindowStats stats, OperationType type) {
        return type == OperationType.INSERT ? stats.p99(type) : stats.p95(type);
    }

    private static double referencePercentile(StableReference reference, OperationType type) {
        return type == OperationType.INSERT ? reference.p99(type) : reference.p95(type);
    }

    private static double maxFinite(double a, double b) {
        double max = Double.NEGATIVE_INFINITY;
        if (Double.isFinite(a)) max = Math.max(max, a);
        if (Double.isFinite(b)) max = Math.max(max, b);
        return max == Double.NEGATIVE_INFINITY ? Double.NaN : max;
    }

    private static double maxFinite(double a, double b, double c) {
        return maxFinite(maxFinite(a, b), c);
    }

    private static double operationMixDrift(RegionWindowStats pre, TrialMetrics trial) {
        int trialTotal = trial.totalOperations();
        if (pre.totalOperations() == 0 || trialTotal == 0) return 1.0;
        double sum = 0.0;
        for (OperationType type : OperationType.values()) {
            double p = pre.count(type) / (double) pre.totalOperations();
            double q = trial.count(type) / (double) trialTotal;
            sum += Math.abs(p - q);
        }
        return 0.5 * sum;
    }

    /** Records a diagnostic partial window without feeding it back into controller policy. */
    public void recordPostCommitBoundarySnapshot(RegionWindowStats stats, String boundary) {
        recordPostCommitObservation(stats, boundary);
    }

    /** Stops post-COMMIT attribution at an experiment phase boundary or run end. */
    public void endPostCommitTracking() {
        postCommitTrackingByRegion.clear();
    }

    private void recordPostCommitObservation(RegionWindowStats stats, String boundary) {
        PostCommitTracking tracking = postCommitTrackingByRegion.get(stats.regionId());
        if (tracking == null) return;
        tracking.observationCount++;
        RegionState state = regionStates.get(stats.regionId());
        double serviceCost = state == null ? Double.NaN : normalizedServiceCost(stats, state.reference);
        postCommitObservations.add(new PostCommitObservation(
                tracking.eventId,
                tracking.regionId,
                tracking.commitOperationIndex,
                tracking.observationCount,
                stats.windowSequence(),
                stats.windowStartOperation(),
                stats.windowEndOperation(),
                boundary,
                stats.totalOperations(),
                operationMix(stats),
                latencySampleMix(stats),
                stats.p95(OperationType.SEARCH),
                stats.p99(OperationType.INSERT),
                stats.p95(OperationType.DELETE),
                stats.p95(OperationType.UPDATE),
                stats.p95(OperationType.RANGE_SEARCH),
                stats.overallP99Nanos(),
                serviceCost,
                stats.averageScanAmplification(),
                stats.leafSplits(),
                stats.leafMerges(),
                stats.averageOccupancy(),
                stats.minimumOccupancy(),
                stats.leafCount(),
                stats.recordCount()
        ));
    }

    private static String operationMix(RegionWindowStats stats) {
        StringBuilder out = new StringBuilder(96);
        int total = stats.totalOperations();
        for (OperationType type : OperationType.values()) {
            appendMixValue(out, type, total == 0 ? 0.0 : stats.count(type) / (double) total);
        }
        return out.toString();
    }

    private static String operationMix(TrialMetrics metrics) {
        StringBuilder out = new StringBuilder(96);
        int total = metrics.totalOperations();
        for (OperationType type : OperationType.values()) {
            appendMixValue(out, type, total == 0 ? 0.0 : metrics.count(type) / (double) total);
        }
        return out.toString();
    }

    private static String latencySampleMix(RegionWindowStats stats) {
        int total = 0;
        for (OperationType type : OperationType.values()) total += stats.latencySampleCount(type);
        StringBuilder out = new StringBuilder(96);
        for (OperationType type : OperationType.values()) {
            appendMixValue(out, type, total == 0 ? 0.0 : stats.latencySampleCount(type) / (double) total);
        }
        return out.toString();
    }

    private static void appendMixValue(StringBuilder out, OperationType type, double value) {
        if (!out.isEmpty()) out.append(';');
        // StringBuilder's double conversion is locale-independent and avoids initializing the
        // heavyweight Formatter/regex machinery inside a measured COMMIT/ROLLBACK operation.
        out.append(type.name()).append('=').append(value);
    }

    private void logAbstention(long regionId, Diagnosis diagnosis, String reason) {
        events.add(new AdaptationEvent(
                ++eventSequence,
                regionId,
                currentOperationIndex,
                -1L,
                -1L,
                -1L,
                -1L,
                -1L,
                diagnosis,
                null,
                "ABSTAIN",
                reason,
                -1,
                -1,
                0,
                0,
                0.0,
                0.0,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                0L,
                0,
                0,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                "",
                "",
                Double.NaN,
                Double.NaN,
                Double.NaN
        ));
    }

    private String structuralSignature(long regionId) {
        BPlusTree.RegionView view = tree.region(regionId).orElse(null);
        if (view == null) return "missing";
        return view.leafCount() + ":" + view.recordCount() + ":"
                + Math.round(view.averageOccupancy() * 1000.0) + ":"
                + Math.round(view.minimumOccupancy() * 1000.0);
    }

    private static double positiveLogRatio(double numerator, double denominator) {
        if (!Double.isFinite(numerator) || !Double.isFinite(denominator)) return Double.NaN;
        if (numerator < 0 || denominator < 0) return Double.NaN;
        double ratio = (numerator + EPS) / (denominator + EPS);
        return Math.max(0.0, Math.log(ratio));
    }

    private static double weightedScore(double w1, double v1,
                                        double w2, double v2,
                                        double w3, double v3) {
        double sum = 0.0;
        double weights = 0.0;
        if (Double.isFinite(v1)) { sum += w1 * v1; weights += w1; }
        if (Double.isFinite(v2)) { sum += w2 * v2; weights += w2; }
        if (Double.isFinite(v3)) { sum += w3 * v3; weights += w3; }
        return weights == 0.0 ? Double.NaN : sum / weights;
    }

    private static final class RegionState {
        final StableReference reference = new StableReference();
        int persistentDegradation;
        int cooldownRemaining;
        double lastServiceCost = Double.NaN;
        RegionWindowStats lastWindow;
        Diagnosis lastDiagnosis = Diagnosis.NO_CONFIDENT_DIAGNOSIS;
        FailedSignature lastFailedSignature;
        long referenceStructuralEvents;
    }

    private static final class PostCommitTracking {
        final long eventId;
        final long regionId;
        final long commitOperationIndex;
        int observationCount;

        PostCommitTracking(long eventId, long regionId, long commitOperationIndex) {
            this.eventId = eventId;
            this.regionId = regionId;
            this.commitOperationIndex = commitOperationIndex;
        }
    }

    private record DiagnosisResult(Diagnosis diagnosis, double insertScore, double scanScore) {}
    private record Candidate(long regionId,
                             Diagnosis diagnosis,
                             BPlusTree.ActionEstimate estimate,
                             long triggerWindowSequence,
                             CostModelEstimate costEstimate) {}
    private record CostModelEstimate(boolean passes,
                                     double regionExposureFraction,
                                     double predictedBenefitNanos,
                                     double estimatedActionCostNanos,
                                     double safetyAdjustedCostNanos,
                                     double predictedBreakEvenOperations) {
        static CostModelEstimate rejected(double regionExposureFraction,
                                          double estimatedCostNanos,
                                          double safetyAdjustedCostNanos) {
            return new CostModelEstimate(false, regionExposureFraction, Double.NaN, estimatedCostNanos,
                    safetyAdjustedCostNanos, Double.NaN);
        }
    }
    private record FailedSignature(Diagnosis diagnosis, BPlusTree.LocalAction action, String structuralSignature) {}
    private record TrialEvaluation(boolean comparable,
                                   boolean tailGuardPassed,
                                   double rawGain,
                                   double netGain,
                                   String reason,
                                   int comparableSamples,
                                   double baselineObjectiveNanos,
                                   double candidateObjectiveNanos,
                                   double baselineP99Nanos,
                                   double candidateP99Nanos) {}

    private static final class TrialState {
        final long regionId;
        final Diagnosis diagnosis;
        final BPlusTree.LocalAction action;
        final BPlusTree.ActionEstimate estimate;
        final BPlusTree.RegionSnapshot snapshot;
        final RegionWindowStats triggerStats;
        final long actionNanos;
        final long actionOperationIndex;
        final CostModelEstimate costEstimate;
        final TrialMetrics metrics;
        final List<BPlusTree.Mutation> mutations;
        long trialStartOperation = -1L;
        long trialEndOperation = -1L;
        TrialEvaluation lastEvaluation;

        TrialState(long regionId,
                   Diagnosis diagnosis,
                   BPlusTree.LocalAction action,
                   BPlusTree.ActionEstimate estimate,
                   BPlusTree.RegionSnapshot snapshot,
                   RegionWindowStats triggerStats,
                   long actionNanos,
                   long actionOperationIndex,
                   CostModelEstimate costEstimate,
                   TrialMetrics metrics,
                   List<BPlusTree.Mutation> mutations) {
            this.regionId = regionId;
            this.diagnosis = diagnosis;
            this.action = action;
            this.estimate = estimate;
            this.snapshot = snapshot;
            this.triggerStats = triggerStats;
            this.actionNanos = actionNanos;
            this.actionOperationIndex = actionOperationIndex;
            this.costEstimate = costEstimate;
            this.metrics = metrics;
            this.mutations = mutations;
        }
    }

    private static final class TrialMetrics {
        private static final OperationType[] TYPES = OperationType.values();
        private final SortedLongBuffer[] latencies = new SortedLongBuffer[TYPES.length];
        private final SortedLongBuffer all = new SortedLongBuffer(128);
        private final int[] counts = new int[TYPES.length];
        private final int[] latencySampleCounts = new int[TYPES.length];
        private int totalOperations;
        private double scanAmplificationSum;
        private int scanSamples;

        TrialMetrics() {
            for (OperationType type : TYPES) latencies[type.ordinal()] = new SortedLongBuffer(32);
        }

        void record(OperationType type, long latencyNanos, double scanAmplification) {
            int ordinal = type.ordinal();
            counts[ordinal]++;
            totalOperations++;
            if (latencyNanos >= 0L) {
                latencies[ordinal].add(latencyNanos);
                latencySampleCounts[ordinal]++;
                all.add(latencyNanos);
            }
            if (type == OperationType.RANGE_SEARCH && Double.isFinite(scanAmplification)) {
                scanAmplificationSum += scanAmplification;
                scanSamples++;
            }
        }

        int totalOperations() { return totalOperations; }
        int count(OperationType type) { return counts[type.ordinal()]; }
        int latencySampleCount(OperationType type) {
            return latencySampleCounts[type.ordinal()];
        }

        double percentile(OperationType type, double p) {
            return latencies[type.ordinal()].percentile(p);
        }

        double overallP99() {
            return all.percentile(0.99);
        }

        double averageScanAmplification() {
            return scanSamples == 0 ? Double.NaN : scanAmplificationSum / scanSamples;
        }

        /** Primitive incrementally sorted samples: exact nearest-rank percentiles, no boxing. */
        private static final class SortedLongBuffer {
            private long[] values;
            private int size;

            SortedLongBuffer(int initialCapacity) {
                values = new long[Math.max(8, initialCapacity)];
            }

            void add(long value) {
                if (size == values.length) values = Arrays.copyOf(values, values.length << 1);
                int position = Arrays.binarySearch(values, 0, size, value);
                if (position < 0) position = -position - 1;
                System.arraycopy(values, position, values, position + 1, size - position);
                values[position] = value;
                size++;
            }

            double percentile(double p) {
                if (size == 0) return Double.NaN;
                int index = (int) Math.ceil(p * size) - 1;
                index = Math.max(0, Math.min(size - 1, index));
                return values[index];
            }
        }
    }
}
