package org.neuro.spikeledger.model;

import java.util.UUID;

/**
 * 稳定身份工厂。派生身份为内容寻址哈希，保证等价输入（相同材料版本与参数）
 * 在任意机器、任意执行顺序下得到相同身份与稳定顺序。
 */
public final class Ids {
    private Ids() {}

    public static String random(String prefix) {
        return prefix + "_" + UUID.randomUUID();
    }

    /** 内容寻址身份：相同输入 -> 相同 id（重放等价性）。 */
    public static String derived(String prefix, String canonicalInput) {
        return prefix + "_" + Hashing.sha12(canonicalInput);
    }

    public static boolean isStable(String id) {
        return id != null && id.startsWith("u_") || id != null && (id.startsWith("wf_")
                || id.startsWith("ev_") || id.startsWith("run_") || id.startsWith("seg_")
                || id.startsWith("br_") || id.startsWith("ds_"));
    }
}
