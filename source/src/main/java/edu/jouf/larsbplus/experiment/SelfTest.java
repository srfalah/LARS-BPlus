package edu.jouf.larsbplus.experiment;

import edu.jouf.larsbplus.core.BPlusTree;

import java.util.*;

/** Standalone deterministic validation suite requiring no external test library. */
public final class SelfTest {
    private SelfTest() {}

    public static void main(String[] args) {
        randomizedEquivalence();
        localActionAndRollback();
        compactAction();
        staleActionEstimateRejected();
        System.out.println("SELF_TEST_OK");
    }

    private static void randomizedEquivalence() {
        BPlusTree tree = new BPlusTree(8, 8);
        TreeMap<Long, Long> oracle = new TreeMap<>();
        Random random = new Random(0x5A17BEEFL);

        for (int i = 0; i < 20_000; i++) {
            int op = random.nextInt(100);
            long key = random.nextInt(5_000);
            long value = random.nextLong();

            if (op < 40) {
                boolean a = tree.insert(key, value);
                boolean b = oracle.putIfAbsent(key, value) == null;
                require(a == b, "insert mismatch");
            } else if (op < 60) {
                boolean a = tree.delete(key);
                boolean b = oracle.remove(key) != null;
                require(a == b, "delete mismatch");
            } else if (op < 75) {
                boolean a = tree.update(key, value);
                boolean b = oracle.containsKey(key);
                if (b) oracle.put(key, value);
                require(a == b, "update mismatch");
            } else if (op < 90) {
                OptionalLong a = tree.search(key);
                Long b = oracle.get(key);
                require(a.isPresent() == (b != null), "search presence mismatch");
                if (b != null) require(a.getAsLong() == b, "search value mismatch");
            } else {
                long end = key + random.nextInt(150);
                List<BPlusTree.Entry> actual = tree.rangeSearch(key, end);
                List<Map.Entry<Long, Long>> expected = new ArrayList<>(oracle.subMap(key, true, end, true).entrySet());
                require(actual.size() == expected.size(), "range size mismatch");
                for (int j = 0; j < actual.size(); j++) {
                    require(actual.get(j).key() == expected.get(j).getKey(), "range key mismatch");
                    require(actual.get(j).value() == expected.get(j).getValue(), "range value mismatch");
                }
            }

            if ((i & 255) == 0) {
                tree.validate();
                require(tree.size() == oracle.size(), "size mismatch");
            }
        }
        tree.validate();
        require(tree.size() == oracle.size(), "final size mismatch");
    }

    private static void localActionAndRollback() {
        BPlusTree tree = new BPlusTree(8, 8);
        for (int i = 0; i < 2_000; i++) require(tree.insert(i, i * 10L), "initial insert failed");
        tree.validate();

        // Find a feasible SLACKEN candidate.
        BPlusTree.ActionEstimate slack = null;
        for (BPlusTree.RegionView region : tree.regions()) {
            for (int width = 2; width <= Math.min(6, region.leafCount()); width++) {
                for (int from = 0; from + width <= region.leafCount(); from++) {
                    BPlusTree.ActionEstimate e = tree.estimateAction(BPlusTree.LocalAction.SLACKEN,
                            region.regionId(), from, from + width - 1);
                    if (e.feasible()) { slack = e; break; }
                }
                if (slack != null) break;
            }
            if (slack != null) break;
        }
        require(slack != null, "expected at least one feasible SLACKEN candidate");

        long regionId = slack.regionId();
        List<BPlusTree.Entry> before = tree.snapshotEntries();
        BPlusTree.RegionSnapshot snapshot = tree.snapshotRegion(regionId);
        tree.applyAction(slack);
        tree.validate();
        require(tree.snapshotEntries().equals(before), "SLACKEN changed logical contents");

        // Apply trial-local writes and verify rollback + replay preserves them.
        BPlusTree.RegionView view = tree.region(regionId).orElseThrow();
        long trialKey = view.firstKey();
        BPlusTree.UpdateMutation update = new BPlusTree.UpdateMutation(trialKey, 777777L);
        require(tree.canApplyMutationWithinRegion(regionId, update), "update should be region-safe");
        require(tree.update(trialKey, 777777L), "trial update failed");

        tree.restoreRegion(snapshot);
        tree.replayMutations(List.of(update));
        tree.validate();
        require(tree.search(trialKey).orElseThrow() == 777777L, "rollback replay lost trial update");
    }

    private static void compactAction() {
        BPlusTree tree = new BPlusTree(8, 8);
        for (int i = 0; i < 2_000; i++) tree.insert(i, i);
        BPlusTree.ActionEstimate compact = null;
        for (BPlusTree.RegionView region : tree.regions()) {
            for (int width = 2; width <= Math.min(6, region.leafCount()); width++) {
                for (int from = 0; from + width <= region.leafCount(); from++) {
                    BPlusTree.ActionEstimate e = tree.estimateAction(BPlusTree.LocalAction.COMPACT,
                            region.regionId(), from, from + width - 1);
                    if (e.feasible()) { compact = e; break; }
                }
                if (compact != null) break;
            }
            if (compact != null) break;
        }
        require(compact != null, "expected at least one feasible COMPACT candidate");
        List<BPlusTree.Entry> before = tree.snapshotEntries();
        tree.applyAction(compact);
        tree.validate();
        require(before.equals(tree.snapshotEntries()), "COMPACT changed logical contents");
    }


    private static void staleActionEstimateRejected() {
        BPlusTree tree = new BPlusTree(8, 8);
        for (int i = 0; i < 4_000; i++) tree.insert(i, i);

        BPlusTree.ActionEstimate stale = null;
        for (BPlusTree.RegionView region : tree.regions()) {
            for (int width = 2; width <= Math.min(6, region.leafCount()); width++) {
                for (int from = 0; from + width <= region.leafCount(); from++) {
                    BPlusTree.ActionEstimate e = tree.estimateAction(
                            BPlusTree.LocalAction.SLACKEN, region.regionId(), from, from + width - 1);
                    if (e.feasible()) {
                        stale = e;
                        break;
                    }
                }
                if (stale != null) break;
            }
            if (stale != null) break;
        }
        require(stale != null, "expected a feasible SLACKEN estimate for stale-estimate check");

        // Consume the region's spare child slots using fresh estimates until the original
        // estimate is no longer feasible against the current state.
        int guard = 0;
        while (guard++ < 64) {
            BPlusTree.ActionEstimate current = tree.estimateAction(
                    stale.action(), stale.regionId(),
                    stale.fromLeafInclusive(), stale.toLeafInclusive());
            if (!current.feasible()) break;
            tree.applyAction(current);
        }

        BPlusTree.ActionEstimate now = tree.estimateAction(
                stale.action(), stale.regionId(),
                stale.fromLeafInclusive(), stale.toLeafInclusive());
        require(!now.feasible(), "failed to create a stale no-longer-feasible estimate");

        boolean rejected = false;
        try {
            tree.applyAction(stale);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "stale action estimate must be rejected before mutation");
        tree.validate();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
