package neuro.domain;

import java.util.List;
import neuro.model.Diagnostic;

/**
 * A computed result with full provenance: the material/input digest it was computed from, the
 * parameter version and the parent event. Results are never silently overwritten; recomputation
 * after a scoped change yields a new result and marks exactly the affected downstream objects.
 */
public record DerivedResult(
        String key,                 // e.g. evidence:<unitId>
        String inputDigest,
        String paramVersion,
        long producedAtSeq,
        String parentEventId,
        String status,              // OK | LOCAL_FAILURE | INVALID
        Evidence.Bundle bundle,     // null when not computable
        Verdict verdict,
        List<Diagnostic> diagnostics,
        boolean downstreamAffected) {
}
