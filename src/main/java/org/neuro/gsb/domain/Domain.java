package org.neuro.gsb.domain;

import java.util.List;

/**
 * 领域模型：神经电生理/棘波候选/漂移簇/人工裁定谱系。
 *
 * 分层原则：
 *  - 不可变材料（原始波形引用、探针几何、检测事件、记录时间基准）一旦写入永不修改；
 *  - 检测阈值、特征版本、聚类标签是“版本化计算物”，新版本是追加而非覆盖；
 *  - 人工裁定（接受/否定/参数调整/冲突裁决）是追加事件，永不删除。
 * 所有身份都是显式稳定 ID，聚类序号不等于簇身份（lineageId）。
 */
public final class Domain {
    private Domain() {}

    /** 三种必须可区分的失败结果（另加成功与被预算拒绝的输入诊断）。 */
    public enum FailureKind {
        SUCCESS,
        /** 资源预算耗尽：材料过大、实体过多等。 */
        BUDGET_EXHAUSTED,
        /** 格式损坏：JSON/结构无法解析。 */
        FORMAT_CORRUPT,
        /** 语义无解：结构可解析但领域含义无法唯一确定。 */
        SEMANTIC_UNRESOLVABLE
    }

    /** 判定状态。 */
    public enum Verdict {
        PENDING,
        ACCEPTED,
        REJECTED,
        /** 参数被调整（生成新的计算物版本）。 */
        ADJUSTED,
        /** 多个互相冲突的人工决定并存，尚未裁决。 */
        CONFLICTED,
        /** 局部失效：该对象无法计算，但不影响范围之外。 */
        LOCALLY_FAILED
    }

    /** 证据种类。证据不自动等于结论：refractory/amplitude/propagation 都只是证据。 */
    public enum EvidenceKind {
        CONCURRENT_SPIKE("concurrent_spike"),
        REFRACTORY_VIOLATION("refractory_violation"),
        AMPLITUDE_DRIFT("amplitude_drift"),
        MULTICHANNEL_PROPAGATION("multichannel_propagation"),
        SPLIT_SHARED_EVENT("split_shared_event"),
        NUMBERING_CHANGE("numbering_change"),
        WINDOW_ENDPOINT("window_endpoint"),
        LOCAL_FAILURE("local_failure");

        private final String code;
        EvidenceKind(String code) { this.code = code; }
        public String code() { return code; }
        public static EvidenceKind fromCode(String code) {
            for (EvidenceKind k : values()) if (k.code.equals(code)) return k;
            throw new IllegalArgumentException("unknown evidence kind: " + code);
        }
    }

    /** 带位置的输入诊断。绝不用空值/默认值伪装成功。 */
    public record Diagnostic(String location, String code, String message) {}

    /** 摄入结果：要么成功携带全部实体与“未转换但保留”的值，要么携带分类后的失败。 */
    public record IngestOutcome(
            FailureKind kind,
            String summary,
            List<Diagnostic> diagnostics,
            IngestedCase payload) {
        public boolean ok() { return kind == FailureKind.SUCCESS; }
        public static IngestOutcome fail(FailureKind k, String summary, List<Diagnostic> diags) {
            return new IngestOutcome(k, summary, diags, null);
        }
    }

    /** 一次成功摄入解析出的全部实体。 */
    public record IngestedCase(
            CaseMaterial material,
            List<WaveformRef> waveforms,
            List<DetectionEvent> events,
            ThresholdSpec threshold,
            FeatureSpec featureSpec,
            List<ClusterLabelSet> labelSets,
            List<Lineage> lineages,
            List<Diagnostic> retainedDiagnostics) {}

    public record CaseMaterial(
            String caseId,
            String schemaVersion,
            String recordingId,
            /** 记录时间基准，例如 t0=2031-03-11T09:14:07+08:00，事件时间是相对它的秒偏移。 */
            TimeBasis timeBasis,
            /** 原始表示保留：时间单位声明。 */
            String timeUnit,
            String amplitudeUnit,
            double refractoryPeriodSeconds) {}

    public record TimeBasis(String epochId, String isoUtc, String note) {}

    public record ProbeGeometry(
            String probeId,
            String coordinateBasis,
            String distanceUnit,
            List<Site> sites) {
        public record Site(String siteId, int channel, double x, double y, String rawX, String rawY) {}
    }

    public record WaveformRef(
            String refId,
            String recordingId,
            String channelSiteId,
            /** 原始引用字符串（文件路径/URI/内部键），原样保留。 */
            String rawLocator,
            /** 采样率与其原始单位表示。 */
            String sampleRateRaw,
            String sampleRateUnit,
            double sampleRateHz,
            /** 内联片段样本（离线包自包含），单位为 amplitudeUnit；null 表示仅引用。 */
            double[] samples,
            double startOffsetSeconds) {}

    /** 检测事件（不可变材料）。原始表示 + 派生规范化值并列存储。 */
    public record DetectionEvent(
            String eventId,
            String waveformRefId,
            String siteId,
            /** 原始时间表示（字符串），例如 "0.012500 s"。 */
            String timeRaw,
            double timeSeconds,
            boolean timeParsed,
            /** 原始幅度表示，例如 "42.1 uV" 或无法转换的 "high?"。 */
            String amplitudeRaw,
            Double amplitudeNormalized,
            boolean amplitudeParsed,
            String amplitudeUnit,
            int sampleIndex) {}

    /** 检测阈值（版本化计算物）。 */
    public record ThresholdSpec(
            String thresholdId,
            String version,
            String method,
            /** 每条通道的阈值，原始表示保留。 */
            List<ThresholdLevel> levels,
            String parentId,
            String revisionReason) {
        public record ThresholdLevel(String siteId, String valueRaw, Double valueNormalized,
                                     boolean parsed, String unit) {}
    }

    /** 特征版本（版本化计算物）。 */
    public record FeatureSpec(
            String featureId,
            String version,
            String extractor,
            int dimensions,
            List<String> parameterKeys,
            String parametersCanonical,
            String parentId) {}

    /** 每个检测事件在某特征版本下的特征向量（派生层规范化）。 */
    public record FeatureValue(String eventId, String featureId, double[] vector) {}

    /** 聚类标签集：某版本/规则下 event -> clusterNumber。编号不稳定，lineage 才是身份。 */
    public record ClusterLabelSet(
            String labelSetId,
            String version,
            String ruleVersion,
            String featureId,
            String parentId,
            /** clusterNumber -> 稳定 lineageId 的映射（在该版本内显式声明）。 */
            List<NumberMapping> mappings,
            List<Assignment> assignments) {
        public record NumberMapping(int clusterNumber, String lineageId) {}
        public record Assignment(String eventId, int clusterNumber) {}
    }

    /** 稳定簇身份：跨重编号/合并/拆分保持的谱系。 */
    public record Lineage(
            String lineageId,
            String displayName,
            String parentLineageId,
            /** SPLIT / MERGE / ROOT */
            String relation,
            String note) {}

    /** 追加式裁定事件。 */
    public record Adjudication(
            String adjudicationId,
            String eventId,
            String scopeId,
            String scopeType,
            String decision,
            String curator,
            String rationale,
            String createdFromEventId,
            String parameterVersion) {}

    /** 证据条目（结构化状态，不只是日志）。 */
    public record Evidence(
            String evidenceId,
            EvidenceKind kind,
            String subjectId,
            String subjectType,
            String detailCanonical,
            String basisEventId) {}
}
