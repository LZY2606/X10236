package neuro.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * An append-only, replayable event. Every event records its parameter version and parent dependency
 * so any past state can be reconstructed without overwriting history with the newest computation.
 */
public final class Event {
    private final String id;
    private final long seq;
    private final EventType type;
    private final String parentEventId;
    private final String paramVersion;
    private final String inputSummary;
    private final String decidedBy;
    private final String note;
    private final JsonNode payload;

    public Event(String id, long seq, EventType type, String parentEventId, String paramVersion,
                 String inputSummary, String decidedBy, String note, JsonNode payload) {
        this.id = Objects.requireNonNull(id);
        this.seq = seq;
        this.type = Objects.requireNonNull(type);
        this.parentEventId = parentEventId;
        this.paramVersion = Objects.requireNonNull(paramVersion);
        this.inputSummary = inputSummary == null ? "" : inputSummary;
        this.decidedBy = decidedBy == null ? "system" : decidedBy;
        this.note = note == null ? "" : note;
        this.payload = payload == null ? JsonNodeFactory.instance.objectNode() : payload;
    }

    public String id() { return id; }
    public long seq() { return seq; }
    public EventType type() { return type; }
    public String parentEventId() { return parentEventId; }
    public String paramVersion() { return paramVersion; }
    public String inputSummary() { return inputSummary; }
    public String decidedBy() { return decidedBy; }
    public String note() { return note; }
    public JsonNode payload() { return payload; }

    public ObjectNode toJson() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("id", id);
        n.put("seq", seq);
        n.put("type", type.name());
        if (parentEventId != null) n.put("parentEventId", parentEventId);
        n.put("paramVersion", paramVersion);
        n.put("inputSummary", inputSummary);
        n.put("decidedBy", decidedBy);
        n.put("note", note);
        n.set("payload", payload);
        return n;
    }
}
