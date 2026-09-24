package ephys;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A positioned diagnostic. Diagnostics never masquerade as success: they are
 *  attached to an explicit Outcome and always carry a location path. */
public record Diag(String scope, String path, String message, String severity) {

    public static Diag error(String scope, String path, String message) {
        return new Diag(scope, path, message, "error");
    }

    public static Diag warning(String scope, String path, String message) {
        return new Diag(scope, path, message, "warning");
    }

    public ObjectNode toJson() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("scope", scope);
        n.put("path", path);
        n.put("message", message);
        n.put("severity", severity);
        return n;
    }
}
