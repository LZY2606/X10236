package gsb.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Stable, content-derived identities. The same logical material always maps
 *  to the same id, across processes and across export/import round-trips. */
public final class Ids {
    private Ids() {}

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Content-derived id: kind prefix + first 24 hex chars of the canonical hash. */
    public static String of(String kind, String canonicalContent) {
        return kind + "_" + sha256(canonicalContent).substring(0, 24);
    }
}
