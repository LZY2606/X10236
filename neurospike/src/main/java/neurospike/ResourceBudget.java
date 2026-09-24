package neurospike;

/**
 * Resource budget enforced during import. Exhaustion is a distinct result from
 * corruption and from semantic impossibility.
 */
public record ResourceBudget(long maxBytes, long maxNodes, long maxDetections,
                             long maxWaveformSamples, long nodes, long bytes) {

    public static ResourceBudget defaultBudget() {
        return new ResourceBudget(32L * 1024 * 1024, 5_000_000, 100_000,
                50_000_000L, 0, 0);
    }

    public ResourceBudget withBytes(long b) {
        return new ResourceBudget(maxBytes, maxNodes, maxDetections,
                maxWaveformSamples, nodes, b);
    }

    public ResourceBudget tickNode() throws BudgetExceededException {
        if (nodes + 1 > maxNodes || bytes > maxBytes) {
            throw new BudgetExceededException(new Diagnostic(
                    Diagnostic.BUDGET_EXHAUSTED, "",
                    "import resource budget exhausted (nodes=" + (nodes + 1)
                            + ", bytes=" + bytes + ")", null));
        }
        return new ResourceBudget(maxBytes, maxNodes, maxDetections,
                maxWaveformSamples, nodes + 1, bytes);
    }

    public static final class BudgetExceededException extends Exception {
        public BudgetExceededException(Diagnostic d) {
            super(d.toString());
            this.diagnostic = d;
        }

        public final Diagnostic diagnostic;
    }
}
