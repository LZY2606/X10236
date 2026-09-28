package spike.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Hash {
    private Hash() {}

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String shortId(String type, String content) {
        return type + "_" + sha256Hex(type + "|" + content).substring(0, 16);
    }
}
