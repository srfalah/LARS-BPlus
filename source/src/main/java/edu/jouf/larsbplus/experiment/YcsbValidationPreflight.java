package edu.jouf.larsbplus.experiment;

/** Runs all non-Maven pre-campaign gates in one process. */
public final class YcsbValidationPreflight {
    private YcsbValidationPreflight() {}

    public static void main(String[] args) throws Exception {
        SelfTest.main(new String[0]);
        ControllerSelfCheck.main(new String[0]);
        CalibrationSelfCheck.main(new String[0]);
        ObservabilitySelfCheck.main(new String[0]);
        YcsbGeneratorValidation.main(args);
        System.out.println("YCSB_PREFLIGHT_OK");
    }
}
