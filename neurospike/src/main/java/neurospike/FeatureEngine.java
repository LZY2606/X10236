package neurospike;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic two-component waveform projection. No random initial vectors:
 * power iteration starts from a fixed vector so equivalent inputs project to
 * identical coordinates. Amplitude is normalized to uV only in this derived
 * layer; unconvertible amplitudes project on waveform PCs but are flagged.
 */
public final class FeatureEngine {

    private FeatureEngine() {
    }

    public record ProjectionResult(Map<String, double[]> features,
                                   List<FeaturePoint> points,
                                   List<Diagnostic> diagnostics) {
    }

    public static ProjectionResult project(State state, MembershipEngine.MembershipResult membership,
                                           long seq) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        MaterialRev<FeatureVersionSpec> specRev = state.specAtOrBefore(seq);
        FeatureVersionSpec spec = specRev.material();
        int dims = spec.waveformSamples();
        Map<String, MaterialRev<WaveformRef>> wfRevs = state.waveformAtOrBefore(seq);
        Map<String, MaterialRev<Detection>> detRevs = state.detectionAtOrBefore(seq);

        // build matrix over detections belonging to clusters with usable waveforms
        Map<String, double[]> raw = new HashMap<>();
        List<String> ids = new ArrayList<>();
        for (ClusterGen g : membership.clusters()) {
            for (String id : g.detections()) {
                Detection d = detRevs.get(id).material();
                MaterialRev<WaveformRef> wfr = d.waveformId() == null ? null
                        : wfRevs.get(d.waveformId());
                if (wfr == null || wfr.material().samples() == null
                        || wfr.material().samples().size() < dims) {
                    diagnostics.add(new Diagnostic(Diagnostic.LOCAL_VALUE_INVALID,
                            "/detections/" + id + "/waveform",
                            "waveform samples missing or shorter than feature window "
                                    + dims, d.waveformId()));
                    continue;
                }
                double[] row = new double[dims];
                List<Double> samples = wfr.material().samples();
                int offset = wfr.material().peakIndex() != null
                        ? Math.max(0, Math.min(samples.size() - dims,
                                wfr.material().peakIndex() - dims / 2))
                        : 0;
                for (int i = 0; i < dims; i++) {
                    row[i] = samples.get(offset + i);
                }
                raw.put(id, row);
                ids.add(id);
            }
        }
        java.util.Collections.sort(ids);

        double[][] basis = fixedBasis(raw, ids, dims);
        Map<String, double[]> clusterOf = new HashMap<>();
        for (ClusterGen g : membership.clusters()) {
            for (String id : g.detections()) {
                clusterOf.put(id, null);
            }
        }
        Map<String, String> stableOf = new HashMap<>();
        for (ClusterGen g : membership.clusters()) {
            for (String id : g.detections()) {
                stableOf.put(id, g.stableId());
            }
        }

        Map<String, double[]> features = new HashMap<>();
        List<FeaturePoint> points = new ArrayList<>();
        for (String id : ids) {
            double[] row = raw.get(id);
            double pc1 = dot(row, basis[0]);
            double pc2 = dot(row, basis[1]);
            Detection d = detRevs.get(id).material();
            Double amp = null;
            String status = "OK";
            if (d.peakAmplitude() != null) {
                amp = d.peakAmplitude().valueIn("uV");
                if (amp == null) {
                    status = "AMPLITUDE_UNCONVERTIBLE";
                }
            } else {
                status = "AMPLITUDE_MISSING";
            }
            features.put(id, new double[]{pc1, pc2, amp == null ? Double.NaN : amp});
            points.add(new FeaturePoint(id, stableOf.get(id), round(pc1), round(pc2),
                    amp == null ? Double.NaN : round(amp), status));
        }
        points.sort((a, b) -> a.detectionId().compareTo(b.detectionId()));
        return new ProjectionResult(features, points, diagnostics);
    }

    private static double[][] fixedBasis(Map<String, double[]> rows, List<String> ids,
                                         int dims) {
        double[] mean = new double[dims];
        double[][] centered = new double[ids.size()][dims];
        for (int i = 0; i < ids.size(); i++) {
            double[] r = rows.get(ids.get(i));
            for (int j = 0; j < dims; j++) {
                mean[j] += r[j];
            }
        }
        for (int j = 0; j < dims; j++) {
            mean[j] /= Math.max(1, ids.size());
        }
        for (int i = 0; i < ids.size(); i++) {
            double[] r = rows.get(ids.get(i));
            for (int j = 0; j < dims; j++) {
                centered[i][j] = r[j] - mean[j];
            }
        }
        double[] pc1 = powerIterate(centered, dims, fixedStart(dims, 0));
        double[] pc2 = powerIterate(centered, dims, orthogonal(fixedStart(dims, 1), pc1));
        return new double[][]{pc1, pc2};
    }

    private static double[] fixedStart(int dims, int variant) {
        double[] v = new double[dims];
        for (int i = 0; i < dims; i++) {
            v[i] = variant == 0
                    ? Math.sin((i + 1) * 0.7)
                    : Math.cos((i + 1) * 1.1);
        }
        normalize(v);
        return v;
    }

    private static double[] powerIterate(double[][] x, int dims, double[] start) {
        double[] v = start;
        for (int iter = 0; iter < 60; iter++) {
            double[] xv = new double[dims];
            for (double[] row : x) {
                double d = dot(row, v);
                for (int j = 0; j < dims; j++) {
                    xv[j] += row[j] * d;
                }
            }
            normalize(xv);
            if (dot(xv, v) > 0.999999) {
                v = xv;
                break;
            }
            v = xv;
        }
        // enforce a deterministic sign so the projection cannot flip
        if (v[0] < 0 || (v[0] == 0 && v[dims / 2] < 0)) {
            for (int i = 0; i < dims; i++) {
                v[i] = -v[i];
            }
        }
        return v;
    }

    private static double[] orthogonal(double[] v, double[] against) {
        double d = dot(v, against);
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = v[i] - d * against[i];
        }
        if (norm(out) < 1e-12) {
            out[0] = 1.0;
            double dd = dot(out, against);
            for (int i = 0; i < v.length; i++) {
                out[i] -= dd * against[i];
            }
        }
        normalize(out);
        return out;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    private static double norm(double[] v) {
        return Math.sqrt(dot(v, v));
    }

    private static void normalize(double[] v) {
        double n = norm(v);
        if (n > 1e-12) {
            for (int i = 0; i < v.length; i++) {
                v[i] /= n;
            }
        }
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
