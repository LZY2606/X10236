package eph.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, deterministic JSON parser/writer. No external dependency.
 * Parsing failures carry a position (index into the input).
 */
public final class Json {

    public static final class JsonParseException extends Exception {
        private final int position;

        public JsonParseException(String message, int position) {
            super(message + " at position " + position);
            this.position = position;
        }

        public int position() {
            return position;
        }
    }

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
        this.i = 0;
    }

    public static Object parse(String text) throws JsonParseException {
        JsonParseWorker w = new JsonParseWorker(text);
        w.parseValue();
        w.skipWs();
        if (w.i < w.s.length()) {
            throw new JsonParseException("unexpected trailing content", w.i);
        }
        return w.root;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, value);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writePretty(sb, value, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeTo(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeTo(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<Object>) value) {
                if (!first) sb.append(',');
                first = false;
                writeTo(sb, item);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("cannot serialize " + value.getClass());
        }
    }

    @SuppressWarnings("unchecked")
    private static void writePretty(StringBuilder sb, Object value, int indent) {
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int n = 0;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                pad(sb, indent + 1);
                writeString(sb, e.getKey());
                sb.append(": ");
                writePretty(sb, e.getValue(), indent + 1);
                if (++n < map.size()) sb.append(',');
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append('}');
        } else if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int k = 0; k < list.size(); k++) {
                pad(sb, indent + 1);
                writePretty(sb, list.get(k), indent + 1);
                if (k < list.size() - 1) sb.append(',');
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append(']');
        } else {
            writeTo(sb, value);
        }
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int k = 0; k < indent; k++) sb.append("  ");
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int k = 0; k < value.length(); k++) {
            char c = value.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---- parsing ----

    private static final class JsonParseWorker {
        private final String s;
        private int i;
        private Object root;

        private JsonParseWorker(String s) {
            this.s = s;
        }

        private Object finalizeValue(Object v) {
            this.root = v;
            return v;
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private JsonParseWorker parseValue() throws JsonParseException {
            skipWs();
            if (i >= s.length()) throw new JsonParseException("unexpected end of input", i);
            char c = s.charAt(i);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') { root = parseString(); return this; }
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') return parseNull();
            return parseNumber();
        }

        private JsonParseWorker parseObject() throws JsonParseException {
            Map<String, Object> map = new LinkedHashMap<>();
            i++; // {
            skipWs();
            if (i < s.length() && s.charAt(i) == '}') { i++; root = map; return this; }
            while (true) {
                skipWs();
                if (i >= s.length() || s.charAt(i) != '"') {
                    throw new JsonParseException("expected string key", i);
                }
                String key = parseString();
                skipWs();
                if (i >= s.length() || s.charAt(i) != ':') {
                    throw new JsonParseException("expected ':'", i);
                }
                i++;
                parseValue();
                map.put(key, root);
                skipWs();
                if (i >= s.length()) throw new JsonParseException("unterminated object", i);
                char c = s.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; break; }
                throw new JsonParseException("expected ',' or '}'", i);
            }
            root = map;
            return this;
        }

        private JsonParseWorker parseArray() throws JsonParseException {
            List<Object> list = new ArrayList<>();
            i++; // [
            skipWs();
            if (i < s.length() && s.charAt(i) == ']') { i++; root = list; return this; }
            while (true) {
                parseValue();
                list.add(root);
                skipWs();
                if (i >= s.length()) throw new JsonParseException("unterminated array", i);
                char c = s.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; break; }
                throw new JsonParseException("expected ',' or ']'", i);
            }
            root = list;
            return this;
        }

        private String parseString() throws JsonParseException {
            // assumes s.charAt(i) == '"'
            int start = i;
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw new JsonParseException("bad escape", start);
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw new JsonParseException("bad unicode escape", start);
                            String hex = s.substring(i, i + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException nfe) {
                                throw new JsonParseException("bad unicode escape", start);
                            }
                            i += 4;
                        }
                        default -> throw new JsonParseException("bad escape char", i - 1);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new JsonParseException("unterminated string starting", start);
        }

        private JsonParseWorker parseBoolean() throws JsonParseException {
            if (s.startsWith("true", i)) { i += 4; root = Boolean.TRUE; return this; }
            if (s.startsWith("false", i)) { i += 5; root = Boolean.FALSE; return this; }
            throw new JsonParseException("invalid literal", i);
        }

        private JsonParseWorker parseNull() throws JsonParseException {
            if (s.startsWith("null", i)) { i += 4; root = null; return this; }
            throw new JsonParseException("invalid literal", i);
        }

        private JsonParseWorker parseNumber() throws JsonParseException {
            int start = i;
            boolean isDouble = false;
            if (i < s.length() && s.charAt(i) == '-') i++;
            int digits = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') { digits++; i++; }
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    if (c == '.' || c == 'e' || c == 'E') isDouble = true;
                    i++;
                } else {
                    break;
                }
            }
            if (digits == 0) throw new JsonParseException("invalid number", start);
            String token = s.substring(start, i);
            try {
                root = isDouble ? Double.valueOf(token) : Long.valueOf(token);
            } catch (NumberFormatException nfe) {
                throw new JsonParseException("invalid number", start);
            }
            return this;
        }
    }
}
