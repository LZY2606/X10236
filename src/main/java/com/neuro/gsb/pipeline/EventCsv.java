package com.neuro.gsb.pipeline;

import com.neuro.gsb.domain.Diagnostic;
import com.neuro.gsb.domain.Quantity;
import com.neuro.gsb.domain.Result;
import com.neuro.gsb.domain.SpikeEvent;

import java.util.ArrayList;
import java.util.List;

/** Parsing + derived-layer normalization for detection_events.csv. Shared by import and source patches. */
final class EventCsv {
    static final String HEADER =
            "event_key,channel,time,time_unit,amplitude,amplitude_unit,cluster_label,label_set";

    record RawEvent(String eventKey, int channel, Quantity time, Quantity amplitude,
                    String clusterLabel, String labelSet) {}

    record Normalized(List<SpikeEvent> events, List<RawEvent> keptRaw, List<Diagnostic> diagnostics) {}

    private EventCsv() {}

    static Result<List<RawEvent>> parse(String csv, ImportService.ImportBudget budget) {
        List<RawEvent> raw = new ArrayList<>();
        String[] lines = csv.split("\\R");
        if (lines.length == 0 || !lines[0].trim().equals(HEADER)) {
            return new Result.FormatCorrupt<>("detection_events.csv:1",
                    "unexpected header: " + (lines.length == 0 ? "<empty>" : lines[0].trim()));
        }
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String position = "detection_events.csv:" + (i + 1);
            if (raw.size() >= budget.maxEvents()) {
                return new Result.BudgetExhausted<>(position,
                        "event budget " + budget.maxEvents() + " exhausted", List.of());
            }
            String[] f = line.split(",", -1);
            if (f.length != 8) {
                return new Result.FormatCorrupt<>(position, "expected 8 fields, got " + f.length);
            }
            int channel;
            try {
                channel = Integer.parseInt(f[1].trim());
            } catch (NumberFormatException e) {
                return new Result.FormatCorrupt<>(position, "channel not an integer: " + f[1].trim());
            }
            raw.add(new RawEvent(f[0].trim(), channel,
                    new Quantity(f[2].trim(), f[3].trim(), Quantity.Basis.RECORDING_CLOCK),
                    new Quantity(f[4].trim(), f[5].trim(), Quantity.Basis.NONE),
                    f[6].trim(), f[7].trim()));
        }
        return Result.ok(raw);
    }

    static Normalized normalize(List<RawEvent> raw) {
        List<Diagnostic> diags = new ArrayList<>();
        List<SpikeEvent> normalized = new ArrayList<>();
        List<RawEvent> keptRaw = new ArrayList<>();
        for (RawEvent r : raw) {
            var tMs = r.time().toMillis(30.0);
            var aUv = r.amplitude().toMicroVolts(null);
            String pos = "detection_events.csv:event " + r.eventKey();
            if (tMs.isEmpty()) {
                diags.add(Diagnostic.warning(pos,
                        "time '" + r.time() + "' not convertible to ms; kept raw, excluded from canonical series"));
            }
            if (aUv.isEmpty()) {
                diags.add(Diagnostic.warning(pos,
                        "amplitude '" + r.amplitude() + "' not convertible to µV (no ADC gain); kept raw"));
            }
            if (tMs.isPresent() && aUv.isPresent()) {
                normalized.add(new SpikeEvent(r.eventKey(), r.channel(), tMs.get(), aUv.get(),
                        r.clusterLabel(), r.labelSet()));
            } else {
                keptRaw.add(r);
            }
        }
        return new Normalized(normalized.stream().sorted().toList(), keptRaw, diags);
    }

    static String seriesPayload(List<SpikeEvent> ordered, List<RawEvent> keptRaw) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"unit\":\"ms\",\"amplitude_unit\":\"uV\",\"time_basis\":\"RECORDING_CLOCK\",");
        sb.append("\"events\":[");
        for (int i = 0; i < ordered.size(); i++) {
            SpikeEvent e = ordered.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"key\":\"").append(e.eventKey()).append("\",\"ch\":").append(e.channel())
              .append(",\"t_ms\":").append(e.timeMs())
              .append(",\"amp_uv\":").append(e.amplitudeUv())
              .append(",\"label\":\"").append(e.clusterLabel()).append("\"")
              .append(",\"label_set\":\"").append(e.labelSetVersion()).append("\"}");
        }
        sb.append("],\"unconverted\":[");
        for (int i = 0; i < keptRaw.size(); i++) {
            RawEvent r = keptRaw.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"key\":\"").append(r.eventKey()).append("\",\"ch\":").append(r.channel())
              .append(",\"time_raw\":\"").append(r.time()).append("\"")
              .append(",\"amp_raw\":\"").append(r.amplitude()).append("\"")
              .append(",\"label\":\"").append(r.clusterLabel()).append("\"")
              .append(",\"label_set\":\"").append(r.labelSet()).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }
}
