package edu.jouf.larsbplus.lars;

/**
 * Immutable snapshot of one affected-region observation window after a COMMIT.
 *
 * <p>The snapshot is diagnostic telemetry only. It is never consulted by the
 * controller and therefore cannot change an adaptation decision.</p>
 */
public record PostCommitObservation(
        long eventId,
        long regionId,
        long commitOperationIndex,
        int observationIndexAfterCommit,
        long regionWindowSequence,
        long windowStartOperation,
        long windowEndOperation,
        String boundary,
        int totalOperations,
        String operationMix,
        String latencySampleMix,
        double searchP95Nanos,
        double insertP99Nanos,
        double deleteP95Nanos,
        double updateP95Nanos,
        double rangeP95Nanos,
        double overallP99Nanos,
        double normalizedServiceCost,
        double averageScanAmplification,
        long leafSplits,
        long leafMerges,
        double averageOccupancy,
        double minimumOccupancy,
        int leafCount,
        int recordCount
) {}
