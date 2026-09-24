package org.neurospike.ingest;

/** 显式资源预算：超限即 RESOURCE_EXHAUSTED，与格式损坏、语义无解区分。 */
public record ResourceBudget(long maxEvents, long maxWaveformBytes, long maxNodes) {
    public static ResourceBudget defaultBudget() {
        return new ResourceBudget(200_000, 64L * 1024 * 1024, 1_000_000);
    }
}
