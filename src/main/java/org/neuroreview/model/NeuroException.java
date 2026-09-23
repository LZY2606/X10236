package org.neuroreview.model;

import java.util.List;

public class NeuroException extends RuntimeException {
    private final FailureKind kind;
    private final List<String> positions;

    public NeuroException(FailureKind kind, String message, List<String> positions) {
        super(message);
        this.kind = kind;
        this.positions = positions == null ? List.of() : List.copyOf(positions);
    }

    public FailureKind kind() {
        return kind;
    }

    public List<String> positions() {
        return positions;
    }
}
