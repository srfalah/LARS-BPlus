package edu.jouf.larsbplus.experiment;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentCampaignTest {
    @Test
    void incompleteOrHeaderOnlyRunArtifactsCannotBeResumed() throws Exception {
        Path directory = Files.createTempDirectory("lars-campaign-artifacts-");
        try {
            assertFalse(ExperimentCampaign.runArtifactsComplete(directory));
            Files.writeString(directory.resolve("run_metrics.csv"), "header\nrow\n");
            Files.writeString(directory.resolve("epoch_metrics.csv"), "header\nrow\n");
            Files.writeString(directory.resolve("adaptation_events.csv"), "header\n");
            Files.writeString(directory.resolve("post_commit_region_metrics.csv"), "header\n");
            assertTrue(ExperimentCampaign.runArtifactsComplete(directory));

            Files.writeString(directory.resolve("run_metrics.csv"), "header\n");
            assertFalse(ExperimentCampaign.runArtifactsComplete(directory));
        } finally {
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void mergedRunCountMustMatchTheCampaignMatrix() throws Exception {
        Path file = Files.createTempFile("lars-merged-runs-", ".csv");
        try {
            Files.writeString(file, "header\nrow1\nrow2\n");
            ExperimentCampaign.requireMergedRunCount(file, 2);
            boolean rejected = false;
            try {
                ExperimentCampaign.requireMergedRunCount(file, 3);
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            assertTrue(rejected);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void ycsbProfileRetainsThePublicationScale() {
        CampaignProfile profile = CampaignProfile.YCSB_VALIDATION;
        assertTrue(profile.initialRecords() == 1_000_000);
        assertTrue(profile.warmupOperations() == 100_000);
        assertTrue(profile.measuredOperations() == 300_000);
        assertTrue(profile.epochOperations() == 10_000);
        assertTrue(profile.repetitions() == 10);
    }

    @Test
    void ycsbIntegrityRejectsDuplicateKeysAndUnresolvedTrials() throws Exception {
        String header = "workload,variant,seed,throughput_ops_s,p99_latency_ns,trial_starts,commit_count,rollback_count\n";
        Path file = Files.createTempFile("lars-ycsb-integrity-", ".csv");
        try {
            Files.writeString(file, header
                    + "YCSB_A,LARS_FULL,1103515245,1000,500,1,1,0\n"
                    + "YCSB_A,STATIC_BPLUS,1103515245,1200,400,0,0,0\n");
            ExperimentCampaign.requireYcsbRunIntegrity(file, 2);

            Files.writeString(file, header
                    + "YCSB_A,LARS_FULL,1103515245,1000,500,1,1,0\n"
                    + "YCSB_A,LARS_FULL,1103515245,1000,500,1,1,0\n");
            boolean duplicateRejected = false;
            try {
                ExperimentCampaign.requireYcsbRunIntegrity(file, 2);
            } catch (IllegalStateException expected) {
                duplicateRejected = true;
            }
            assertTrue(duplicateRejected);

            Files.writeString(file, header
                    + "YCSB_E,LARS_FULL,1103515245,1000,500,1,0,0\n");
            boolean unresolvedRejected = false;
            try {
                ExperimentCampaign.requireYcsbRunIntegrity(file, 1);
            } catch (IllegalStateException expected) {
                unresolvedRejected = true;
            }
            assertTrue(unresolvedRejected);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
