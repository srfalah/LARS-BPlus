package edu.jouf.larsbplus.experiment;

import edu.jouf.larsbplus.core.BPlusTree;
import edu.jouf.larsbplus.lars.*;

import java.util.EnumMap;

/**
 * No-dependency checks for the diagnostic-calibration boundary.
 *
 * <p>It verifies two properties:</p>
 * <ol>
 *   <li>a clean monitor-only reference can be frozen and then used to trigger diagnosis;</li>
 *   <li>a reference phase that changes structure is rejected rather than silently frozen.</li>
 * </ol>
 */
public final class CalibrationSelfCheck {
    private CalibrationSelfCheck() {}

    public static void main(String[] args) {
        cleanReferenceThenGroundTruth();
        structurallyContaminatedReferenceIsRejected();
        System.out.println("CALIBRATION_SELF_CHECK_OK");
    }

    private static void cleanReferenceThenGroundTruth() {
        BPlusTree tree = denseTree();
        long regionId = findSlackenRegion(tree);
        BPlusTree.RegionView view = tree.region(regionId).orElseThrow();
        LarsController controller = new LarsController(tree, testConfig());

        controller.beginReferenceCalibration();
        require(controller.controlMode() == LarsController.ControlMode.MONITOR_ONLY,
                "reference phase must be monitor-only");

        controller.onObservationWindow(insertStats(regionId, 1, view, 1_000, 1_050, 0));
        controller.onObservationWindow(insertStats(regionId, 2, view, 1_000, 1_050, 0));

        require(controller.referenceReady(regionId), "reference should be ready");
        require(controller.referenceStructurallyStable(regionId),
                "clean reference must remain structurally stable");
        require(!controller.hasActiveTrial(), "monitor-only phase must never start a trial");

        controller.beginGroundTruthCalibration(regionId);
        require(controller.controlMode() == LarsController.ControlMode.FROZEN_REFERENCE,
                "ground-truth phase must freeze reference learning");

        controller.onObservationWindow(insertStats(regionId, 3, view, 5_000, 9_000, 16));
        controller.onObservationWindow(insertStats(regionId, 4, view, 5_000, 9_000, 16));

        require(controller.hasActiveTrial(),
                "persistent insert/split degradation should start a trial after reference freeze");
    }

    private static void structurallyContaminatedReferenceIsRejected() {
        BPlusTree tree = denseTree();
        long regionId = findSlackenRegion(tree);
        BPlusTree.RegionView view = tree.region(regionId).orElseThrow();
        LarsController controller = new LarsController(tree, testConfig());

        controller.beginReferenceCalibration();
        controller.onObservationWindow(insertStats(regionId, 1, view, 1_000, 1_050, 1));
        controller.onObservationWindow(insertStats(regionId, 2, view, 1_000, 1_050, 1));
        require(controller.referenceReady(regionId), "reference should be numerically ready");
        require(!controller.referenceStructurallyStable(regionId),
                "structural churn must mark reference as contaminated");

        boolean rejected = false;
        try {
            controller.beginGroundTruthCalibration(regionId);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        require(rejected, "contaminated reference must not be frozen for ground truth");
    }

    private static BPlusTree denseTree() {
        BPlusTree tree = new BPlusTree(8, 8);
        for (int i = 0; i < 2_000; i++) tree.insert(i, i);
        tree.validate();
        return tree;
    }

    private static long findSlackenRegion(BPlusTree tree) {
        for (BPlusTree.RegionView region : tree.regions()) {
            for (int width = 2; width <= Math.min(6, region.leafCount()); width++) {
                for (int from = 0; from + width <= region.leafCount(); from++) {
                    BPlusTree.ActionEstimate estimate = tree.estimateAction(
                            BPlusTree.LocalAction.SLACKEN,
                            region.regionId(),
                            from,
                            from + width - 1);
                    if (estimate.feasible()) return region.regionId();
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
                32, 64, 0.02, 1.0, 1.0,
                1, 0.01,
                d.insertLatencyWeight(), d.splitRateWeight(), d.headroomWeight(),
                d.scanLatencyWeight(), d.scanAmplificationWeight(), d.sparsityWeight());
    }

    private static RegionWindowStats insertStats(long regionId,
                                                  long sequence,
                                                  BPlusTree.RegionView view,
                                                  double p95Insert,
                                                  double p99Insert,
                                                  long splits) {
        EnumMap<OperationType, Integer> counts = new EnumMap<>(OperationType.class);
        EnumMap<OperationType, Double> p50 = new EnumMap<>(OperationType.class);
        EnumMap<OperationType, Double> p95 = new EnumMap<>(OperationType.class);
        EnumMap<OperationType, Double> p99 = new EnumMap<>(OperationType.class);
        for (OperationType type : OperationType.values()) counts.put(type, 0);
        counts.put(OperationType.INSERT, 64);
        p50.put(OperationType.INSERT, p95Insert * 0.70);
        p95.put(OperationType.INSERT, p95Insert);
        p99.put(OperationType.INSERT, p99Insert);
        return new RegionWindowStats(
                regionId,
                sequence,
                64,
                counts,
                counts,
                p50,
                p95,
                p99,
                p99Insert,
                Double.NaN,
                splits,
                0,
                view.averageOccupancy(),
                view.minimumOccupancy(),
                view.leafCount(),
                view.recordCount());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
