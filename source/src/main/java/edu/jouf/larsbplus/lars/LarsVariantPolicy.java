package edu.jouf.larsbplus.lars;

/**
 * Evaluation-only feature switches used to construct single-component ablations.
 * The default FULL policy preserves the paper algorithm.
 */
public record LarsVariantPolicy(
        boolean useDiagnosis,
        boolean useLocalityBudget,
        boolean useRuntimeValidation,
        boolean useRollback
) {
    public static LarsVariantPolicy full() {
        return new LarsVariantPolicy(true, true, true, true);
    }

    public static LarsVariantPolicy noDiagnosis() {
        return new LarsVariantPolicy(false, true, true, true);
    }

    public static LarsVariantPolicy noLocality() {
        return new LarsVariantPolicy(true, false, true, true);
    }

    public static LarsVariantPolicy noRuntimeValidation() {
        return new LarsVariantPolicy(true, true, false, true);
    }

    public static LarsVariantPolicy noRollback() {
        return new LarsVariantPolicy(true, true, true, false);
    }

    /** Mechanism baseline: persistent local cost trigger without diagnosis, hard locality, or live validation. */
    public static LarsVariantPolicy localCostAdaptive() {
        return new LarsVariantPolicy(false, false, false, false);
    }
}
