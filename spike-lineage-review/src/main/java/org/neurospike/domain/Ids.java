package org.neurospike.domain;

import java.util.UUID;

public final class Ids {
    private Ids() {
    }

    public static String stable(String prefix, String deterministicKey) {
        return prefix + "_" + UUID.nameUUIDFromBytes(deterministicKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String random(String prefix) {
        return prefix + "_" + UUID.randomUUID();
    }
}
