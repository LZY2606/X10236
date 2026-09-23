package org.neuro.spike.model;

/** Refractory violations, amplitude drift and multi-channel propagation are evidence, never identity. */
public enum EvidenceKind {
    REFRACTORY_VIOLATION,
    AMPLITUDE_DRIFT,
    MULTI_CHANNEL_PROPAGATION,
    CONCURRENT_SPIKE,
    SHARED_EVENT_AFTER_SPLIT
}
