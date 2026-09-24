package neuro.spike.provenance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable content identifiers. Identity is derived from content and position in
 * the dependency graph, never from a mutable cluster ordinal, so a cluster
 * renumbering cannot be mistaken for a new neuron.
 */
public final class Ids {
    private Ids() {}

    public static String hash(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String p : parts) md.update((p == null ? "" : p).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0x1f);
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String materialId(String project, String kind, String sourceRef, String localIndex) {
        return "mat_" + kind + "_" + hash(project, kind, sourceRef, localIndex);
    }

    public static String ruleId(String ruleName, String version) {
        return "rule_" + ruleName + "_v" + version.replace('.', '-');
    }

    /** Derived-result id binds result kind to its parent dependency + input digest + rule version. */
    public static String resultId(String kind, String parentId, String inputDigest, String ruleVersion) {
        return "res_" + kind + "_" + hash(kind, parentId == null ? "" : parentId,
                inputDigest == null ? "" : inputDigest, ruleVersion);
    }

    public static String decisionId(String project, long seq) {
        return "dec_" + hash(project, Long.toHexString(seq)) + "_" + seq;
    }

    /**
     * A stable unit identity that survives renumbering. The same cluster
     * material across label runs shares this key because it references the
     * stable spike/material ids rather than the run-local ordinal.
     */
    public static String unitKey(String project, String anchorMaterialId) {
        return "unit_" + hash(project, anchorMaterialId);
    }
}
