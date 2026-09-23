package spike.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of importing a case pack. Counts plus positioned diagnostics. Corrupt format, semantic
 * impossibility and budget exhaustion stay distinct outcomes; local failures quarantine only the
 * offending item and never fail the whole pack.
 */
public class ImportResult {
    public String caseId;
    public int probeChannels;
    public int waveforms;
    public int thresholds;
    public int featureVersions;
    public int spikes;
    public final List<Diagnostic> diagnostics = new ArrayList<>();
    public boolean formatCorrupt;
    public boolean semanticImpossible;
    public boolean budgetExhausted;
    public int skippedLocal;
}
