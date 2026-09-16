package edu.jouf.larsbplus.experiment;

import edu.jouf.larsbplus.lars.LarsConfig;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Forked-JVM campaign runner with resume support and merged CSV outputs. */
public final class ExperimentCampaign {
    private static final String YCSB_FOCUS = "ycsb-validation";
    private static final String FROZEN_SOURCE_VERSION = "LARS-BPlus-Pilot-v6.4-Full-Monitoring-Fast";
    private static final String FROZEN_SOURCE_ZIP_SHA256 =
            "1547ae8902c26968b68e9b10e4488ec1a22999868afb5b0dbf84c7a029d058a2";
    private static final List<String> REQUIRED_RUN_ARTIFACTS = List.of(
            "run_metrics.csv", "epoch_metrics.csv", "adaptation_events.csv",
            "post_commit_region_metrics.csv");
    private static final long[] SEEDS = {
            1103515245L, 214013L, 1664525L, 22695477L, 69069L,
            48271L, 134775813L, 16807L, 2147483647L, 6364136223846793005L
    };

    private ExperimentCampaign() {}

    public static void main(String[] args) throws Exception {
        Map<String,String> cli = parseArgs(args);
        CampaignProfile profile = CampaignProfile.valueOf(cli.getOrDefault("profile", "SMOKE"));
        Path out = Path.of(cli.getOrDefault("out", "results/" + profile.name().toLowerCase(Locale.ROOT)));
        boolean ycsbFocusByProfile = profile == CampaignProfile.YCSB_VALIDATION;
        String focus = cli.getOrDefault("focus", ycsbFocusByProfile ? YCSB_FOCUS : "full");
        boolean ycsbFocus = focus.equals(YCSB_FOCUS);
        String heap = cli.getOrDefault("heap",
                profile == CampaignProfile.PUBLICATION || ycsbFocusByProfile ? "4g" : "2g");
        boolean controls = Boolean.parseBoolean(cli.getOrDefault("controls",
                Boolean.toString(!ycsbFocus)));
        boolean resume = Boolean.parseBoolean(cli.getOrDefault("resume", "true"));
        if (!focus.equals("full") && !focus.equals("commit-diagnostic") && !ycsbFocus) {
            throw new IllegalArgumentException("unsupported --focus=" + focus);
        }
        if (focus.equals("commit-diagnostic") && profile == CampaignProfile.PUBLICATION) {
            throw new IllegalArgumentException("commit-diagnostic is a pre-freeze PILOT/SMOKE workflow, not PUBLICATION");
        }
        if (ycsbFocusByProfile && !ycsbFocus) {
            throw new IllegalArgumentException("YCSB_VALIDATION requires --focus=ycsb-validation");
        }
        if (ycsbFocus && profile != CampaignProfile.YCSB_VALIDATION
                && profile != CampaignProfile.SMOKE) {
            throw new IllegalArgumentException("YCSB focus supports only SMOKE or YCSB_VALIDATION profiles");
        }
        if (ycsbFocus && controls) {
            throw new IllegalArgumentException("YCSB validation runs only the 90 specified main runs; use --controls=false");
        }

        Files.createDirectories(out);
        writeEnvironment(out.resolve("environment.txt"), profile, heap, focus);
        if (ycsbFocus) writeYcsbManifest(out.resolve("YCSB_VALIDATION_MANIFEST.txt"), profile, heap);

        List<RunSpec> specs = new ArrayList<>();
        List<WorkloadName> mainWorkloads = ycsbFocus
                ? List.of(WorkloadName.YCSB_A, WorkloadName.YCSB_B, WorkloadName.YCSB_E)
                : focus.equals("commit-diagnostic")
                ? List.of(WorkloadName.LOCAL_HOTSPOT, WorkloadName.SKEW_80_20,
                        WorkloadName.STRESS, WorkloadName.WORKLOAD_SHIFT)
                : List.of(WorkloadName.READ_HEAVY, WorkloadName.WRITE_HEAVY, WorkloadName.MIXED,
                        WorkloadName.WORKLOAD_SHIFT, WorkloadName.SKEW_80_20,
                        WorkloadName.LOCAL_HOTSPOT, WorkloadName.STRESS);
        List<ExperimentVariant> variants = ycsbFocus || focus.equals("commit-diagnostic")
                ? List.of(ExperimentVariant.STATIC_BPLUS, ExperimentVariant.LARS_MONITOR_ONLY,
                        ExperimentVariant.LARS_FULL)
                : List.of(ExperimentVariant.values());

        for (int rep = 0; rep < profile.repetitions(); rep++) {
            long seed = SEEDS[rep];
            for (WorkloadName w : mainWorkloads) {
                for (ExperimentVariant v : variants) specs.add(new RunSpec(w, v, rep, seed));
            }
            if (controls) {
                specs.add(new RunSpec(WorkloadName.STABLE_CONTROL, ExperimentVariant.LARS_FULL, rep, seed));
                specs.add(new RunSpec(WorkloadName.INSERT_PRESSURE_CONTROL, ExperimentVariant.LARS_FULL, rep, seed));
                specs.add(new RunSpec(WorkloadName.SCAN_FRAGMENTATION_CONTROL, ExperimentVariant.LARS_FULL, rep, seed));
            }
        }

        if (ycsbFocus) {
            System.out.println("YCSB validation workloads: " + mainWorkloads.size());
            System.out.println("variants: " + variants.size());
            System.out.println("seeds: " + profile.repetitions());
            System.out.println("expected measured runs: " + specs.size());
            int requiredRuns = profile == CampaignProfile.YCSB_VALIDATION ? 90 : 9;
            if (specs.size() != requiredRuns) {
                throw new IllegalStateException("YCSB matrix mismatch: expected="
                        + requiredRuns + " actual=" + specs.size());
            }
        }

        Collections.shuffle(specs, new Random(0x4c415253L ^ profile.ordinal()));
        Path raw = out.resolve("raw");
        Files.createDirectories(raw);
        int completed = 0;
        for (RunSpec spec : specs) {
            Path runDir = raw.resolve(spec.id());
            if (resume && runArtifactsComplete(runDir)) {
                completed++;
                System.out.printf(Locale.ROOT, "[%d/%d] RESUME %s%n", completed, specs.size(), spec.id());
                continue;
            }
            Files.createDirectories(runDir);
            System.out.printf(Locale.ROOT, "[%d/%d] RUN %s%n", completed + 1, specs.size(), spec.id());
            runChild(profile, spec, runDir, heap);
            if (!runArtifactsComplete(runDir)) {
                throw new IllegalStateException("child produced incomplete artifacts for " + spec.id());
            }
            completed++;
        }

        // A delayed filesystem failure can occur after the immediate child check. Recheck the
        // complete campaign and repair a bad run once before merging anything.
        for (RunSpec spec : specs) {
            Path runDir = raw.resolve(spec.id());
            if (runArtifactsComplete(runDir)) continue;
            System.out.printf(Locale.ROOT, "REPAIR_INCOMPLETE %s%n", spec.id());
            runChild(profile, spec, runDir, heap);
            if (!runArtifactsComplete(runDir)) {
                throw new IllegalStateException("repair produced incomplete artifacts for " + spec.id());
            }
        }

        merge(raw, "run_metrics.csv", out.resolve("run_metrics.csv"));
        merge(raw, "epoch_metrics.csv", out.resolve("epoch_metrics.csv"));
        merge(raw, "adaptation_events.csv", out.resolve("adaptation_events.csv"));
        merge(raw, "post_commit_region_metrics.csv", out.resolve("post_commit_region_metrics.csv"));
        requireMergedRunCount(out.resolve("run_metrics.csv"), specs.size());
        if (ycsbFocus) {
            requireYcsbRunIntegrity(out.resolve("run_metrics.csv"), specs.size());
            System.out.println("YCSB_VALIDATION_OK " + out.toAbsolutePath());
            System.out.println("completed runs: " + specs.size() + "/" + specs.size());
        } else {
            System.out.println("CAMPAIGN_OK " + out.toAbsolutePath());
        }
    }

    private static void runChild(CampaignProfile profile, RunSpec spec, Path runDir, String heap)
            throws IOException, InterruptedException {
        String java = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        cmd.add("-Xms" + heap);
        cmd.add("-Xmx" + heap);
        cmd.add("-XX:+UseG1GC");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(SingleExperimentRun.class.getName());
        cmd.add("--profile=" + profile.name());
        cmd.add("--workload=" + spec.workload.name());
        cmd.add("--variant=" + spec.variant.name());
        cmd.add("--repetition=" + spec.repetition);
        cmd.add("--seed=" + spec.seed);
        cmd.add("--out=" + runDir.toAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Path log = runDir.resolve("console.log");
        pb.redirectOutput(log.toFile());
        Process process = pb.start();
        int exit = process.waitFor();
        if (exit != 0) {
            String tail = Files.exists(log) ? tail(log, 80) : "no log";
            throw new IllegalStateException("child failed for " + spec.id() + " exit=" + exit + "\n" + tail);
        }
    }

    static boolean runArtifactsComplete(Path runDir) {
        for (String fileName : REQUIRED_RUN_ARTIFACTS) {
            Path file = runDir.resolve(fileName);
            try {
                if (!Files.isRegularFile(file) || Files.size(file) == 0L) return false;
                if ((fileName.equals("run_metrics.csv") || fileName.equals("epoch_metrics.csv"))
                        && firstTwoLineCount(file) < 2L) return false;
            } catch (IOException ignored) {
                return false;
            }
        }
        return true;
    }

    private static long firstTwoLineCount(Path file) throws IOException {
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.limit(2).count();
        }
    }

    static void requireMergedRunCount(Path runMetrics, int expectedRuns) throws IOException {
        long lines;
        try (var stream = Files.lines(runMetrics, StandardCharsets.UTF_8)) {
            lines = stream.count();
        }
        long actualRuns = Math.max(0L, lines - 1L);
        if (actualRuns != expectedRuns) {
            throw new IllegalStateException("merged run_metrics row count mismatch: expected="
                    + expectedRuns + " actual=" + actualRuns);
        }
    }

    static void requireYcsbRunIntegrity(Path runMetrics, int expectedRuns) throws IOException {
        List<String> lines = Files.readAllLines(runMetrics, StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new IllegalStateException("empty YCSB run metrics");
        String[] header = lines.get(0).split(",", -1);
        int workloadIndex = columnIndex(header, "workload");
        int variantIndex = columnIndex(header, "variant");
        int seedIndex = columnIndex(header, "seed");
        int throughputIndex = columnIndex(header, "throughput_ops_s");
        int p99Index = columnIndex(header, "p99_latency_ns");
        int trialsIndex = columnIndex(header, "trial_starts");
        int commitsIndex = columnIndex(header, "commit_count");
        int rollbacksIndex = columnIndex(header, "rollback_count");
        HashSet<String> keys = new HashSet<>();
        Set<String> allowedWorkloads = Set.of("YCSB_A", "YCSB_B", "YCSB_E");
        Set<String> allowedVariants = Set.of("STATIC_BPLUS", "LARS_MONITOR_ONLY", "LARS_FULL");
        for (int lineIndex = 1; lineIndex < lines.size(); lineIndex++) {
            String[] row = lines.get(lineIndex).split(",", -1);
            if (row.length != header.length) {
                throw new IllegalStateException("malformed YCSB run row " + lineIndex);
            }
            String workload = row[workloadIndex];
            String variant = row[variantIndex];
            String seed = row[seedIndex];
            if (!allowedWorkloads.contains(workload) || !allowedVariants.contains(variant)) {
                throw new IllegalStateException("unexpected YCSB pairing key: " + workload + "/" + variant);
            }
            if (!keys.add(workload + '\u0000' + variant + '\u0000' + seed)) {
                throw new IllegalStateException("duplicate YCSB run key: " + workload + "/" + variant + "/" + seed);
            }
            requireFinitePositive(row[throughputIndex], "throughput_ops_s", lineIndex);
            requireFinitePositive(row[p99Index], "p99_latency_ns", lineIndex);
            long trials = Long.parseLong(row[trialsIndex]);
            long terminals = Long.parseLong(row[commitsIndex]) + Long.parseLong(row[rollbacksIndex]);
            if (trials != terminals) {
                throw new IllegalStateException("unresolved YCSB live trial at row " + lineIndex
                        + ": starts=" + trials + " terminals=" + terminals);
            }
        }
        if (keys.size() != expectedRuns) {
            throw new IllegalStateException("YCSB unique run-key count mismatch: expected="
                    + expectedRuns + " actual=" + keys.size());
        }
    }

    private static int columnIndex(String[] header, String name) {
        for (int i = 0; i < header.length; i++) if (header[i].equals(name)) return i;
        throw new IllegalStateException("missing required YCSB column: " + name);
    }

    private static void requireFinitePositive(String value, String column, int row) {
        double parsed = Double.parseDouble(value);
        if (!Double.isFinite(parsed) || parsed <= 0.0) {
            throw new IllegalStateException("invalid " + column + " at row " + row + ": " + value);
        }
    }

    private static void merge(Path raw, String fileName, Path output) throws IOException {
        List<Path> files;
        try (var stream = Files.walk(raw)) {
            files = stream.filter(p -> p.getFileName().toString().equals(fileName)).sorted().toList();
        }
        try (BufferedWriter out = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            boolean wroteHeader = false;
            for (Path file : files) {
                try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    boolean first = true;
                    while ((line = in.readLine()) != null) {
                        if (first) {
                            first = false;
                            if (wroteHeader) continue;
                            wroteHeader = true;
                        }
                        out.write(line);
                        out.newLine();
                    }
                }
            }
        }
    }

    private static void writeEnvironment(Path file, CampaignProfile profile, String heap, String focus) throws IOException {
        LarsConfig larsConfig = LarsConfig.pilotDefaults();
        String text = "timestamp_utc=" + Instant.now() + "\n"
                + "profile=" + profile + "\n"
                + "campaign.focus=" + focus + "\n"
                + "java.version=" + System.getProperty("java.version") + "\n"
                + "java.vendor=" + System.getProperty("java.vendor") + "\n"
                + "java.vm.name=" + System.getProperty("java.vm.name") + "\n"
                + "os.name=" + System.getProperty("os.name") + "\n"
                + "os.version=" + System.getProperty("os.version") + "\n"
                + "os.arch=" + System.getProperty("os.arch") + "\n"
                + "available.processors=" + Runtime.getRuntime().availableProcessors() + "\n"
                + "max.heap.bytes=" + Runtime.getRuntime().maxMemory() + "\n"
                + "child.heap=" + heap + "\n"
                + "initial.records=" + profile.initialRecords() + "\n"
                + "measured.operations=" + profile.measuredOperations() + "\n"
                + "warmup.operations=" + profile.warmupOperations() + "\n"
                + "repetitions=" + profile.repetitions() + "\n"
                + "lars.config=" + larsConfig + "\n"
                + "gc=G1\n";
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static void writeYcsbManifest(Path file, CampaignProfile profile, String heap) throws IOException {
        LarsConfig config = LarsConfig.pilotDefaults();
        StringBuilder text = new StringBuilder();
        text.append("timestamp_utc=").append(Instant.now()).append('\n');
        text.append("extension.version=LARS-BPlus-YCSB-Validation-v1\n");
        text.append("source.baseline=").append(FROZEN_SOURCE_VERSION).append('\n');
        text.append("source.baseline.zip.sha256=").append(FROZEN_SOURCE_ZIP_SHA256).append('\n');
        text.append("java.version=").append(System.getProperty("java.version")).append('\n');
        text.append("java.vendor=").append(System.getProperty("java.vendor")).append('\n');
        text.append("os.name=").append(System.getProperty("os.name")).append('\n');
        text.append("os.version=").append(System.getProperty("os.version")).append('\n');
        text.append("heap=").append(heap).append('\n');
        text.append("gc=G1\n");
        text.append("workloads=YCSB_A,YCSB_B,YCSB_E\n");
        text.append("variants=STATIC_BPLUS,LARS_MONITOR_ONLY,LARS_FULL\n");
        text.append("seeds=");
        for (int i = 0; i < profile.repetitions(); i++) {
            if (i > 0) text.append(',');
            text.append(SEEDS[i]);
        }
        text.append('\n');
        text.append("expected.runs=").append(profile.repetitions() * 3 * 3).append('\n');
        text.append("initial.records=").append(profile.initialRecords()).append('\n');
        text.append("warmup.operations=").append(profile.warmupOperations()).append('\n');
        text.append("measured.operations=").append(profile.measuredOperations()).append('\n');
        text.append("epoch.operations=").append(profile.epochOperations()).append('\n');
        text.append("rate.limit=none\n");
        text.append("client.concurrency=1\n");
        text.append("record.model=long-to-long\n");
        text.append("zipfian.theta=").append(ScrambledZipfianLongGenerator.ZIPFIAN_CONSTANT).append('\n');
        text.append("zipfian.reference.items=").append(ScrambledZipfianLongGenerator.REFERENCE_ITEM_COUNT).append('\n');
        text.append("zipfian.scramble=FNV64\n");
        text.append("random.source=seeded-SplittableRandom\n");
        text.append("ycsb.e.scan.length.minimum=").append(YcsbDerivedWorkloadGenerator.MINIMUM_SCAN_LENGTH).append('\n');
        text.append("ycsb.e.scan.length.maximum=").append(YcsbDerivedWorkloadGenerator.MAXIMUM_SCAN_LENGTH).append('\n');
        text.append("ycsb.e.scan.length.distribution=uniform\n");
        text.append("lars.config=").append(config).append('\n');
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
    }

    private static String tail(Path file, int lines) throws IOException {
        List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
        int from = Math.max(0, all.size() - lines);
        return String.join(System.lineSeparator(), all.subList(from, all.size()));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Map<String,String> parseArgs(String[] args) {
        HashMap<String,String> m = new HashMap<>();
        for (String a : args) {
            int eq = a.indexOf('=');
            if (!a.startsWith("--") || eq < 3) throw new IllegalArgumentException("expected --key=value: " + a);
            m.put(a.substring(2,eq), a.substring(eq+1));
        }
        return m;
    }

    private record RunSpec(WorkloadName workload, ExperimentVariant variant, int repetition, long seed) {
        String id() {
            return String.format(Locale.ROOT, "%s__%s__r%02d__s%d", workload, variant, repetition, seed);
        }
    }
}
