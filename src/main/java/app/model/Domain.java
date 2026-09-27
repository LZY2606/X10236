package app.model;

import java.util.List;
import java.util.Map;

/** 事件溯源谱系中的核心领域类型。全部类型不可变。 */
public final class Domain {
    private Domain() {}

    public enum Layer { RAW_MATERIAL, PARAMETER, DETECTION, FEATURE, CLUSTER,
                        LINEAGE, EVIDENCE, DERIVED, DECISION, FAILURE }

    public enum Status { PROPOSED, ADOPTED, REJECTED, CANDIDATE, CONFLICT,
                         INVALID, DEGRADED, SUPERSEDED }

    public enum FailureMode { RESOURCE_EXHAUSTED, CORRUPT_FORMAT, SEMANTIC_UNRESOLVABLE }

    /** 带位置的诊断；绝不以空值/默认值伪装成功。 */
    public record Diagnostic(String code, String message, String position) {
        public static Diagnostic of(String code, String message, String position) {
            return new Diagnostic(code, message, position);
        }
    }

    /** 时间窗：端点是否包含显式记录，避免边界歧义。 */
    public record TimeWindow(double t0Ms, double t1Ms, String basis,
                             boolean includeStart, boolean includeEnd) {
        public boolean contains(double t) {
            boolean lo = includeStart ? t >= t0Ms : t > t0Ms;
            boolean hi = includeEnd ? t <= t1Ms : t < t1Ms;
            return lo && hi;
        }
    }

    /** 谱系节点（稳定单元身份，不随簇编号变化）。 */
    public record UnitNode(String unitId, String bornFromEvent, String note) {}

    public enum LineageOp { SEED, RELABEL, MERGE, SPLIT, DRIFT }

    public record LineageEdge(String childUnitId, List<String> parentUnitIds,
                              LineageOp op, String eventId, String detail) {}

    /** 检测事件 → 稳定单元的成员关系；无法唯一决定时保留全部合法候选。 */
    public record Membership(String spikeId, String unitId, Status status,
                             String runId, String eventId) {}

    public enum EvKind { REFRACTORY_VIOLATION, AMPLITUDE_DRIFT,
                         MULTICHANNEL_PROPAGATION, CONCURRENT_SPIKE }

    /** 证据：只作判断依据，永不改变稳定身份。 */
    public record Evidence(String evidenceId, EvKind kind, String unitId,
                           List<String> spikeIds, TimeWindow window,
                           Qty metric, String severity, String detail) {}

    public enum ResultKind { WAVEFORM_VIEW, FEATURE_PLOT, AUTOCORRELOGRAM,
                             FIRING_RATE, DRIFT_TIMELINE, PROBE_VIEW }

    /** 派生结果：记录输入摘要、参数版本、父级依赖；显示值均带稳定身份。 */
    public record DerivedResult(String resultId, ResultKind kind, String unitId,
                                TimeWindow window, String inputSummary,
                                String paramVersion, List<String> parents,
                                Map<String, Object> payload,
                                Status status, String diagnostic) {}

    /** 追加式账本事件（DAG：分支与决定历史可回溯）。 */
    public record EventRecord(String eventId, long seq, String parentId,
                              String branchId, String type, Layer layer,
                              String title,
                              List<String> inputs,     // 输入材料/事件引用（含版本）
                              String inputSummary,     // 输入摘要（哈希）
                              String paramVersion,
                              List<String> parents,    // 父级依赖
                              String payloadJson,
                              Status status,
                              List<Diagnostic> diagnostics,
                              List<String> affectedDownstream) {
        public EventRecord withStatus(Status s, List<Diagnostic> diags,
                                      List<String> downstream) {
            return new EventRecord(eventId, seq, parentId, branchId, type, layer,
                    title, inputs, inputSummary, paramVersion, parents,
                    payloadJson, s, diags, downstream);
        }
    }
}
