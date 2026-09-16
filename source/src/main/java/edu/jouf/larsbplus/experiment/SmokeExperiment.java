package edu.jouf.larsbplus.experiment;

import edu.jouf.larsbplus.core.BPlusTree;
import edu.jouf.larsbplus.lars.AdaptationEvent;
import edu.jouf.larsbplus.lars.LarsBPlusIndex;
import edu.jouf.larsbplus.lars.LarsConfig;

import java.util.Random;

/**
 * Small executable smoke workload. This is not the publication benchmark; it only verifies that
 * the integrated index/controller can execute a changing workload and emit adaptation events.
 */
public final class SmokeExperiment {
    private SmokeExperiment() {}

    public static void main(String[] args) {
        BPlusTree tree = new BPlusTree(32, 32);
        LarsBPlusIndex index = new LarsBPlusIndex(tree, LarsConfig.pilotDefaults());

        for (int i = 0; i < 100_000; i++) tree.insert(i * 2L, i);
        tree.validate();

        Random rnd = new Random(20260907L);

        // Warm/reference phase: predominantly point reads with a small amount of range scanning.
        for (int i = 0; i < 150_000; i++) {
            long key = (rnd.nextInt(100_000) * 2L);
            if ((i % 10) == 0) index.rangeSearch(key, key + 64);
            else index.search(key);
        }

        // Shift phase: focus inserts around a bounded key neighborhood while retaining reads.
        long nextOdd = 80_001L;
        for (int i = 0; i < 200_000; i++) {
            if ((i % 3) == 0) {
                index.insert(nextOdd, nextOdd);
                nextOdd += 2;
            } else {
                long key = 70_000L + rnd.nextInt(30_000);
                index.search(key);
            }
        }

        index.validate();
        LarsBPlusIndex.RuntimeCounters c = index.runtimeCounters();
        System.out.printf("size=%d leaves=%d foregroundOps=%d windows=%d trialOps=%d controllerNanos=%d%n",
                tree.size(), tree.leafCount(), c.foregroundOperations(), c.observationWindows(),
                c.trialOperations(), c.controllerNanos());

        for (AdaptationEvent event : index.adaptationEvents()) {
            System.out.println(event);
        }
    }
}
