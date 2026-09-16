package edu.jouf.larsbplus.experiment;

import edu.jouf.larsbplus.lars.OperationType;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** Generates deterministic YCSB-derived logical operations for the fixed long-to-long index. */
public final class YcsbDerivedWorkloadGenerator {
    public static final int MINIMUM_SCAN_LENGTH = 1;
    public static final int MAXIMUM_SCAN_LENGTH = 100;

    private static final long TYPE_SALT = 0x594353425F545950L;
    private static final long KEY_SALT = 0x594353425F4B4559L;
    private static final long SCAN_SALT = 0x594353425F534341L;

    private YcsbDerivedWorkloadGenerator() {}

    public static List<ExperimentOperation> generate(YcsbWorkloadProfile profile,
                                                     int initialRecords,
                                                     int operations,
                                                     long seed) {
        if (profile == null) throw new NullPointerException("profile");
        if (initialRecords < 1) throw new IllegalArgumentException("initialRecords must be positive");
        if (operations < 0) throw new IllegalArgumentException("operations must be non-negative");

        long maximumLogicalRecords = Math.addExact((long) initialRecords, operations);
        SplittableRandom operationRandom = new SplittableRandom(seed ^ TYPE_SALT);
        ScrambledZipfianLongGenerator keyChooser =
                new ScrambledZipfianLongGenerator(maximumLogicalRecords, seed ^ KEY_SALT);
        UniformScanLengthGenerator scanLengths = new UniformScanLengthGenerator(
                MINIMUM_SCAN_LENGTH, MAXIMUM_SCAN_LENGTH, seed ^ SCAN_SALT);

        ArrayList<Long> insertedPhysicalKeys = new ArrayList<>();
        ArrayList<ExperimentOperation> generated = new ArrayList<>(operations);
        long currentRecords = initialRecords;

        for (int operationIndex = 0; operationIndex < operations; operationIndex++) {
            OperationType type = profile.chooseOperation(operationRandom.nextDouble());
            long key;
            long toKey;

            if (type == OperationType.INSERT) {
                int insertOrdinal = insertedPhysicalKeys.size() + 1;
                long logicalId = currentRecords;
                key = insertedKey(logicalId, insertOrdinal, initialRecords);
                insertedPhysicalKeys.add(key);
                currentRecords++;
                toKey = key;
            } else {
                long logicalId = keyChooser.nextExistingLong(currentRecords);
                key = physicalKey(logicalId, initialRecords, insertedPhysicalKeys);
                if (type == OperationType.RANGE_SEARCH) {
                    int requestedLength = scanLengths.nextInt();
                    toKey = inclusiveRangeEnd(key, requestedLength);
                } else {
                    toKey = key;
                }
            }

            long value = mix64(seed ^ ((long) operationIndex << 17) ^ key);
            generated.add(new ExperimentOperation(type, key, value, toKey,
                    profile.name(), null));
        }
        return List.copyOf(generated);
    }

    static int translatedScanLength(ExperimentOperation operation) {
        if (operation.type() != OperationType.RANGE_SEARCH) {
            throw new IllegalArgumentException("operation is not a range search");
        }
        return Math.toIntExact(Math.floorDiv(operation.toKey() - operation.key(),
                WorkloadGenerator.KEY_STRIDE) + 1L);
    }

    private static long physicalKey(long logicalId, int initialRecords,
                                    List<Long> insertedPhysicalKeys) {
        if (logicalId < initialRecords) return logicalId * WorkloadGenerator.KEY_STRIDE;
        int insertedIndex = Math.toIntExact(logicalId - initialRecords);
        if (insertedIndex >= insertedPhysicalKeys.size()) {
            throw new IllegalStateException("selected a logical key that has not been inserted");
        }
        return insertedPhysicalKeys.get(insertedIndex);
    }

    /**
     * Maps YCSB's increasing insert IDs into scattered B+Tree intervals. The FNV-scrambled
     * interval avoids a sequential append hotspot, while the ordinal suffix guarantees
     * uniqueness and cannot collide with initial records (whose suffix is zero).
     */
    private static long insertedKey(long logicalId, int insertOrdinal, int initialRecords) {
        if (insertOrdinal >= WorkloadGenerator.KEY_STRIDE) {
            throw new IllegalStateException("YCSB insert key-space exhausted");
        }
        long interval = Math.floorMod(ScrambledZipfianLongGenerator.fnvHash64(logicalId),
                initialRecords);
        return Math.addExact(Math.multiplyExact(interval, WorkloadGenerator.KEY_STRIDE),
                insertOrdinal);
    }

    private static long inclusiveRangeEnd(long startKey, int requestedLength) {
        long width = Math.multiplyExact((long) requestedLength, WorkloadGenerator.KEY_STRIDE);
        long delta = width - 1L;
        if (startKey > Long.MAX_VALUE - delta) return Long.MAX_VALUE;
        return startKey + delta;
    }

    private static long mix64(long value) {
        long z = (value ^ (value >>> 33)) * 0xff51afd7ed558ccdl;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53l;
        return z ^ (z >>> 33);
    }
}
