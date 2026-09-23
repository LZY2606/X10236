package gsb.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Content addressed identifiers so equivalent inputs yield the same stable identity. */
public final class Ids {
    private Ids() {}

    public static String hash(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String of(String prefix, String content) {
        return prefix + "_" + hash(content);
    }
}
