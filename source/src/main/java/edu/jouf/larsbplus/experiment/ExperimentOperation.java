package edu.jouf.larsbplus.experiment;

import edu.jouf.larsbplus.lars.Diagnosis;
import edu.jouf.larsbplus.lars.OperationType;

public record ExperimentOperation(
        OperationType type,
        long key,
        long value,
        long toKey,
        String phase,
        Diagnosis expectedDiagnosis
) {}
