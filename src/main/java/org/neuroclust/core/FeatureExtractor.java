package org.neuroclust.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.neuroclust.model.Material;
import org.neuroclust.model.Types;

import java.util.ArrayList;
import java.util.List;

/**
 * 特征层（派生层）：仅在此做规范化。特征：[振幅µV, 峰值SNR, 通道y坐标µm, 波形宽度ms]。
 * 无法转换的值不丢弃：产生带位置的诊断，检测仍保留并在派生里标记 local_failure。
 */
public final class FeatureExtractor {

    public record Extracted(Results.Feature feature, List<Types.Diagnostic> diagnostics) {}

    private final java.util.Map<String, Material.Channel> channelById = new java.util.HashMap<>();
    private final java.util.Map<String, Material.WaveformRef> waveformById;
    private final double[] scales;

    public FeatureExtractor(Material.ProbeGeometry probe,
                            java.util.Collection<Material.WaveformRef> waveforms,
                            JsonNode scaleConfig) {
        for (Material.Channel c : probe.channels()) channelById.put(c.channelId(), c);
        this.waveformById = new java.util.HashMap<>();
        for (Material.WaveformRef w : waveforms) waveformById.put(w.waveformId(), w);
        this.scales = new double[] {
                scaleConfig.path("ampUv").asDouble(100),
                scaleConfig.path("snr").asDouble(5),
                scaleConfig.path("yUm").asDouble(50),
                scaleConfig.path("widthMs").asDouble(0.4)
        };
    }

    public double[] scales() { return scales; }

    public Extracted extract(Material.Detection d) {
        List<Types.Diagnostic> diag = new ArrayList<>();
        String loc = "/detections/" + d.detectionId();

        Types.Quantity amp = d.amplitude();
        if (amp == null || !amp.isConvertible() || !"uV".equals(amp.unit())) {
            diag.add(Types.Diagnostic.error("AMP_UNRESOLVED", loc + "/amplitudeUv",
                    "振幅无法规范化到 uV，原值保留: " + (amp == null ? null : amp.raw())));
        }
        if (d.peakSnr() == null || !d.peakSnr().isConvertible()) {
            diag.add(Types.Diagnostic.error("SNR_UNRESOLVED", loc + "/peakSnr",
                    "SNR 无法解析，原值保留: " + (d.peakSnr() == null ? null : d.peakSnr().raw())));
        }
        Material.Channel ch = channelById.get(d.channelId());
        if (ch == null) {
            diag.add(Types.Diagnostic.error("CHANNEL_MISSING", loc + "/channelId",
                    "检测引用了探针几何中不存在的通道: " + d.channelId()));
        }
        Double widthMs = null;
        if (d.waveformId() != null) {
            Material.WaveformRef w = waveformById.get(d.waveformId());
            if (w == null) {
                diag.add(Types.Diagnostic.error("WAVEFORM_REF_MISSING", loc + "/waveformId",
                        "波形引用不可解析: " + d.waveformId()));
            } else if (!w.samplingRate().isConvertible() || !"Hz".equals(w.samplingRate().unit())
                    || w.sampleCount() <= 0) {
                diag.add(Types.Diagnostic.error("WAVEFORM_DIMENSION_INVALID", loc,
                        "波形采样率/采样点无法解析为宽度，原值保留"));
            } else {
                widthMs = (w.sampleCount() - 1) / w.samplingRate().value() * 1000.0;
            }
        }
        double[] raw = new double[] {
                amp != null && amp.isConvertible() ? amp.value() : Double.NaN,
                d.peakSnr() != null && d.peakSnr().isConvertible() ? d.peakSnr().value() : Double.NaN,
                ch != null ? ch.position().yUm() : Double.NaN,
                widthMs != null ? widthMs : Double.NaN
        };
        double[] vector = new double[4];
        for (int i = 0; i < 4; i++) vector[i] = Double.isNaN(raw[i]) ? Double.NaN : raw[i] / scales[i];
        return new Extracted(
                new Results.Feature(d.detectionId(), vector,
                        List.of("amp_uV", "snr", "y_um", "width_ms")),
                diag);
    }
}
