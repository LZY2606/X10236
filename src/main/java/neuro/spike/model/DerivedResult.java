package neuro.spike.model;

import java.util.List;

/**
 * One computed evidence envelope. Every derived value carries:
 *  - stable result id derived from kind+parents+input digest+rule version
 *  - input digest, rule/version, parent dependencies
 *  - the unit/coordinate/time basis it is expressed in
 *  - a valid interval and, when locally invalid, a structured reason
 * Evidence is advisory; it never assigns or changes unit identity.
 */
public record DerivedResult(String resultId,
                            EvidenceKind kind,
                            String subjectId,
                            String subjectKind,
                            String windowId,
                            String ruleName,
                            String ruleVersion,
                            String inputDigest,
                            List<Dependency> parents,
                            String unit,
                            String basis,
                            double validStartSec,
                            double validEndSec,
                            Validity validity,
                            String invalidReason,
                            String payloadJson,
                            long computedSeq) {

    public DerivedResult withValidity(Validity v, String reason) {
        return new DerivedResult(resultId, kind, subjectId, subjectKind, windowId, ruleName, ruleVersion,
                inputDigest, parents, unit, basis, validStartSec, validEndSec, v, reason, payloadJson, computedSeq);
    }

    public boolean isLive() { return validity == Validity.VALID; }
}
