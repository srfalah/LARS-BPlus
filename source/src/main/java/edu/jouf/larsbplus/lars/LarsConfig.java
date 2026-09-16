package edu.jouf.larsbplus.lars;

/**
 * Tunable research parameters. Defaults are pilot values only; they are not claimed to be optimal
 * and should be calibrated, documented, then frozen before the primary experiment campaign.
 */
public record LarsConfig(
        int observationOperationsPerRegion,
        int persistenceWindows,
        int referenceWarmupWindows,
        int minSamplesPerOperationType,
        int latencySampleStride,
        double stableReferenceAlpha,
        double degradationThreshold,
        double diagnosisThreshold,
        double diagnosisMargin,
        int minRepairLeaves,
        int maxRepairLeaves,
        double maxMovedRecordFraction,
        double maxTouchedLeafFraction,
        int benefitHorizonOperations,
        double estimatedRecordMoveNanos,
        double estimatedLeafTouchNanos,
        double costSafetyFactor,
        int trialOperations,
        int maxTrialOperations,
        double minimumNetGain,
        double p99Tolerance,
        double maxOperationMixDrift,
        int cooldownWindows,
        double structuralEvidenceThreshold,
        double insertLatencyWeight,
        double splitRateWeight,
        double headroomWeight,
        double scanLatencyWeight,
        double scanAmplificationWeight,
        double sparsityWeight
) {
    public LarsConfig {
        if (observationOperationsPerRegion < 64) throw new IllegalArgumentException("observationOperationsPerRegion too small");
        if (persistenceWindows < 1) throw new IllegalArgumentException("persistenceWindows must be >= 1");
        if (referenceWarmupWindows < 1) throw new IllegalArgumentException("referenceWarmupWindows must be >= 1");
        if (minSamplesPerOperationType < 1) throw new IllegalArgumentException("minSamplesPerOperationType must be >= 1");
        if (latencySampleStride < 1) throw new IllegalArgumentException("latencySampleStride must be >= 1");
        if (!(stableReferenceAlpha > 0 && stableReferenceAlpha <= 1)) throw new IllegalArgumentException("stableReferenceAlpha must be in (0,1]");
        if (degradationThreshold < 0) throw new IllegalArgumentException("degradationThreshold must be >= 0");
        if (diagnosisThreshold < 0) throw new IllegalArgumentException("diagnosisThreshold must be >= 0");
        if (diagnosisMargin < 0) throw new IllegalArgumentException("diagnosisMargin must be >= 0");
        if (minRepairLeaves < 2 || maxRepairLeaves < minRepairLeaves) throw new IllegalArgumentException("invalid repair-window bounds");
        if (maxMovedRecordFraction <= 0 || maxMovedRecordFraction > 1) throw new IllegalArgumentException("maxMovedRecordFraction must be in (0,1]");
        if (maxTouchedLeafFraction <= 0 || maxTouchedLeafFraction > 1) throw new IllegalArgumentException("maxTouchedLeafFraction must be in (0,1]");
        if (benefitHorizonOperations < 1) throw new IllegalArgumentException("benefitHorizonOperations must be >= 1");
        if (estimatedRecordMoveNanos < 0 || estimatedLeafTouchNanos < 0) throw new IllegalArgumentException("estimated costs must be >= 0");
        if (costSafetyFactor < 1.0) throw new IllegalArgumentException("costSafetyFactor should be >= 1");
        if (trialOperations < 32 || maxTrialOperations < trialOperations) throw new IllegalArgumentException("invalid trial bounds");
        if (minimumNetGain < 0) throw new IllegalArgumentException("minimumNetGain must be >= 0");
        if (p99Tolerance < 0) throw new IllegalArgumentException("p99Tolerance must be >= 0");
        if (maxOperationMixDrift < 0 || maxOperationMixDrift > 1) throw new IllegalArgumentException("maxOperationMixDrift must be in [0,1]");
        if (cooldownWindows < 0) throw new IllegalArgumentException("cooldownWindows must be >= 0");
        if (structuralEvidenceThreshold < 0) throw new IllegalArgumentException("structuralEvidenceThreshold must be >= 0");
    }

    public static LarsConfig pilotDefaults() {
        return new LarsConfig(
                1_024,
                2,
                2,
                16,
                8,
                0.10,
                0.12,
                0.15,
                0.03,
                2,
                6,
                0.05,
                0.05,
                20_000,
                40.0,
                250.0,
                1.15,
                512,
                1_536,
                0.03,
                0.10,
                0.30,
                2,
                0.05,
                0.45,
                0.35,
                0.20,
                0.25,
                0.55,
                0.20
        );
    }
}
