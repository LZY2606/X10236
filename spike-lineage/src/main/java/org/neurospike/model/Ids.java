package org.neurospike.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 稳定身份：同一规范化输入 -> 同一身份；身份只依赖内容，不依赖处理顺序。 */
public final class Ids {
    private Ids() {}

    public static String sha256Hex(String canonical) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String contentId(String prefix, String canonical) {
        return prefix + "_" + sha256Hex(canonical).substring(0, 24);
    }
}
