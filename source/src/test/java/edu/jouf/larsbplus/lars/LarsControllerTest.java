package edu.jouf.larsbplus.lars;

import edu.jouf.larsbplus.core.BPlusTree;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LarsControllerTest {

    @Test
    void regionExposureUsesExactGlobalOperationSpan() throws Exception {
        int n = OperationType.values().length;
        int[] counts = new int[n];
        int[] samples = new int[n];
        double[] p50 = new double[n];
        double[] p95 = new double[n];
        double[] p99 = new double[n];
        counts[OperationType.INSERT.ordinal()] = 100;
        samples[OperationType.INSERT.ordinal()] = 13;
        RegionWindowStats stats = new RegionWindowStats(1L, 1L, 100L, 1_099L,
                100, counts, samples, p50, p95, p99, 1_000.0, Double.NaN,
                0L, 0L, 0.75, 0.5, 4, 192);
        Method method = LarsController.class.getDeclaredMethod(
                "regionExposureFraction", RegionWindowStats.class);
        method.setAccessible(true);
        assertEquals(0.1, (double) method.invoke(null, stats), 1e-12);
    }

    @Test
    void syntheticInsertPressureStartsTrialAndBadTrialRollsBack() {
        BPlusTree tree = new BPlusTree(8, 8);
        for (int i = 0; i < 2_000; i++) tree.insert(i, i);
        long regionId = findSlackenRegion(tree);

        LarsConfig c = permissiveTestConfig();
        LarsController controller = new LarsController(tree, c);
        BPlusTree.RegionView view = tree.region(regionId).orElseThrow();

        for (int i = 0; i < c.referenceWarmupWindows(); i++) {
            controller.onObservationWindow(stats(regionId, i + 1, view, 1_000, 0, 0.05));
        }
        for (int i = 0; i < c.persistenceWindows(); i++) {
            controller.onObservationWindow(stats(regionId, 100 + i, view, 5_000, 20, 0.05));
        }

        assertTrue(controller.hasActiveTrial(), "degraded windows should start a trial");
        for (int i = 0; i < c.trialOperations(); i++) {
            controller.onTrialOperation(regionId, OperationType.INSERT, 8_000, Double.NaN, null);
        }

        assertFalse(controller.hasActiveTrial());
        assertEquals("ROLLBACK", controller.events().get(controller.events().size() - 1).outcome());
        tree.validate();
    }

    @Test
    void unsampledTrialOperationsExtendUntilComparableLatencyExists() {
        BPlusTree tree = new BPlusTree(8, 8);
        for (int i = 0; i < 2_000; i++) tree.insert(i, i);
        long regionId = findSlackenRegion(tree);
        LarsConfig c = permissiveTestConfig();
        LarsController controller = new LarsController(tree, c);
        BPlusTree.RegionView view = tree.region(regionId).orElseThrow();
        startInsertTrial(controller, c, regionId, view);

        for (int i = 0; i < c.trialOperations(); i++) {
            controller.onTrialOperation(regionId, OperationType.INSERT, -1L, Double.NaN, null, i + 1L);
        }
        assertTrue(controller.hasActiveTrial(),
                "operation counts must not be mistaken for latency-sample counts");

        for (int i = 0; i < c.minSamplesPerOperationType(); i++) {
            controller.onTrialOperation(regionId, OperationType.INSERT, 8_000L,
                    Double.NaN, null, c.trialOperations() + i + 1L);
        }
        assertFalse(controller.hasActiveTrial());
        AdaptationEvent event = controller.events().get(controller.events().size() - 1);
        assertEquals("ROLLBACK", event.outcome());
        assertEquals(c.minSamplesPerOperationType(), event.comparableTrialSamples());
    }

    @Test
    void primitiveTrialBufferPreservesExactNearestRankPercentiles() {
        BPlusTree tree = new BPlusTree(8, 8);
        for (int i = 0; i < 2_000; i++) tree.insert(i, i);
        long regionId = findSlackenRegion(tree);
        LarsConfig c = permissiveTestConfig();
        LarsController controller = new LarsController(tree, c);
        startInsertTrial(controller, c, regionId, tree.region(regionId).orElseThrow());

        for (int i = 0; i < c.trialOperations(); i++) {
            long shuffledLatency = 1_000L + (i * 17L) % c.trialOperations();
            controller.onTrialOperation(regionId, OperationType.INSERT, shuffledLatency,
                    Double.NaN, null, i + 1L);
        }

        AdaptationEvent event = controller.events().get(controller.events().size() - 1);
        assertEquals("COMMIT", event.outcome());
        assertEquals(1_031.0, event.candidateObjectiveNanos());
        assertEquals(1_031.0, event.candidateP99Nanos());
        assertEquals(c.trialOperations(), event.comparableTrialSamples());
    }

    @Test
    void runBoundaryRollsBackCensoredFullPolicyTrialAndEmitsTerminalEvent() {
        BPlusTree tree = new BPlusTree(8, 8);
        for (int i = 0; i < 2_000; i++) tree.insert(i, i);
        long originalLeaves = tree.leafCount();
        var originalEntries = tree.snapshotEntries();
        long regionId = findSlackenRegion(tree);
        LarsConfig c = permissiveTestConfig();
        LarsController controller = new LarsController(tree, c);
        BPlusTree.RegionView view = tree.region(regionId).orElseThrow();
        startInsertTrial(controller, c, regionId, view);

        assertTrue(controller.hasActiveTrial());
        controller.endActiveTrialAtBoundary("RUN_END", 123L);

        assertFalse(controller.hasActiveTrial());
        AdaptationEvent event = controller.events().get(controller.events().size() - 1);
        assertEquals("ROLLBACK", event.outcome());
        assertEquals(123L, event.eventOperationIndex());
        assertTrue(event.reason().contains("RUN_END"));
        assertEquals(originalLeaves, tree.leafCount());
        assertEquals(originalEntries, tree.snapshotEntries());
        tree.validate();
    }

    private static void startInsertTrial(LarsController controller,
                                         LarsConfig config,
                                         long regionId,
                                         BPlusTree.RegionView view) {
        for (int i = 0; i < config.referenceWarmupWindows(); i++) {
            controller.onObservationWindow(stats(regionId, i + 1, view, 1_000, 0, 0.05));
        }
        for (int i = 0; i < config.persistenceWindows(); i++) {
            controller.onObservationWindow(stats(regionId, 100 + i, view, 5_000, 20, 0.05));
        }
        assertTrue(controller.hasActiveTrial());
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
        throw new AssertionError("no SLACKEN-capable region");
    }

    private static LarsConfig permissiveTestConfig() {
        LarsConfig d = LarsConfig.pilotDefaults();
        return new LarsConfig(
                64, 2, 2, 5, 1, 0.2,
                0.10, 0.10, 0.01,
                2, 6, 1.0, 1.0,
                100_000, 1.0, 1.0, 1.0,
                32, 64, 0.02, 1.0, 1.0,
                1, 0.01,
                d.insertLatencyWeight(), d.splitRateWeight(), d.headroomWeight(),
                d.scanLatencyWeight(), d.scanAmplificationWeight(), d.sparsityWeight()
        );
    }

    private static RegionWindowStats stats(long regionId,
                                           long seq,
                                           BPlusTree.RegionView view,
                                           double insertP95,
                                           long splits,
                                           double scanAmp) {
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
                insertP95 * 1.1, scanAmp, splits, 0,
                view.averageOccupancy(), view.minimumOccupancy(), view.leafCount(), view.recordCount());
    }
}
