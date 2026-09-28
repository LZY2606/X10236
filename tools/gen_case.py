#!/usr/bin/env python3
"""Deterministic generator for the bundled offline case pack.

Produces a domain-specific neurophysiology case (not a generic table):
raw waveform references, probe geometry, detection thresholds, feature
versions, cluster labels and adjudication decisions are separate layers.
"""
import json, math, os

FS_HZ = 30000
NSAMPLES = 48  # 1.6 ms window

def spike(n, amp, width=10):
    """Tri-phasic deterministic waveform with unit uV after scaling."""
    out = []
    for i in range(n):
        t = i - 14
        a = amp * (math.exp(-(t/width)**2) * (-math.sin(t/3.2)) / 0.93)
        out.append(round(a, 3))
    return out

def lin_amp(a, b, i, n):
    return round(a + (b - a) * (i / max(1, n - 1)), 3)

dets = []   # detection dicts
def add(did, site, t, amp, rev_amp=None, wf_scale=None, raw_amp=None,
        raw_site=None, wf_id=None, channels=None):
    amp_eff = rev_amp if rev_amp is not None else amp
    if wf_id is None:
        wf_id = "wf-" + did
    if wf_id not in waveforms:
        wf = spike(NSAMPLES, wf_scale if wf_scale is not None else amp_eff)
    d = {
        "detectionId": did,
        "rawSiteRef": raw_site if raw_site is not None else site,
        "timeS": t,
        "timeBase": "recording-start",
        "timeUnit": "s",
        "rawAmplitude": raw_amp if raw_amp is not None else amp,
        "amplitudeUnit": "uV",
        "waveformRef": wf_id,
        "channels": channels or [site],
        "labels": {"r1": None, "r2": None, "r3": None},
        "tags": [],
    }
    dets.append(d)
    waveforms[wf_id] = {"waveformId": wf_id, "sampleRateHz": FS_HZ,
                        "samples": wf, "sampleUnit": "uV"}

waveforms = {}

# --- Unit A: stable identity, amplitude drift, a borderline near threshold
nA = 12
for i in range(nA):
    t = round(0.30 + i * 0.80, 6)
    add(f"a{i+1:02d}", "A.s0", t, lin_amp(95, 128, i, nA),
        raw_amp=("140" if i == 11 else None))
dets[-1]["tags"].append("borderline-under-100uV")  # a12 raw 140 actually above; see a13
# concurrent spike shared with unit B at same time, separate channels
add("a09x", "A.s1", 4.30, 110.0, channels=["A.s1", "B.s7"])
waveforms["wf-a09x"] = waveforms["wf-a09x"]  # own waveform
dets[-1]["tags"].append("concurrent-with-b06")
# window endpoint event at exactly 5.00 s
add("a13", "A.s0", 5.00, 99.5)
dets[-1]["tags"].append("window-endpoint")

# --- Unit B: on shank B, shares the concurrent moment
for i, t in enumerate([0.55, 1.35, 2.15, 2.95, 3.75, 4.30, 5.05, 5.85, 6.65, 7.45]):
    did = f"b{i+1:02d}"
    if t == 4.30:
        add(did, "B.s7", round(t, 6), 88.0, wf_id="wf-a09x",
            channels=["B.s7", "A.s1"])
        dets[-1]["tags"].append("concurrent-with-a09x")
    else:
        add(did, "B.s7", round(t, 6), 84.0 + i)

# --- Unit C: refractory violation evidence (two sub-trains interleaved)
ct = 0.40
for i in range(10):
    add(f"c{i+1:02d}", "A.s3", round(ct, 6), 76 + (i % 3) * 2)
    gap = 0.0009 if i % 2 == 0 else 0.62   # 0.9 ms gaps = refractory violations
    ct += gap

# --- Unit D: strong amplitude drift only (evidence, not identity split)
for i in range(9):
    add(f"d{i+1:02d}", "A.s6", round(0.70 + i * 0.95, 6),
        lin_amp(60, 150, i, 9))

# --- Unit E: split in r3 with one shared detection
e_times = [0.20, 0.20+1.10, 0.20+2.20, 0.20+3.30, 0.20+4.40,
           0.20+5.50, 0.20+6.60, 0.20+7.70, 0.20+8.80]
for i, t in enumerate(e_times):
    add(f"e{i+1:02d}", "B.s4", round(t, 6),
        (90 if i % 2 == 0 else 130))
# the 5th detection e05 is shared after split
shared = next(d for d in dets if d["detectionId"] == "e05")
shared["tags"].append("shared-after-split")

# --- Unit F: stable across renumbering
for i in range(8):
    add(f"f{i+1:02d}", "A.s10", round(0.90 + i * 1.05, 6), 101 + (i % 4))

# --- Unit G: single-channel events, plus locally invalid records
for i in range(7):
    add(f"g{i+1:02d}", "B.s12", round(1.10 + i * 1.15, 6), 92 + (i % 2) * 6)
# locally invalid: amplitude cannot be converted (stays raw)
add("g08", "B.s12", 3.03, None, wf_scale=100.0, raw_amp="N/A", raw_site=None)
dets[-1]["tags"].append("unconvertible-amplitude")
# locally invalid: site reference missing from geometry
add("g09", "C.s3", 4.83, 105.0, raw_site="C.s3", channels=["C.s3"])
dets[-1]["tags"].append("unknown-site")
# zero-time semantic anomaly example stays valid-ish (t=0 endpoint)
add("g10", "B.s12", 0.00, 100.0)
dets[-1]["tags"].append("window-startpoint")

# ---- labels per revision ----
label_map_r1 = {}
def r1(prefix, cl):
    for d in dets:
        if d["detectionId"].startswith(prefix) and d["detectionId"] != "a09x":
            label_map_r1[d["detectionId"]] = cl
# a09x belongs to A in r1 as well
r1("a", 1)
label_map_r1["a09x"] = 1
r1("b", 2)
r1("c", 3)
r1("d", 4)
r1("e", 5)
r1("f", 6)
r1("g", 7)

for d in dets:
    did = d["detectionId"]
    d["labels"]["r1"] = label_map_r1.get(did)
    # r2: same numbering; a13 (99.5 < new 100 threshold) is dropped => null
    d["labels"]["r2"] = label_map_r1.get(did) if did != "a13" else None
    # r3: renumbered (reverse order), E splits 5 -> {55: even index?, ...}
    renum = {1: 16, 2: 15, 3: 14, 4: 13, 6: 11, 7: 10}
    base = label_map_r1.get(did)
    if did == "a13":
        d["labels"]["r3"] = None
    elif did.startswith("e"):
        # e01..e09 split: odd rank -> 51, even rank -> 52, e05 shared
        rank = int(did[1:])
        d["labels"]["r3"] = 52 if rank == 5 else (51 if rank % 2 == 1 else 52)
    elif base in renum:
        d["labels"]["r3"] = renum[base]
    else:
        d["labels"]["r3"] = None

# ---- recording & probe geometry ----
sites = []
for i in range(12):
    sites.append({"siteId": f"A.s{i}", "shank": "A", "xUm": 0.0,
                  "yUm": round(i * 20.0, 3), "coordinateBase": "probe-tip"})
for sid, y in [("B.s2", 20.0), ("B.s4", 40.0), ("B.s7", 60.0), ("B.s12", 80.0)]:
    sites.append({"siteId": sid, "shank": "B", "xUm": 25.0,
                  "yUm": y, "coordinateBase": "probe-tip"})

recording = {"recordingId": "rec-2026-09-19-probeA",
             "sampleRateHz": FS_HZ, "durationS": 10.0,
             "timeBase": "recording-start", "timeUnit": "s"}

revisions = [
  {"revisionId": "r1", "description": "Initial thresholded detection review",
   "params": {"detectThresholdUv": 75.0, "featureVersion": "feat-v1",
              "refractoryMs": 2.0},
   "labeledAt": "2026-09-19T09:00:00+08:00"},
  {"revisionId": "r2", "description": "Parameter revision: threshold raised to 100 uV",
   "params": {"detectThresholdUv": 100.0, "featureVersion": "feat-v1",
              "refractoryMs": 2.0},
   "labeledAt": "2026-09-19T10:30:00+08:00"},
  {"revisionId": "r3", "description": "Feature v2 + cluster renumbering + split proposal",
   "params": {"detectThresholdUv": 100.0, "featureVersion": "feat-v2",
              "refractoryMs": 2.0},
   "labeledAt": "2026-09-19T12:15:00+08:00"},
]

amendments = [
  {"amendmentId": "am1", "description": "Local source correction for g08 amplitude and g09 geometry",
   "appliedAt": "2026-09-19T13:00:00+08:00",
   "detectionPatches": {
       "g08": {"rawAmplitude": 97.5},
       "g09": {"rawSiteRef": "B.s2"}}},
]

decisions = [
  {"decisionId": "dec-split-e", "revisionId": "r3",
   "kind": "split", "at": "2026-09-19T12:20:00+08:00",
   "proposalId": "prop-r3-5",
   "children": [
     {"label": 51, "members": ["e01", "e03", "e07", "e09"]},
     {"label": 52, "members": ["e02", "e04", "e06", "e08"]}],
   "sharedDetections": ["e05"],
   "rationale": "Bimodal amplitudes; e05 sits between modes and is retained as shared evidence."},
  {"decisionId": "dec-merge-c-accept", "revisionId": "r3",
   "kind": "accept", "at": "2026-09-19T12:25:00+08:00",
   "proposalId": "prop-r3-14", "accepted": True,
   "rationale": "Refractory pairs are evidence of contamination, not proof of two units; keep one unit pending QC."},
  {"decisionId": "dec-reject-drift-d", "revisionId": "r3",
   "kind": "reject", "at": "2026-09-19T12:30:00+08:00",
   "proposalId": "prop-r3-13-drift", "accepted": False,
   "rationale": "Amplitude drift is evidence; cluster number 13 must not be treated as a new stable identity."},
  {"decisionId": "dec-conflict-1", "revisionId": "r3",
   "kind": "conflict", "at": "2026-09-19T12:35:00+08:00",
   "proposalId": "prop-r3-16",
   "competingClaims": [
     {"claimId": "claim-alpha", "action": "merge-into", "targetLabel": 15},
     {"claimId": "claim-beta", "action": "keep-separate", "targetLabel": 16}],
   "resolution": "unresolved",
   "rationale": "Two reviewers disagree; conflict must surface all legal candidates."},
]

case = {
  "caseId": "spike-lineage-case-001",
  "schemaVersion": "1.0",
  "title": "Pulse-cluster dossier: neural spike candidates, drift clusters and adjudication lineage",
  "recordedAt": "2026-09-19",
  "units": {"amplitude": "uV", "time": "s", "distance": "um",
            "coordinateBase": "probe-tip", "timeBase": "recording-start"},
  "recording": recording,
  "sites": sites,
  "revisions": revisions,
  "detections": sorted(dets, key=lambda d: d["detectionId"]),
  "waveforms": [waveforms[k] for k in sorted(waveforms)],
  "sourceAmendments": amendments,
  "decisions": decisions,
  "rules": [
    {"ruleId": "evidence-not-identity",
     "machineReadable": {"refractoryViolation": "evidence",
       "amplitudeDrift": "evidence", "multiChannelPropagation": "evidence",
       "clusterNumberIsStableIdentity": False}},
    {"ruleId": "window-scoped-recompute",
     "machineReadable": {"onWindowChange": "recompute-intersecting-clusters-only"}},
    {"ruleId": "ambiguity",
     "machineReadable": {"whenNotUniquelyDecidable": "keep-all-legal-candidates"}},
  ],
}

os.makedirs("src/main/resources/case", exist_ok=True)
out1 = "src/main/resources/case/case-pack.json"
out2 = "cases/case-pack.json"
for p in (out1, out2):
    with open(p, "w") as f:
        json.dump(case, f, indent=1, ensure_ascii=False)
print("detections:", len(dets), "waveforms:", len(waveforms))
