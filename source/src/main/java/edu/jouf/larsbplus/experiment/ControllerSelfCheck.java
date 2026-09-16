package edu.jouf.larsbplus.experiment;

import edu.jouf.larsbplus.core.BPlusTree;
import edu.jouf.larsbplus.lars.*;

import java.util.EnumMap;

/** Deterministic controller-level self-check using synthetic observation windows. */
public final class ControllerSelfCheck {
    private ControllerSelfCheck() {}

    public static void main(String[] args) {
        BPlusTree tree = new BPlusTree(8, 8);
        for (int i = 0; i < 2_000; i++) tree.insert(i, i);
        long regionId = findSlackenRegion(tree);
        LarsConfig c = testConfig();
        LarsController controller = new LarsController(tree, c);
        BPlusTree.RegionView view = tree.region(regionId).orElseThrow();

        controller.onObservationWindow(stats(regionId, 1, view, 1_000, 0));
        controller.onObservationWindow(stats(regionId, 2, view, 1_000, 0));
        controller.onObservationWindow(stats(regionId, 3, view, 5_000, 20));
        controller.onObservationWindow(stats(regionId, 4, view, 5_000, 20));
        require(controller.hasActiveTrial(), "expected active trial after persistent degradation");

        for (int i = 0; i < c.trialOperations(); i++) {
            controller.onTrialOperation(regionId, OperationType.INSERT, 8_000, Double.NaN, null);
        }
        require(!controller.hasActiveTrial(), "trial should have ended");
        require(controller.events().stream().anyMatch(e -> "ROLLBACK".equals(e.outcome())),
                "expected rollback event");
        tree.validate();
        System.out.println("CONTROLLER_SELF_CHECK_OK");
    }

    private static long findSlackenRegion(BPlusTree tree) {
        for (BPlusTree.RegionView region : tree.regions()) {
            for (int width = 2; width <= Math.min(6, region.leafCount()); width++) {
                for (int from = 0; from + width <= region.leafCount(); from++) {
                    if (tree.estimateAction(BPlusTree.LocalAction.SLACKEN, region.regionId(), from, from + width - 1).feasible()) {
                        return region.regionId();
                    }
                }
            }
        }
        throw new IllegalStateException("No feasible SLACKEN region found");
    }

    private static LarsConfig testConfig() {
        LarsConfig d = LarsConfig.pilotDefaults();
        return new LarsConfig(
                64, 2, 2, 5, 1, 0.2,
                0.10, 0.10, 0.01,
                2, 6, 1.0, 1.0,
                100_000, 1.0, 1.0, 1.0,
                32, 64, 0.02, 1.0, 1.0, 1, 0.01,
                d.insertLatencyWeight(), d.splitRateWeight(), d.headroomWeight(),
                d.scanLatencyWeight(), d.scanAmplificationWeight(), d.sparsityWeight());
    }

    private static RegionWindowStats stats(long regionId,
                                           long seq,
                                           BPlusTree.RegionView view,
                                           double insertP95,
                                           long splits) {
        EnumMap<OperationType, Integer> counts = new EnumMap<>(OperationType.class);
        EnumMap<OperationType, Double> p50 = new EnumMap<>(OperationType.class);
        EnumMap<OperationType, Double> p95 = new EnumMap<>(OperationType.class);
        EnumMap<OperationType, Double> p99 = new EnumMap<>(OperationType.class);
        for (OperationType t : OperationType.values()) counts.put(t, 0);
        counts.put(OperationType.INSERT, 64);
        p50.put(OperationType.INSERT, insertP95 * 0.7);
        p95.put(OperationType.INSERT, insertP95);
        p99.put(OperationType.INSERT, insertP95 * 1.1);
        return new RegionWindowStats(regionId, seq, 64, counts, counts, p50, p95, p99,
                insertP95 * 1.1, Double.NaN, splits, 0,
                view.averageOccupancy(), view.minimumOccupancy(), view.leafCount(), view.recordCount());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
