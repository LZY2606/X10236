package ephys.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Stable identity and content digests. Cluster numbers are never used as identity. */
public final class Ids {
    private static final AtomicLong SEQ = new AtomicLong();

    private Ids() {}

    /** Content digest over canonical (sorted) parts so equivalent input yields equal digests. */
    public static String digest(List<String> parts) {
        List<String> sorted = parts.stream().sorted().toList();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String p : sorted) {
                md.update(p.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            byte[] out = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", out[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String newId(String prefix) {
        return prefix + "-" + Long.toString(SEQ.incrementAndGet(), 36) + "-"
                + Long.toString(System.nanoTime(), 36);
    }
}
