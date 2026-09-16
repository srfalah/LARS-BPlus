package edu.jouf.larsbplus.experiment;

import edu.jouf.larsbplus.lars.OperationType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YcsbDerivedWorkloadGeneratorTest {
    private static final int INITIAL_RECORDS = 100_000;
    private static final long SEED = 1_103_515_245L;

    @Test
    void officialOperationMixesAreReproducedWithinSamplingTolerance() {
        assertMix(YcsbWorkloadProfile.YCSB_A, 0.50, 0.50, 0.00, 0.00);
        assertMix(YcsbWorkloadProfile.YCSB_B, 0.95, 0.05, 0.00, 0.00);
        assertMix(YcsbWorkloadProfile.YCSB_E, 0.00, 0.00, 0.05, 0.95);
    }

    @Test
    void sameSeedProducesExactlyTheSameOperationSequence() {
        for (YcsbWorkloadProfile profile : YcsbWorkloadProfile.values()) {
            List<ExperimentOperation> first = YcsbDerivedWorkloadGenerator.generate(
                    profile, INITIAL_RECORDS, 20_000, SEED);
            List<ExperimentOperation> repeated = YcsbDerivedWorkloadGenerator.generate(
                    profile, INITIAL_RECORDS, 20_000, SEED);
            assertEquals(first, repeated, profile.name());
        }
    }

    @Test
    void ycsbEUsesValidStartsUniqueInsertsAndUniformOneToOneHundredLengths() {
        List<ExperimentOperation> operations = YcsbDerivedWorkloadGenerator.generate(
                YcsbWorkloadProfile.YCSB_E, INITIAL_RECORDS, 200_000, SEED);
        Set<Long> inserted = new HashSet<>();
        int[] lengths = new int[101];
        for (ExperimentOperation operation : operations) {
            if (operation.type() == OperationType.INSERT) {
                assertTrue(operation.key() % WorkloadGenerator.KEY_STRIDE != 0L);
                assertTrue(inserted.add(operation.key()), "insert keys must be unique");
            } else {
                assertEquals(OperationType.RANGE_SEARCH, operation.type());
                assertTrue(initialKey(operation.key()) || inserted.contains(operation.key()),
                        "range start must already exist");
                int length = YcsbDerivedWorkloadGenerator.translatedScanLength(operation);
                assertTrue(length >= 1 && length <= 100);
                lengths[length]++;
            }
        }
        assertTrue(Math.abs(10_000 - inserted.size()) <= 700,
                "insert proportion should remain close to five percent");
        for (int length = 1; length <= 100; length++) {
            assertTrue(lengths[length] > 0, "every scan length must be reachable");
        }
        double expected = Arrays.stream(lengths).sum() / 100.0;
        double maximumDeviation = 0.0;
        for (int length = 1; length <= 100; length++) {
            maximumDeviation = Math.max(maximumDeviation,
                    Math.abs(lengths[length] - expected) / expected);
        }
        assertTrue(maximumDeviation < 0.12, "scan-length bins should be approximately uniform");
    }

    @Test
    void scrambledZipfianGeneratorIsDeterministicBoundedAndSkewed() {
        int itemCount = 10_000;
        int draws = 200_000;
        int[] frequencies = new int[itemCount];
        ScrambledZipfianLongGenerator first = new ScrambledZipfianLongGenerator(itemCount, SEED);
        ScrambledZipfianLongGenerator repeated = new ScrambledZipfianLongGenerator(itemCount, SEED);
        for (int i = 0; i < draws; i++) {
            long value = first.nextLong();
            assertEquals(value, repeated.nextLong());
            assertTrue(value >= 0L && value < itemCount);
            frequencies[Math.toIntExact(value)]++;
        }
        Arrays.sort(frequencies);
        long hottestOnePercent = 0L;
        for (int i = frequencies.length - itemCount / 100; i < frequencies.length; i++) {
            hottestOnePercent += frequencies[i];
        }
        assertTrue(hottestOnePercent / (double) draws >= 0.20);
    }

    private static void assertMix(YcsbWorkloadProfile profile,
                                  double search, double update, double insert, double range) {
        int operations = 200_000;
        List<ExperimentOperation> generated = YcsbDerivedWorkloadGenerator.generate(
                profile, INITIAL_RECORDS, operations, SEED);
        EnumMap<OperationType, Integer> counts = new EnumMap<>(OperationType.class);
        for (OperationType type : OperationType.values()) counts.put(type, 0);
        for (ExperimentOperation operation : generated) counts.merge(operation.type(), 1, Integer::sum);
        assertEquals(search, counts.get(OperationType.SEARCH) / (double) operations, 0.01);
        assertEquals(update, counts.get(OperationType.UPDATE) / (double) operations, 0.01);
        assertEquals(insert, counts.get(OperationType.INSERT) / (double) operations, 0.01);
        assertEquals(range, counts.get(OperationType.RANGE_SEARCH) / (double) operations, 0.01);
        assertEquals(0, counts.get(OperationType.DELETE));
    }

    private static boolean initialKey(long key) {
        if (key < 0L || key % WorkloadGenerator.KEY_STRIDE != 0L) return false;
        return key / WorkloadGenerator.KEY_STRIDE < INITIAL_RECORDS;
    }
}
