package eph.archive;

import java.util.List;

/**
 * Probe-site coordinate tied to an explicit reference frame ("probe" origin, metres).
 * The reference frame is part of the value: coordinates without it are not silently zeroed.
 */
public record Coordinate(String raw, Double x, Double y, Double z, String unit,
                         Double canonicalX, Double canonicalY, Double canonicalZ,
                         String referenceFrame) {

    public static Coordinate parse(String rawX, String rawY, String rawZ, String frame,
                                   String location, List<Diagnostic> diagnostics) {
        Quantity qx = Quantity.parse(rawX, location + ".x", diagnostics);
        Quantity qy = Quantity.parse(rawY, location + ".y", diagnostics);
        if (frame == null || frame.isBlank()) {
            diagnostics.add(Diagnostic.local("MISSING_COORDINATE_FRAME", location,
                    "coordinate has no reference frame; retained as raw"));
        }
        Quantity qz = rawZ == null ? null : Quantity.parse(rawZ, location + ".z", diagnostics);
        return new Coordinate(rawX + "," + rawY + (rawZ == null ? "" : "," + rawZ),
                qx.value(), qy.value(), qz == null ? null : qz.value(),
                qx.unit(), qx.canonicalValue(), qy.canonicalValue(),
                qz == null ? null : qz.canonicalValue(), frame);
    }
}
