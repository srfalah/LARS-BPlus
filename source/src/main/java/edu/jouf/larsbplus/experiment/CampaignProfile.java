package edu.jouf.larsbplus.experiment;

public enum CampaignProfile {
    SMOKE(20_000, 20_000, 2_000, 1, 10_000),
    PILOT(100_000, 100_000, 5_000, 3, 50_000),
    FREEZE_VALIDATION(100_000, 1_000_000, 10_000, 5, 200_000),
    PUBLICATION(1_000_000, 300_000, 10_000, 10, 100_000),
    YCSB_VALIDATION(1_000_000, 300_000, 10_000, 10, 100_000);

    private final int initialRecords;
    private final int measuredOperations;
    private final int epochOperations;
    private final int repetitions;
    private final int warmupOperations;

    CampaignProfile(int initialRecords, int measuredOperations, int epochOperations,
                    int repetitions, int warmupOperations) {
        this.initialRecords = initialRecords;
        this.measuredOperations = measuredOperations;
        this.epochOperations = epochOperations;
        this.repetitions = repetitions;
        this.warmupOperations = warmupOperations;
    }

    public int initialRecords() { return initialRecords; }
    public int measuredOperations() { return measuredOperations; }
    public int epochOperations() { return epochOperations; }
    public int repetitions() { return repetitions; }
    public int warmupOperations() { return warmupOperations; }
}
