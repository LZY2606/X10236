#!/usr/bin/env python3
"""
离线领域案例包生成器：脉冲簇谱 —— 神经棘波候选、漂移簇与人工裁定谱系。
确定性（无随机数）：相同输入永远生成相同字节与稳定身份。
单位基准：坐标 um（探针几何），时间 s（相对记录起点 t0），幅度 uV，采样率 Hz。
"""
import hashlib, json, math, os, struct, csv, io

ROOT = os.path.join("src", "main", "resources", "casepack", "pulse-cluster-lineage")
PROBE = "probe-shank-A"
SAMPLE_RATE_HZ = 30000.0
SAMPLES_PER_SPIKE = 48
WFDIR = os.path.join(ROOT, "waveforms")
os.makedirs(WFDIR, exist_ok=True)

def wid(channel, spike_index, amp):
    raw = f"wf|{PROBE}|ch{channel}|#{spike_index}|{amp:.1f}uV"
    return "wf_" + hashlib.sha256(raw.encode()).hexdigest()[:20]

def write_waveform(channel, spike_index, amp_uv):
    """合成确定性波形：负相主波+正相回弹；amp 为峰值(uV, 1 uV/ADC)。"""
    wf_id = wid(channel, spike_index, amp_uv)
    path = os.path.join(WFDIR, wf_id + ".bin")
    if not os.path.exists(path):
        samples = []
        for i in range(SAMPLES_PER_SPIKE):
            t_ms = (i - 14) / (SAMPLE_RATE_HZ / 1000.0)
            neg = -amp_uv * math.exp(-(t_ms ** 2) / 0.10)
            pos = 0.45 * amp_uv * math.exp(-((t_ms - 0.62) ** 2) / 0.22)
            samples.append(int(round(neg + pos)))
        blob = struct.pack("<%dh" % SAMPLES_PER_SPIKE, *samples)
        with open(path, "wb") as f:
            f.write(blob)
    return wf_id

# 事件表：(id, t(s), ch, amp_uV/None, valid, invalid_reason, raw_amp_string)
events = []
def ev(eid, t, ch, amp, valid=True, reason=None, raw=None, findex=0):
    events.append(dict(id=eid, t=t, ch=ch, amp=amp, valid=valid,
                       reason=reason, raw=raw, findex=findex))

# ---- 簇 A：真实单元，幅度漂移（120 -> 78 uV），含一个不应期违例（ISI 0.8ms）----
A_times = [10.000, 10.050, 10.100, 10.150, 10.200, 10.250, 10.300,
           20.000, 20.060, 20.120, 20.180, 20.240, 20.300, 20.360]
A_amps  = [120,   118,   116,   114,   112,   110,   108,
           96,     93,    90,    87,    84,    81,    78]
for i, (t, a) in enumerate(zip(A_times, A_amps)):
    ev(f"e{i:03d}", t, 0, float(a))
# 不应期违例：与 e013 仅隔 0.8ms 的第二个 A 通道棘波（证据：可能是两个单元污染）
ev("e100", 20.3608, 0, 79.0)

# ---- 簇 B：稳定单元（ch 2），幅度稳定 ----
B_times = [10.020, 10.130, 10.240, 10.350, 20.030, 20.140, 20.250, 20.360, 20.470]
for i, t in enumerate(B_times):
    ev(f"e2{i:02d}", t, 2, 95.0)

# ---- 并发棘波组 G：v1 归 K_C，v3 拆成 K_D/K_E/K_F，e044 拆分后被 K_F 与 K_B 共享 ----
# G1: e040(ch1,12.0000), e041(ch3,12.0003), e042(ch4,12.0006)  跨通道同时传播
ev("e040", 12.0000, 1, 60.0)
ev("e041", 12.0003, 3, 58.0)
ev("e042", 12.0006, 4, 55.0)
# G2: e043(ch1), e044(ch2)；e044 同时落在簇 B 的发放节律 -> 拆分后合法归属不唯一
ev("e043", 18.0000, 1, 61.0)
ev("e044", 18.0003, 2, 94.0)

# ---- 簇 G（漂移时间线之外的小簇，标注 K_G）：时间窗端点检验 ----
# 窗口默认 [14.0, 22.0)：e060 在端点 14.0 必须包含；e061 在 22.0 必须排除
ev("e060", 14.0000, 5, 70.0)
ev("e061", 22.0000, 5, 71.0)
ev("e062", 17.5000, 5, 69.0)

# ---- 阈值边界事件：幅度 45uV。v1 阈值 -45 检测到（K_H）；v2 阈值 -50 不再检测 ----
ev("e080", 16.0000, 6, 45.0)
ev("e081", 16.1000, 6, 62.0)

# ---- 保留原位的失效行：无法转换的幅度单位；缺失波形引用（局部失效，不拖垮整批）----
ev("e090", 15.0000, 7, None, valid=False, reason="amplitude-unit-unconvertible", raw="87 px")
ev("e091", 15.1000, 7, 80.0, valid=False, reason="waveform-reference-missing")

# 写波形，得到 event_id -> waveform_id
e_to_wf = {}
wf_counter = {}
for e in events:
    if e["reason"] == "waveform-reference-missing":
        e_to_wf[e["id"]] = "wf_MISSING_" + e["id"]
        continue
    key = (e["ch"], e["amp"] if e["amp"] is not None else 0.0)
    idx = wf_counter.get(key, 0)
    wf_counter[key] = idx + 1
    e_to_wf[e["id"]] = write_waveform(e["ch"], idx, e["amp"] if e["amp"] is not None else 87.0)

def features(e):
    """fs-v1 特征：(log10(amp), channel 投影, 首峰相位)。无效行给空向量并保留诊断。"""
    if not e["valid"]:
        return ""
    return f"{math.log10(e['amp']):.6f},{e['ch'] / 7.0:.6f},0.620000"

# detection/events.csv（保留原始行表示，不规范化）
os.makedirs(os.path.join(ROOT, "detection"), exist_ok=True)
with open(os.path.join(ROOT, "detection", "events.csv"), "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["event_id", "time_seconds", "channel_local_index", "waveform_ref",
                "amplitude", "amplitude_unit", "feature_set_id", "feature_vector"])
    for e in events:
        amp = e["raw"] if e["raw"] else (f"{e['amp']:.1f}" if e["amp"] is not None else "")
        unit = "px" if e["raw"] else ("uV" if e["amp"] is not None else "")
        fset = "fs-v1" if e["valid"] else "fs-v1"
        w.writerow([e["id"], f"{e['t']:.4f}", e["ch"], e_to_wf[e["id"]],
                    amp, unit, fset, features(e)])

# geometry/probe-shank-A.csv：单列探针，20um 间距
os.makedirs(os.path.join(ROOT, "geometry"), exist_ok=True)
with open(os.path.join(ROOT, "geometry", "probe-shank-A.csv"), "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["local_index", "label", "x_um", "y_um"])
    for i in range(8):
        w.writerow([i, f"A{i:02d}", 0, 20 * i])

# thresholds：v1 -45 uV，v2 -50 uV（参数修订，只影响阈值附近检测）
os.makedirs(os.path.join(ROOT, "thresholds"), exist_ok=True)
def write_thresholds(name, val):
    with open(os.path.join(ROOT, "thresholds", name), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["probe_id", "channel_local_index", "operator",
                    "value", "unit", "basis"])
        for i in range(8):
            w.writerow([PROBE, i, "lt", val, "uV", "negative-SD*4.5" if val == "-45" else "negative-SD*5.0"])
write_thresholds("thresholds-v1.csv", "-45")
write_thresholds("thresholds-v2.csv", "-50")

# features/fs-v1（版本化参数文件；CSV 只存特征向量，参数在此声明）
os.makedirs(os.path.join(ROOT, "features"), exist_ok=True)
with open(os.path.join(ROOT, "features", "fs-v1.json"), "w") as f:
    json.dump({
        "featureSetId": "fs-v1", "version": 1, "algorithm": "log-amp,channel-proj,peak-phase",
        "params": {"logBase": 10, "ampUnit": "uV", "channelCount": 8, "phaseSample": 19},
        "createdAt": "2026-09-01T00:00:00Z"
    }, f, indent=2, ensure_ascii=False)
with open(os.path.join(ROOT, "features", "fs-v2.json"), "w") as f:
    json.dump({
        "featureSetId": "fs-v2", "version": 2, "algorithm": "log-amp,channel-proj,peak-phase",
        "params": {"logBase": 10, "ampUnit": "uV", "channelCount": 8, "phaseSample": 19,
                   "note": "threshold-revision-companion"},
        "createdAt": "2026-09-02T00:00:00Z"
    }, f, indent=2, ensure_ascii=False)

# labels：v1 与 v2 成员集合完全相同（键不变），只是显示编号置换；v3 拆分并发组
os.makedirs(os.path.join(ROOT, "labels"), exist_ok=True)
def member_set():
    sets = {}
    sets["K_A"] = [f"e{i:03d}" for i in range(14)] + ["e100"]
    sets["K_B"] = [f"e2{i:02d}" for i in range(9)]
    sets["K_C"] = ["e040", "e041", "e042", "e043", "e044"]
    sets["K_G"] = ["e060", "e062"]          # 仅窗内成员；e061 在窗右端点外
    sets["K_H"] = ["e080", "e081"]
    return sets
K = member_set()

def write_labels(fname, ls_id, version, parent, numbers, note, created):
    with open(os.path.join(ROOT, "labels", fname), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["event_id", "cluster_key", "display_number"])
        for key in ["K_A", "K_B", "K_C", "K_G", "K_H", "K_D", "K_E", "K_F"]:
            if key not in numbers:
                continue
            members = K.get(key, [])
            for eid in members:
                w.writerow([eid, key, numbers[key]])
    return {
        "labelSetId": ls_id, "version": version, "parentLabelSetId": parent,
        "labelsCsv": f"labels/{fname}", "note": note, "createdAt": created
    }

lab1 = write_labels("labels-v1.csv", "ls-v1", 1, None,
                    {"K_A": 1, "K_B": 2, "K_C": 3, "K_G": 7, "K_H": 8},
                    "initial clustering", "2026-09-01T00:05:00Z")
lab2 = write_labels("labels-v2.csv", "ls-v2", 2, "ls-v1",
                    {"K_A": 5, "K_B": 1, "K_C": 4, "K_G": 7, "K_H": 2},
                    "renumbered after sort by channel (display numbers changed, identity stable)",
                    "2026-09-02T00:05:00Z")
# v3：K_C 拆成 K_D(ch1)、K_E(ch3,4)、K_F(ch2 共享 e044)；e044 的另一个合法候选是 K_B
K["K_D"] = ["e040", "e043"]
K["K_E"] = ["e041", "e042"]
K["K_F"] = ["e044"]
lab3 = write_labels("labels-v3.csv", "ls-v3", 3, "ls-v2",
                    {"K_A": 5, "K_B": 1, "K_D": 9, "K_E": 10, "K_F": 11, "K_G": 7, "K_H": 2},
                    "split concurrent multichannel group; e044 shared by K_F and K_B (ambiguous)",
                    "2026-09-03T00:05:00Z")

# 拆分后共享事件的候选清单（合法归属不唯一时保留全部候选，不强制选择）
with open(os.path.join(ROOT, "labels", "candidates-v3.csv"), "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["label_set_id", "cluster_key", "event_id", "reason"])
    w.writerow(["ls-v3", "K_F", "e044", "split-shared:event matches both K_F (split child) and K_B (rhythm)"])
    w.writerow(["ls-v3", "K_B", "e044", "split-shared:event matches both K_F (split child) and K_B (rhythm)"])

manifest = {
    "caseId": "pulse-cluster-lineage",
    "title": "脉冲簇谱：神经棘波候选、漂移簇与人工裁定谱系",
    "probeId": PROBE,
    "coordinateBasis": {"unit": "um", "origin": "probe-tip"},
    "timeBasis": {"t0IsoUtc": "2026-09-01T08:00:00Z",
                  "sampleRateHz": SAMPLE_RATE_HZ, "rateRaw": "30", "rateUnit": "kHz"},
    "files": {
        "geometry": [{"localIndex": i, "label": f"A{i:02d}", "x": 0, "y": 20 * i}
                     for i in range(8)],
        "eventsCsv": "detection/events.csv",
        "thresholdsCsv": "thresholds/thresholds-v1.csv",
        "featureSets": [
            {"featureSetId": "fs-v1", "version": 1,
             "algorithm": "log-amp,channel-proj,peak-phase",
             "paramsCsv": "features/fs-v1.params.csv",
             "createdAt": "2026-09-01T00:00:00Z"},
            {"featureSetId": "fs-v2", "version": 2,
             "algorithm": "log-amp,channel-proj,peak-phase",
             "paramsCsv": "features/fs-v2.params.csv",
             "createdAt": "2026-09-02T00:00:00Z"}],
        "labels": [lab1, lab2, lab3]
    },
    "replay": [
        {"stepId": "r1-import", "action": "import-case", "target": "detection/events.csv",
         "expect": "materials stored in separate layers; invalid rows retained in place",
         "affectsScope": "all"},
        {"stepId": "r2-concurrent", "action": "detect-concurrent", "target": "tol=0.001s",
         "expect": "groups G1/G2 marked as evidence only; cluster numbers must not become identity",
         "affectsScope": "events within 1ms windows"},
        {"stepId": "r3-amplitude-drift", "action": "detect-amplitude-drift", "target": "K_A",
         "expect": "K_A flagged EVIDENCE with slope (uV/min); not auto-split",
         "affectsScope": "K_A only"},
        {"stepId": "r4-threshold-revision", "action": "set-active-threshold", "target": "th-v2",
         "expect": "e080 (45uV) leaves detection; only threshold-near dependencies recompute",
         "affectsScope": "local:dependencies-of-e080"},
        {"stepId": "r5-source-patch", "action": "patch-source-amplitude",
         "target": "e062:69->92 uV",
         "expect": "new material revision; only nodes reading e062 recompute; unrelated history intact",
         "affectsScope": "local:dependencies-of-e062"},
        {"stepId": "r6-renumber", "action": "activate-label-set", "target": "ls-v2",
         "expect": "display numbers permute but clusterKey/lineage identity stable",
         "affectsScope": "display layer"},
        {"stepId": "r7-split-shared", "action": "activate-label-set", "target": "ls-v3",
         "expect": "K_C split to K_D/K_E/K_F; e044 keeps both legal candidates K_F,K_B",
         "affectsScope": "K_C subtree"},
        {"stepId": "r8-window-edge", "action": "set-window", "target": "[14.0,22.0)",
         "expect": "e060 included, e061 excluded; only intersecting clusters recompute",
         "affectsScope": "intersecting-clusters"},
        {"stepId": "r9-adjudicate-conflict", "action": "adjudicate",
         "target": "e044 -> K_F, then e044 -> K_B",
         "expect": "conflicting decisions both retained; CONFLICT state; downstream marked affected",
         "affectsScope": "downstream-of-e044"},
        {"stepId": "r10-local-failure", "action": "derive",
         "target": "e090,e091",
         "expect": "LOCAL_FAILURE nodes with positions; rest of batch still succeeds",
         "affectsScope": "e090,e091 only"}
    ]
}
with open(os.path.join(ROOT, "manifest.json"), "w") as f:
    json.dump(manifest, f, indent=2, ensure_ascii=False)

# 特征参数 CSV（领域参数，非通用表格数据）
for name, note in [("fs-v1.params.csv", ""), ("fs-v2.params.csv", "threshold-revision-companion")]:
    with open(os.path.join(ROOT, "features", name), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["param", "value", "unit/basis"])
        w.writerow(["logBase", "10", "dimensionless"])
        w.writerow(["ampUnit", "uV", "microvolt"])
        w.writerow(["channelCount", "8", "count"])
        w.writerow(["phaseSample", "19", "sample-index@30kHz"])
        if note:
            w.writerow(["note", note, "annotation"])

# README（叙事说明）
with open(os.path.join(ROOT, "README.md"), "w") as f:
    f.write(
        "# 脉冲簇谱：神经棘波候选、漂移簇与人工裁定谱系\n\n"
        "离线领域案例包。所有材料按层分开存储：原始波形引用（waveforms/*.bin，内容寻址）、\n"
        "探针几何（geometry/）、检测阈值（thresholds/，版本化）、特征版本（features/）、\n"
        "聚类标签（labels/，版本化）与裁定事件（由应用在重放时追加，仅追加日志）。\n\n"
        "## 重放叙事\n"
        "1. 导入后核对六层材料分开存储，e090/e091 保留原位并附诊断。\n"
        "2. 并发棘波（G1/G2，<=1ms）、幅度漂移（K_A 120->78uV）、多通道传播都只形成证据。\n"
        "3. 阈值修订 v1->v2 仅移除边界事件 e080（45uV），只重算相交依赖。\n"
        "4. 源数据局部改动 e062 幅度，仅影响读取 e062 的派生节点。\n"
        "5. ls-v2 重编号（身份不变），ls-v3 拆分并发组，e044 同时属于 K_F/K_B 的合法候选。\n"
        "6. 时间窗 [14,22)：端点 e060 包含、e061 排除，只重算相交簇。\n"
        "7. 对 e044 的两次冲突裁定同时保留为 CONFLICT，下游记录被标为受影响。\n"
        "8. e090/e091 为 LOCAL_FAILURE，不拖垮整批。\n\n"
        "生成器：tools/gen_casepack.py（确定性，可重复生成相同字节）。\n")

print("casepack generated at", ROOT)
