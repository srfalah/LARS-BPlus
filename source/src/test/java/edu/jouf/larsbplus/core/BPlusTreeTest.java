package edu.jouf.larsbplus.core;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BPlusTreeTest {

    @Test
    void randomizedOperationsMatchTreeMap() {
        BPlusTree tree = new BPlusTree(8, 8);
        TreeMap<Long, Long> oracle = new TreeMap<>();
        Random rnd = new Random(20260907L);

        for (int i = 0; i < 25_000; i++) {
            long key = rnd.nextInt(6_000);
            long value = rnd.nextLong();
            switch (rnd.nextInt(5)) {
                case 0 -> assertEquals(oracle.putIfAbsent(key, value) == null, tree.insert(key, value));
                case 1 -> assertEquals(oracle.remove(key) != null, tree.delete(key));
                case 2 -> {
                    boolean exists = oracle.containsKey(key);
                    if (exists) oracle.put(key, value);
                    assertEquals(exists, tree.update(key, value));
                }
                case 3 -> {
                    OptionalLong actual = tree.search(key);
                    Long expected = oracle.get(key);
                    assertEquals(expected != null, actual.isPresent());
                    if (expected != null) assertEquals(expected.longValue(), actual.getAsLong());
                }
                case 4 -> {
                    long end = key + rnd.nextInt(200);
                    List<BPlusTree.Entry> actual = tree.rangeSearch(key, end);
                    List<Map.Entry<Long, Long>> expected = new ArrayList<>(oracle.subMap(key, true, end, true).entrySet());
                    assertEquals(expected.size(), actual.size());
                    for (int j = 0; j < expected.size(); j++) {
                        assertEquals(expected.get(j).getKey().longValue(), actual.get(j).key());
                        assertEquals(expected.get(j).getValue().longValue(), actual.get(j).value());
                    }
                }
            }
            if ((i & 127) == 0) tree.validate();
        }

        tree.validate();
        assertEquals(oracle.size(), tree.size());
    }

    @Test
    void reusableRangeTraceMatchesPublicTraceAndResets() {
        BPlusTree tree = denseTree();
        BPlusTree.RangeTrace trace = new BPlusTree.RangeTrace();
        BPlusTree.RangeResult expected = tree.rangeSearchWithTrace(137, 2_431);
        List<BPlusTree.Entry> actual = tree.rangeSearchWithTrace(137, 2_431, trace);

        assertEquals(expected.entries(), actual);
        assertEquals(expected.leavesVisited(), trace.totalLeavesVisited());
        assertEquals(expected.leavesVisitedByRegion().size(), trace.regionCount());
        for (int i = 0; i < trace.regionCount(); i++) {
            long regionId = trace.regionId(i);
            assertEquals(expected.leavesVisitedByRegion().get(regionId), trace.leavesVisited(i));
            assertEquals(expected.resultsByRegion().getOrDefault(regionId, 0), trace.results(i));
        }

        assertEquals(List.of(), tree.rangeSearchWithTrace(10, 9, trace));
        assertEquals(0, trace.regionCount());
        assertEquals(0, trace.totalLeavesVisited());
    }

    @Test
    void slackenPreservesLogicalContents() {
        BPlusTree tree = denseTree();
        BPlusTree.ActionEstimate estimate = findFeasible(tree, BPlusTree.LocalAction.SLACKEN);
        assertNotNull(estimate);
        List<BPlusTree.Entry> before = tree.snapshotEntries();
        tree.applyAction(estimate);
        tree.validate();
        assertEquals(before, tree.snapshotEntries());
    }

    @Test
    void compactPreservesLogicalContentsWhenFeasible() {
        BPlusTree tree = denseTree();
        BPlusTree.ActionEstimate estimate = findFeasible(tree, BPlusTree.LocalAction.COMPACT);
        assertNotNull(estimate);
        List<BPlusTree.Entry> before = tree.snapshotEntries();
        tree.applyAction(estimate);
        tree.validate();
        assertEquals(before, tree.snapshotEntries());
        assertTrue(estimate.leafDelta() < 0);
    }

    @Test
    void movedRecordEstimateMatchesLiteralRedistributionForEveryFeasibleWindow() {
        BPlusTree tree = denseTree();
        int checked = 0;
        for (BPlusTree.RegionView region : tree.regions()) {
            int maxWidth = Math.min(6, region.leafCount());
            for (BPlusTree.LocalAction action : BPlusTree.LocalAction.values()) {
                for (int width = 2; width <= maxWidth; width++) {
                    for (int from = 0; from + width <= region.leafCount(); from++) {
                        int to = from + width - 1;
                        BPlusTree.ActionEstimate estimate = tree.estimateAction(
                                action, region.regionId(), from, to);
                        if (!estimate.feasible()) continue;
                        assertEquals(literalMovedRecords(region, from, to,
                                        width + estimate.leafDelta()),
                                estimate.recordsMoved(),
                                action + " region=" + region.regionId()
                                        + " window=" + from + ".." + to);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 0, "fixture must exercise feasible repair windows");
    }

    @Test
    void regionalRollbackAndReplayPreserveTrialMutation() {
        BPlusTree tree = denseTree();
        BPlusTree.ActionEstimate estimate = findFeasible(tree, BPlusTree.LocalAction.SLACKEN);
        assertNotNull(estimate);
        long regionId = estimate.regionId();
        BPlusTree.RegionSnapshot snapshot = tree.snapshotRegion(regionId);
        tree.applyAction(estimate);

        long key = tree.region(regionId).orElseThrow().firstKey();
        BPlusTree.UpdateMutation mutation = new BPlusTree.UpdateMutation(key, 123456789L);
        assertTrue(tree.canApplyMutationWithinRegion(regionId, mutation));
        assertTrue(tree.update(key, 123456789L));

        tree.restoreRegion(snapshot);
        tree.replayMutations(List.of(mutation));
        tree.validate();
        assertEquals(123456789L, tree.search(key).orElseThrow());
    }

    private static BPlusTree denseTree() {
        BPlusTree tree = new BPlusTree(8, 8);
        for (int i = 0; i < 3_000; i++) assertTrue(tree.insert(i, i));
        tree.validate();
        return tree;
    }

    private static BPlusTree.ActionEstimate findFeasible(BPlusTree tree, BPlusTree.LocalAction action) {
        for (BPlusTree.RegionView region : tree.regions()) {
            for (int width = 2; width <= Math.min(6, region.leafCount()); width++) {
                for (int from = 0; from + width <= region.leafCount(); from++) {
                    BPlusTree.ActionEstimate e = tree.estimateAction(action, region.regionId(), from, from + width - 1);
                    if (e.feasible()) return e;
                }
            }
        }
        return null;
    }

    private static int literalMovedRecords(BPlusTree.RegionView region,
                                           int from,
                                           int to,
                                           int targetLeafCount) {
        List<Long> origins = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            BPlusTree.LeafView leaf = region.leaves().get(i);
            for (int record = 0; record < leaf.size(); record++) origins.add(leaf.leafId());
        }
        int base = origins.size() / targetLeafCount;
        int extra = origins.size() % targetLeafCount;
        int cursor = 0;
        int moved = 0;
        for (int target = 0; target < targetLeafCount; target++) {
            int count = base + (target < extra ? 1 : 0);
            long targetLeafId = target <= to - from
                    ? region.leaves().get(from + target).leafId() : Long.MIN_VALUE;
            for (int i = 0; i < count; i++) {
                if (origins.get(cursor++) != targetLeafId) moved++;
            }
        }
        return moved;
    }
}
