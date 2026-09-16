package edu.jouf.larsbplus.experiment;

import edu.jouf.larsbplus.core.BPlusTree;
import edu.jouf.larsbplus.lars.*;

import java.util.EnumMap;

/** Deterministic self-check for the observability-only COMMIT instrumentation. */
public final class ObservabilitySelfCheck {
    private ObservabilitySelfCheck() {}

    public static void main(String[] args) {
        BPlusTree tree = new BPlusTree(8, 8);
        for (int i = 0; i < 2_000; i++) tree.insert(i, i);
        long regionId = findSlackenRegion(tree);
        LarsConfig config = permissiveConfig();
        LarsController controller = new LarsController(tree, config);
        BPlusTree.RegionView view = tree.region(regionId).orElseThrow();

        controller.onObservationWindow(stats(regionId, 1, view, 1_000, 0), 64);
        controller.onObservationWindow(stats(regionId, 2, view, 1_000, 0), 128);
        controller.onObservationWindow(stats(regionId, 3, view, 5_000, 20), 192);
        controller.onObservationWindow(stats(regionId, 4, view, 5_000, 20), 256);
        require(controller.hasActiveTrial(), "trial did not start");

        for (int i = 0; i < config.trialOperations(); i++) {
            controller.onTrialOperation(regionId, OperationType.INSERT, 100,
                    Double.NaN, null, 257L + i);
        }

        AdaptationEvent event = controller.events().get(controller.events().size() - 1);
        require("COMMIT".equals(event.outcome()), "expected COMMIT");
        require(event.regionId() == regionId, "region id mismatch");
        require(event.actionOperationIndex() == 256, "action operation mismatch");
        require(event.trialStartOperation() == 257, "trial start mismatch");
        require(event.trialEndOperation() == 288, "trial end mismatch");
        require(event.eventOperationIndex() == 288, "event operation mismatch");
        require(event.commitOperationIndex() == 288, "commit operation mismatch");
        require(event.trialOperations() == 32, "trial count mismatch");
        require(event.comparableTrialSamples() == 32, "comparable sample count mismatch");
        require(Double.isFinite(event.baselineObjectiveNanos()), "missing baseline objective");
        require(Double.isFinite(event.candidateObjectiveNanos()), "missing candidate objective");
        require(Double.isFinite(event.predictedBenefitNanos()), "missing predicted benefit");
        require(Double.isFinite(event.estimatedActionCostNanos()), "missing estimated cost");
        require(Double.isFinite(event.predictedBreakEvenOperations()), "missing break-even estimate");
        require(!event.baselineOperationMix().isBlank(), "missing baseline operation mix");
        require(!event.trialOperationMix().isBlank(), "missing trial operation mix");

        controller.onObservationWindow(stats(regionId, 5, tree.region(regionId).orElseThrow(), 900, 0), 352);
        require(controller.postCommitObservations().size() == 1, "missing post-COMMIT snapshot");
        PostCommitObservation observation = controller.postCommitObservations().get(0);
        require(observation.eventId() == event.sequence(), "snapshot event link mismatch");
        require(observation.regionId() == regionId, "snapshot region link mismatch");
        require(observation.commitOperationIndex() == 288, "snapshot commit operation mismatch");

        tree.validate();
        System.out.println("OBSERVABILITY_SELF_CHECK_OK");
    }

    private static long findSlackenRegion(BPlusTree tree) {
        for (BPlusTree.RegionView region : tree.regions()) {
            for (int width = 2; width <= Math.min(6, region.leafCount()); width++) {
                for (int from = 0; from + width <= region.leafCount(); from++) {
                    if (tree.estimateAction(BPlusTree.LocalAction.SLACKEN, region.regionId(),
                            from, from + width - 1).feasible()) return region.regionId();
                }
            }
        }
        throw new IllegalStateException("no SLACKEN-capable region");
    }

    private static LarsConfig permissiveConfig() {
        LarsConfig d = LarsConfig.pilotDefaults();
        return new LarsConfig(
                64, 2, 2, 5, 1, 0.2,
                0.10, 0.10, 0.01,
                2, 6, 1.0, 1.0,
                100_000, 1.0, 1.0, 1.0,
                32, 64, 0.02, 1.0, 1.0,
                1, 0.01,
                d.insertLatencyWeight(), d.splitRateWeight(), d.headroomWeight(),
                d.scanLatencyWeight(), d.scanAmplificationWeight(), d.sparsityWeight());
    }

    private static RegionWindowStats stats(long regionId,
                                           long sequence,
                                           BPlusTree.RegionView view,
                                           double insertP95,
                                           long splits) {
        EnumMap<OperationType, Integer> counts = new EnumMap<>(OperationType.class);
        EnumMap<OperationType, Double> p50 = new EnumMap<>(OperationType.class);
        EnumMap<OperationType, Double> p95 = new EnumMap<>(OperationType.class);
        EnumMap<OperationType, Double> p99 = new EnumMap<>(OperationType.class);
        for (OperationType type : OperationType.values()) counts.put(type, 0);
        counts.put(OperationType.INSERT, 64);
        p50.put(OperationType.INSERT, insertP95 * 0.7);
        p95.put(OperationType.INSERT, insertP95);
        p99.put(OperationType.INSERT, insertP95 * 1.1);
        return new RegionWindowStats(regionId, sequence, 64, counts, counts, p50, p95, p99,
                insertP95 * 1.1, 0.05, splits, 0,
                view.averageOccupancy(), view.minimumOccupancy(), view.leafCount(), view.recordCount());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
