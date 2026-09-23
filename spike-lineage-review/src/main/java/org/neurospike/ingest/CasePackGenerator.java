package org.neurospike.ingest;

import org.neurospike.domain.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the committed offline domain case pack:
 * "pulse cluster atlas: neural spike candidates, drift clusters and manual adjudication lineage".
 *
 * Deterministic output (fixed seed and stable ordering). Covers the seven replay situations:
 * concurrent spikes, amplitude drift, cluster renumbering, post-split shared events,
 * window endpoints, adjudication conflict and local failure.
 */
public final class CasePackGenerator {
    private final Path out;
    private final List<Object[]> rows = new ArrayList<>(); // id,time,ch,ref,sample,len,chans,amp,unit
    private final Map<Long, String> unitV1 = new LinkedHashMap<>();
    private final Map<Long, String> unitV2 = new LinkedHashMap<>();
    private final Map<Long, double[]> pc = new LinkedHashMap<>();
    private long nextId = 1;
    private long sampleCursor = 0;

    public CasePackGenerator(Path out) {
        this.out = out;
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0]
                : "src/main/resources/casepack/spike-lineage-case/src");
        new CasePackGenerator(dir).generate();
        System.out.println("case pack written to " + dir);
    }

    public void generate() throws IOException {
        Files.createDirectories(out);
        buildUnits();
        writeManifest();
        writeRules();
        writeScenarios();
        writeWaveforms();
        writeGeometry();
        writeThresholds();
        writeSpikes();
        writeFeatures();
        writeClusters();
        writeDecisions();
        writeReadme();
    }

    private void add(long timeMs, int channel, String chans, String amp, String v1, String v2,
                     double pc1, double pc2, int sampleLen) {
        long id = nextId++;
        rows.add(new Object[]{id, timeMs, channel, "wf0", sampleCursor, sampleLen, chans, amp});
        sampleCursor += sampleLen + 40;
        if (v1 != null) {
            unitV1.put(id, v1);
        }
        if (v2 != null) {
            unitV2.put(id, v2);
        }
        pc.put(id, new double[]{pc1, pc2});
    }

    private void buildUnits() {
        // --- unit A: regular firing; amplitude drift in later window ---
        long t = 1000;
        for (int i = 0; i < 24; i++) {
            double drift = i >= 14 ? -18.0 : 0.0;
            long amp = (long) (120 + drift + (i % 2 == 0 ? 2 : -2));
            add(t, 1, "1,2", String.valueOf(amp), "u_a", "u_a", -2.0 + 0.05 * (i % 5),
                    1.0 - 0.04 * (i % 4), 48);
            t += 45;
        }
        // refractory violation: two A spikes 0.4 ms apart (< 1.5 ms) -> evidence only
        add(t, 1, "1,2", "118", "u_a", "u_a", -1.9, 1.0, 48);
        add(t + 12, 1, "1,2", "116", "u_a", "u_a", -1.85, 1.05, 48); // 12 samples @30k = 0.4ms
        t += 90;
        add(t, 1, "1,2", "101", "u_a", "u_a", -1.95, 0.95, 48);
        add(t + 1350, 1, "1,2", "99", "u_a", "u_a", -2.05, 1.02, 48);

        // --- unit B: on channels 3,4; simultaneous multi-channel propagation ---
        long tb = 1100;
        for (int i = 0; i < 20; i++) {
            String chans = i >= 12 ? "3,4,5" : "3,4";
            add(tb, 3, chans, "88", "u_b", "u_b", 2.0 + 0.04 * (i % 4),
                    2.0 - 0.05 * (i % 5), 44);
            tb += 60;
        }
        // concurrent spikes: A and B at the same ms (inclusive window endpoint later)
        add(4000, 3, "3,4", "87", "u_b", "u_b", 2.02, 1.98, 44);
        add(4000, 1, "1,2", "117", "u_a", "u_a", -1.98, 1.01, 48);

        // --- unit C: later split into C1 and C2; shared events kept on both ---
        long tc = 1300;
        for (int i = 0; i < 22; i++) {
            String v2 = i < 11 ? "u_c1" : "u_c2";
            double p1 = i < 11 ? 0.2 + 0.03 * i : 3.2 + 0.03 * (i - 11);
            add(tc, 6, "6,7", "70", "u_c", v2, p1, -2.0 - 0.03 * (i % 3), 40);
            tc += 50;
        }
        // shared post-split events: assigned to BOTH c1 and c2 (two physical detections,
        // identical time, borderline feature)
        long sharedT = 2500;
        for (int i = 0; i < 3; i++) {
            add(sharedT, 6, "6,7", "69", "u_c", "u_c1", 1.70 + 0.01 * i, -2.05, 40);
            add(sharedT, 6, "6,7", "69", "u_c", "u_c2", 1.72 + 0.01 * i, -1.98, 40);
            sharedT += 50;
        }

        // --- unit D: disappears after parameter revision (threshold tightened) ---
        long td = 1500;
        for (int i = 0; i < 10; i++) {
            add(td, 8, "8", "55", "u_d", null, 0.0 + 0.1 * (i % 3), 3.2 - 0.1 * (i % 4), 36);
            td += 110;
        }
        // new unit E appears only under v2
        long te = 5200;
        for (int i = 0; i < 8; i++) {
            add(te, 2, "2,9", "64", null, "u_e", -3.1 + 0.05 * i, -0.5 + 0.05 * (i % 3), 42);
            te += 70;
        }

        // unconvertible quantities must be retained, never dropped for plotting
        add(3000, 5, "5,6", "N/A uV", "u_b", "u_b", 2.05, 2.0, 44);
        add(3050, 1, "1,2", "119", "u_a", "u_a", -2.0, 1.0, 48);
    }

    private void writeManifest() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("casePackId", "spike-lineage-case");
        m.put("title", "\u8109\u51b2\u7c07\u8c31\uff1a\u795e\u7ecf\u68d8\u6ce2\u5019\u9009\u3001\u6f02\u79fb\u7c07\u4e0e\u4eba\u5de5\u88c1\u5b9a\u8c31\u7cfb");
        m.put("titleEn", "pulse cluster atlas: spike candidates, drift clusters and adjudication lineage");
        m.put("version", 1);
        m.put("recordStart", "2026-09-01T09:00:00+09:00");
        m.put("timeOrigin", "recclock");
        m.put("sampleRateHz", 30000);
        m.put("coordinateFrame", "probe:wf0");
        m.put("layers", List.of("waveform", "geometry", "threshold", "feature", "cluster", "decision"));
        m.put("budget", Map.of("maxRowsPerFile", 20000, "maxBytesPerFile", 16777216));
        Files.writeString(out.resolve("manifest.json"), Json.pretty(m) + "\n", StandardCharsets.UTF_8);
    }

    private void writeRules() throws IOException {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("ruleVersion", "rules-2026-09-01");
        rules.put("refractory", Map.of("ms", 1.5, "meaning",
                "evidence of contamination or collision; never an identity by itself"));
        rules.put("amplitudeDrift", Map.of("ratioBelow", 0.85, "meaning",
                "evidence of waveform amplitude drift; never an identity by itself"));
        rules.put("propagation", Map.of("minChannels", 2, "meaning",
                "simultaneous multi-channel propagation is evidence; cluster number is not identity"));
        rules.put("identityMatch", Map.of("jaccardMin", 0.5,
                "meaning", "match stable units across versions by event-set overlap, not label"));
        rules.put("window", Map.of("left", "inclusive", "right", "exclusive",
                "meaning", "window change recomputes only intersecting clusters"));
        rules.put("conflict", Map.of("resolution", "latest_adopted_wins",
                "keep", "both adopted and rejected results retained"));
        Files.writeString(out.resolve("rules.json"), Json.pretty(rules) + "\n", StandardCharsets.UTF_8);
    }

    private void writeScenarios() throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(sc("S1_CONCURRENT_SPIKES",
                "two channels detect spikes at the same timestamp; both detections survive",
                "concurrent spikes are not merged just for being simultaneous"));
        list.add(sc("S2_AMPLITUDE_DRIFT",
                "unit A amplitude drops below 0.85 ratio after 4000 ms",
                "drift recorded as evidence; stable unit identity retained"));
        list.add(sc("S3_CLUSTER_RENUMBERING",
                "v2 permutes numeric labels while stable unit ids persist",
                "cluster number must not be treated as stable identity"));
        list.add(sc("S4_POST_SPLIT_SHARED_EVENTS",
                "unit C split into C1/C2; borderline events shared by both",
                "all legal candidates retained when ownership is not unique"));
        list.add(sc("S5_WINDOW_ENDPOINTS",
                "window [1000,4000) includes t=1000 and excludes t=4000; second run [1000,4000]",
                "only intersecting clusters recomputed; endpoint policy visible"));
        list.add(sc("S6_ADJUDICATION_CONFLICT",
                "a merge is accepted then rejected by a later reviewer decision",
                "both decisions and both results retained; latest adopted wins"));
        list.add(sc("S7_LOCAL_FAILURE",
                "a single direct source changes; only intersecting downstream objects are invalidated",
                "failure is contained to the correct scope, never global"));
        Files.writeString(out.resolve("scenarios.json"), Json.pretty(list) + "\n", StandardCharsets.UTF_8);
    }

    private Map<String, Object> sc(String id, String setup, String judgment) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("setup", setup);
        m.put("judgment", judgment);
        return m;
    }

    private void writeWaveforms() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# refId|uri|checksum|format|timeOrigin|timeUnit|sampleRateHz|sampleCount|sampleIndex|sampleLength|gain\n");
        sb.append("wf0|local://recordings/seg0.bin|sha256:casepack0|int16-le|recclock|ms|30000|")
                .append(sampleCursor).append("|0|48|0.195 uV/count\n");
        Files.writeString(out.resolve("waveforms.wvr"), sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeGeometry() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# refId|channel|x|y|z in coordinate frame probe:wf0 (micrometre)\n");
        // 10 channels, 2 columns of a linear probe; channel 5 carries an unconvertible coordinate
        for (int ch = 1; ch <= 10; ch++) {
            int col = (ch - 1) % 2;
            int row = (ch - 1) / 2;
            String y = ch == 5 ? "?? um" : (row * 25) + " um";
            sb.append("wf0|").append(ch).append('|').append(col == 0 ? "0 um" : "25 um")
                    .append('|').append(y).append("|0 um\n");
        }
        Files.writeString(out.resolve("geometry.geo"), sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeThresholds() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# thresholdId|channel|value|rule|featureVersion\n");
        for (int ch = 1; ch <= 10; ch++) {
            double v = (ch == 8) ? 5.0 : 4.0; // tightened for v2 on channel 8 -> unit D lost
            sb.append("thr_v1_").append(ch).append('|').append(ch).append('|').append("4.0 sigma")
                    .append("|negative_threshold|fv1\n");
            sb.append("thr_v2_").append(ch).append('|').append(ch).append('|').append(v).append(" sigma")
                    .append("|negative_threshold|fv2\n");
        }
        Files.writeString(out.resolve("thresholds.thr"), sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeSpikes() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# eventId|time(ms, recclock)|primaryChannel|waveformRef|sampleIndex|sampleLength|channels|amplitude\n");
        for (Object[] r : rows) {
            sb.append(r[0]).append('|').append(r[1]).append(" ms|").append(r[2]).append('|')
                    .append(r[3]).append('|').append(r[4]).append('|').append(r[5]).append('|')
                    .append(r[6]).append('|').append(r[7]).append('\n');
        }
        Files.writeString(out.resolve("spikes.spk"), sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeFeatures() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# featureId|eventId|version|basis|pc1(std)|pc2(std); basis identifies the waveform ref\n");
        for (Object[] r : rows) {
            long id = (long) r[0];
            double[] p = pc.get(id);
            String ver = unitV2.containsKey(id) || !unitV1.containsKey(id) ? "fv2" : "fv1";
            if (unitV1.containsKey(id) && unitV2.containsKey(id)) {
                // emit both feature versions for events present in both
                sb.append("f_").append(id).append("_fv1|").append(id).append("|fv1|wf0|")
                        .append(String.format(java.util.Locale.ROOT, "%.3f std", p[0])).append('|')
                        .append(String.format(java.util.Locale.ROOT, "%.3f std\n", p[1]));
                sb.append("f_").append(id).append("_fv2|").append(id).append("|fv2|wf0|")
                        .append(String.format(java.util.Locale.ROOT, "%.3f std", p[0] + 0.1)).append('|')
                        .append(String.format(java.util.Locale.ROOT, "%.3f std\n", p[1]));
            } else {
                sb.append("f_").append(id).append('_').append(ver).append('|').append(id).append('|')
                        .append(ver).append("|wf0|")
                        .append(String.format(java.util.Locale.ROOT, "%.3f std", p[0])).append('|')
                        .append(String.format(java.util.Locale.ROOT, "%.3f std\n", p[1]));
            }
        }
        Files.writeString(out.resolve("features.ftr"), sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeClusters() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# clustering runs. Header first (## JSON), then eventId|unitId|label|score rows\n");
        sb.append("## ").append(Json.write(Map.of(
                "clusterId", "clu_v1", "version", "clustering-1", "seq", 1,
                "featureVersion", "fv1", "thresholdId", "thr_v1_*",
                "windowStart", "1000 ms", "windowEnd", "5000 ms",
                "params", Map.of("k", 4, "metric", "mahalanobis", "jaccardMin", 0.5)))).append('\n');
        Map<String, Integer> labelV1 = Map.of("u_a", 1, "u_b", 2, "u_c", 3, "u_d", 4);
        for (Object[] r : rows) {
            long id = (long) r[0];
            String u = unitV1.get(id);
            if (u != null) {
                sb.append(id).append('|').append(u).append('|').append(labelV1.get(u))
                        .append("|0.91\n");
            }
        }
        // second run: revised parameters (threshold ch8 tightened -> D gone, E appears),
        // labels deliberately permuted, and window right endpoint inclusive
        sb.append("## ").append(Json.write(Map.of(
                "clusterId", "clu_v2", "version", "clustering-2", "seq", 2,
                "featureVersion", "fv2", "thresholdId", "thr_v2_*",
                "windowStart", "1000 ms", "windowEnd", "4000 ms!",
                "params", Map.of("k", 5, "metric", "mahalanobis", "jaccardMin", 0.5,
                        "rightEndpoint", "inclusive")))).append('\n');
        Map<String, Integer> labelV2 = Map.of("u_a", 7, "u_b", 3, "u_c1", 1, "u_c2", 8, "u_e", 9);
        // events shared after split appear twice (once per legal owner)
        for (Object[] r : rows) {
            long id = (long) r[0];
            String u = unitV2.get(id);
            if (u != null) {
                sb.append(id).append('|').append(u).append('|').append(labelV2.get(u))
                        .append("|0.88\n");
            }
        }
        Files.writeString(out.resolve("clusters.clu"), sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeDecisions() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# decisionId|seq|type|actor|atEvent|basisRuleId|parentClusterId|adopted|payload\n");
        dec(sb, "dec1", 1, "ACCEPT", "reviewer-li", "", "rule:identityMatch", "clu_v1", "ADOPTED",
                Map.of("clusterId", "clu_v1", "note", "accept baseline clustering"));
        dec(sb, "dec2", 2, "PARAMETER_REVISION", "reviewer-li", "", "rule:window", "clu_v1",
                "ADOPTED", Map.of("thresholdChannel8", "4.0 sigma->5.0 sigma", "featureVersion",
                        "fv1->fv2", "reason", "reduce low-amplitude noise on channel 8"));
        dec(sb, "dec3", 3, "SPLIT", "reviewer-wang", "", "rule:identityMatch", "clu_v2", "ADOPTED",
                Map.of("fromUnit", "u_c", "intoUnits", List.of("u_c1", "u_c2"),
                        "sharedEvents", "retain all legal owners"));
        dec(sb, "dec4", 4, "MERGE", "reviewer-li", "", "rule:evidence", "clu_v2", "ADOPTED",
                Map.of("units", List.of("u_a", "u_b"), "claim",
                        "concurrent spikes + propagation suggest one unit"));
        dec(sb, "dec5", 5, "REJECT_MERGE", "reviewer-zhao", "4000", "rule:evidence", "clu_v2",
                "REJECTED", Map.of("contradicts", "dec4", "reason",
                        "refractory/amplitude/propagation are evidence only; labels and timing separate A and B"));
        dec(sb, "dec6", 6, "WINDOW_CHANGE", "reviewer-zhao", "", "rule:window", "clu_v2",
                "ADOPTED", Map.of("windowStart", "1000 ms", "windowEnd", "4000 ms!",
                        "recompute", "intersecting clusters only"));
        dec(sb, "dec7", 7, "LOCAL_SOURCE_CHANGE", "pipeline", "", "rule:locality", "clu_v2",
                "ADOPTED", Map.of("directSource", "thr_v2_5", "change",
                        "channel 5 threshold retained, geometry y stays unconvertible",
                        "scope", "only units intersecting channel 5"));
        Files.writeString(out.resolve("decisions.dec"), sb.toString(), StandardCharsets.UTF_8);
    }

    private void dec(StringBuilder sb, String id, long seq, String type, String actor, String atEvent,
                     String rule, String cluster, String adopted, Map<String, Object> payload) {
        Map<String, Object> p = new LinkedHashMap<>(payload);
        p.put("adopted", adopted);
        sb.append(id).append('|').append(seq).append('|').append(type).append('|').append(actor)
                .append('|').append(atEvent).append('|').append(rule).append('|').append(cluster)
                .append('|').append(Json.write(p)).append('\n');
    }

    private void writeReadme() throws IOException {
        String readme = """
                脉冲簇谱离线案例包 / pulse cluster atlas
                =========================================
                叙事：神经电生理团队导入多通道检测事件、波形片段与记录时间，为单元聚类建立
                可回溯的合并、拆分和漂移证据。

                分层（不可变材料 / 可修改决定）：
                  waveforms.wvr      原始波形引用（不复制、不规范化）
                  geometry.geo       探针几何（坐标基准 probe:wf0，单位 um；保留不可转换坐标）
                  thresholds.thr     检测阈值（原始 4.0 sigma 表示）
                  spikes.spk         检测事件、时间(recclock, ms)、波形引用、通道、振幅
                  features.ftr       特征版本 fv1/fv2（主成分，保留原始表示）
                  clusters.clu       聚类标签（数字标签 != 稳定身份；u_a/u_b/... 才是身份）
                  decisions.dec      追加式人工裁定（含一次互相冲突的合并/否决）

                重放情形：S1 并发棘波 S2 幅度漂移 S3 聚类编号变化 S4 拆分后共享事件
                          S5 时间窗端点 S6 裁定冲突 S7 局部失效。
                所有领域量携带单位或坐标基准；无法转换的值（如 "N/A uV"、"?? um"）留在原位置。
                """;
        Files.writeString(out.resolve("README.txt"), readme, StandardCharsets.UTF_8);
    }
}
