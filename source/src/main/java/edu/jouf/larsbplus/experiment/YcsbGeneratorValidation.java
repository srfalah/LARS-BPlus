package edu.jouf.larsbplus.experiment;

import edu.jouf.larsbplus.lars.OperationType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Performs the mandatory untimed one-million-operation generator validation. */
public final class YcsbGeneratorValidation {
    private static final int INITIAL_RECORDS = 1_000_000;
    private static final int OPERATIONS = 1_000_000;
    private static final long VALIDATION_SEED = 1_103_515_245L;
    private static final double MIX_TOLERANCE = 0.005;

    private YcsbGeneratorValidation() {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(parseArgs(args).getOrDefault("out",
                "results/ycsb_generator_validation.md"));
        if (output.getParent() != null) Files.createDirectories(output.getParent());

        double topOnePercentShare = validateZipfianDistribution();
        ArrayList<ProfileSummary> summaries = new ArrayList<>();
        for (YcsbWorkloadProfile profile : YcsbWorkloadProfile.values()) {
            List<ExperimentOperation> first = YcsbDerivedWorkloadGenerator.generate(
                    profile, INITIAL_RECORDS, OPERATIONS, VALIDATION_SEED);
            ProfileSummary summary = inspect(profile, first);
            long firstFingerprint = fingerprint(first);
            first = null;

            List<ExperimentOperation> repeated = YcsbDerivedWorkloadGenerator.generate(
                    profile, INITIAL_RECORDS, OPERATIONS, VALIDATION_SEED);
            long repeatedFingerprint = fingerprint(repeated);
            require(firstFingerprint == repeatedFingerprint,
                    profile + " is not deterministic for a fixed seed");
            summaries.add(summary.withFingerprint(firstFingerprint));
        }

        String report = renderReport(summaries, topOnePercentShare);
        Files.writeString(output, report, StandardCharsets.UTF_8);
        System.out.println("YCSB_GENERATOR_VALIDATION_OK " + output.toAbsolutePath());
    }

    private static ProfileSummary inspect(YcsbWorkloadProfile profile,
                                          List<ExperimentOperation> operations) {
        require(operations.size() == OPERATIONS, "wrong operation count for " + profile);
        EnumMap<OperationType, Long> counts = new EnumMap<>(OperationType.class);
        for (OperationType type : OperationType.values()) counts.put(type, 0L);
        Set<Long> inserted = new HashSet<>();
        int[] scanLengthCounts = new int[YcsbDerivedWorkloadGenerator.MAXIMUM_SCAN_LENGTH + 1];
        int scanMinimum = Integer.MAX_VALUE;
        int scanMaximum = Integer.MIN_VALUE;

        for (ExperimentOperation operation : operations) {
            counts.merge(operation.type(), 1L, Long::sum);
            switch (operation.type()) {
                case INSERT -> {
                    require(operation.key() % WorkloadGenerator.KEY_STRIDE != 0L,
                            "insert collided with an initial key");
                    require(inserted.add(operation.key()), "duplicate YCSB-E insert key");
                }
                case SEARCH, UPDATE, RANGE_SEARCH -> {
                    require(isKnownKey(operation.key(), inserted),
                            "request selected a key that was not present");
                    if (operation.type() == OperationType.RANGE_SEARCH) {
                        int length = YcsbDerivedWorkloadGenerator.translatedScanLength(operation);
                        require(length >= YcsbDerivedWorkloadGenerator.MINIMUM_SCAN_LENGTH
                                        && length <= YcsbDerivedWorkloadGenerator.MAXIMUM_SCAN_LENGTH,
                                "scan length outside [1,100]");
                        scanLengthCounts[length]++;
                        scanMinimum = Math.min(scanMinimum, length);
                        scanMaximum = Math.max(scanMaximum, length);
                    }
                }
                case DELETE -> throw new IllegalStateException("YCSB A/B/E must not generate DELETE");
            }
        }

        validateProportion(profile.searchProportion(), counts.get(OperationType.SEARCH), profile, "SEARCH");
        validateProportion(profile.updateProportion(), counts.get(OperationType.UPDATE), profile, "UPDATE");
        validateProportion(profile.insertProportion(), counts.get(OperationType.INSERT), profile, "INSERT");
        validateProportion(profile.rangeSearchProportion(), counts.get(OperationType.RANGE_SEARCH), profile,
                "RANGE_SEARCH");
        validateProportion(profile.deleteProportion(), counts.get(OperationType.DELETE), profile, "DELETE");

        double maximumUniformDeviation = 0.0;
        if (profile == YcsbWorkloadProfile.YCSB_E) {
            require(scanMinimum == 1 && scanMaximum == 100, "YCSB-E did not cover full scan-length range");
            double expectedPerLength = counts.get(OperationType.RANGE_SEARCH) / 100.0;
            for (int length = 1; length <= 100; length++) {
                double deviation = Math.abs(scanLengthCounts[length] - expectedPerLength) / expectedPerLength;
                maximumUniformDeviation = Math.max(maximumUniformDeviation, deviation);
            }
            require(maximumUniformDeviation <= 0.08,
                    "YCSB-E scan lengths are not approximately uniform: " + maximumUniformDeviation);
        }

        return new ProfileSummary(profile, counts, inserted.size(), scanMinimum, scanMaximum,
                maximumUniformDeviation, 0L);
    }

    private static boolean isKnownKey(long key, Set<Long> inserted) {
        if (inserted.contains(key)) return true;
        if (key < 0L || key % WorkloadGenerator.KEY_STRIDE != 0L) return false;
        long logicalId = key / WorkloadGenerator.KEY_STRIDE;
        return logicalId >= 0L && logicalId < INITIAL_RECORDS;
    }

    private static void validateProportion(double expected, long count,
                                           YcsbWorkloadProfile profile, String type) {
        double actual = count / (double) OPERATIONS;
        require(Math.abs(actual - expected) <= MIX_TOLERANCE,
                profile + " " + type + " proportion mismatch: expected=" + expected + " actual=" + actual);
    }

    private static double validateZipfianDistribution() {
        int itemCount = 10_000;
        int draws = 1_000_000;
        int[] frequencies = new int[itemCount];
        ScrambledZipfianLongGenerator first = new ScrambledZipfianLongGenerator(itemCount, VALIDATION_SEED);
        ScrambledZipfianLongGenerator repeated = new ScrambledZipfianLongGenerator(itemCount, VALIDATION_SEED);
        for (int i = 0; i < draws; i++) {
            long value = first.nextLong();
            require(value >= 0L && value < itemCount, "Zipfian output outside domain");
            require(value == repeated.nextLong(), "Zipfian generator is not deterministic");
            frequencies[Math.toIntExact(value)]++;
        }
        int[] sorted = frequencies.clone();
        Arrays.sort(sorted);
        long topOnePercent = 0L;
        for (int i = sorted.length - itemCount / 100; i < sorted.length; i++) {
            topOnePercent += sorted[i];
        }
        double share = topOnePercent / (double) draws;
        require(share >= 0.20, "Zipfian skew is unexpectedly weak: top-1% share=" + share);
        return share;
    }

    private static long fingerprint(List<ExperimentOperation> operations) {
        long hash = 0x9E3779B97F4A7C15L;
        for (ExperimentOperation operation : operations) {
            hash ^= Long.rotateLeft(operation.key(), 7);
            hash ^= Long.rotateLeft(operation.toKey(), 19);
            hash ^= Long.rotateLeft(operation.value(), 31);
            hash ^= (long) operation.type().ordinal() * 0xBF58476D1CE4E5B9L;
            hash = Long.rotateLeft(hash * 0x94D049BB133111EBL, 23);
        }
        return hash;
    }

    private static String renderReport(List<ProfileSummary> summaries, double topOnePercentShare) {
        StringBuilder report = new StringBuilder();
        report.append("# YCSB-Derived Generator Validation\n\n");
        report.append("- Operations generated per profile: 1,000,000\n");
        report.append("- Initial records: 1,000,000\n");
        report.append("- Validation seed: ").append(VALIDATION_SEED).append("\n");
        report.append("- Zipfian theta: 0.99\n");
        report.append("- Scramble: FNV-64\n");
        report.append(String.format(Locale.ROOT,
                "- Top 1%% hottest-key share in the 10,000-key distribution check: %.4f%%\n",
                topOnePercentShare * 100.0));
        report.append("- Same seed produced identical operation fingerprints for every profile.\n\n");
        report.append("| Profile | SEARCH | UPDATE | INSERT | RANGE | Unique inserts | Scan min/max | Max uniform-bin deviation | Fingerprint |\n");
        report.append("|---|---:|---:|---:|---:|---:|---:|---:|---|\n");
        for (ProfileSummary summary : summaries) {
            Map<OperationType, Long> c = summary.counts();
            String range = summary.profile() == YcsbWorkloadProfile.YCSB_E
                    ? summary.scanMinimum() + "/" + summary.scanMaximum() : "n/a";
            String deviation = summary.profile() == YcsbWorkloadProfile.YCSB_E
                    ? String.format(Locale.ROOT, "%.4f%%", summary.maximumUniformDeviation() * 100.0) : "n/a";
            report.append(String.format(Locale.ROOT,
                    "| %s | %.4f%% | %.4f%% | %.4f%% | %.4f%% | %d | %s | %s | `%016x` |%n",
                    summary.profile(), c.get(OperationType.SEARCH) * 100.0 / OPERATIONS,
                    c.get(OperationType.UPDATE) * 100.0 / OPERATIONS,
                    c.get(OperationType.INSERT) * 100.0 / OPERATIONS,
                    c.get(OperationType.RANGE_SEARCH) * 100.0 / OPERATIONS,
                    summary.uniqueInserts(), range, deviation, summary.fingerprint()));
        }
        report.append("\nResult: `YCSB_GENERATOR_VALIDATION_OK`\n");
        return report.toString();
    }

    private static Map<String, String> parseArgs(String[] args) {
        java.util.HashMap<String, String> parsed = new java.util.HashMap<>();
        for (String argument : args) {
            int separator = argument.indexOf('=');
            if (!argument.startsWith("--") || separator < 3) {
                throw new IllegalArgumentException("expected --key=value: " + argument);
            }
            parsed.put(argument.substring(2, separator), argument.substring(separator + 1));
        }
        return parsed;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record ProfileSummary(YcsbWorkloadProfile profile,
                                  EnumMap<OperationType, Long> counts,
                                  int uniqueInserts,
                                  int scanMinimum,
                                  int scanMaximum,
                                  double maximumUniformDeviation,
                                  long fingerprint) {
        ProfileSummary withFingerprint(long value) {
            return new ProfileSummary(profile, counts, uniqueInserts, scanMinimum, scanMaximum,
                    maximumUniformDeviation, value);
        }
    }
}
