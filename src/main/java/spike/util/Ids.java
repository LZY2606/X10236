package spike.util;

import java.security.SecureRandom;

/** Stable identifiers. Cluster *numbers* are volatile; these ids are the stable identity. */
public final class Ids {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Ids() {
    }

    public static String random(String prefix) {
        byte[] bytes = new byte[10];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(prefix);
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }

    /** Deterministic id for seeded case-pack material (stable across replays/round-trips). */
    public static String seeded(String prefix, String seed) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < seed.length(); i++) {
            h ^= seed.charAt(i);
            h *= 0x100000001b3L;
        }
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < 12; i++) {
            sb.append(HEX[(int) ((h >>> (i * 5)) & 0xF)]);
        }
        return sb.toString();
    }
}
