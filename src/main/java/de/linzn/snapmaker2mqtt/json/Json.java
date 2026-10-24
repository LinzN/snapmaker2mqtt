package de.linzn.snapmaker2mqtt.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON parser and serializer.
 * <p>
 * Parsed values are mapped to plain Java types:
 * objects → {@link LinkedHashMap}, arrays → {@link ArrayList}, strings → {@link String},
 * numbers → {@link Long} (integral) or {@link Double}, booleans → {@link Boolean}, null → {@code null}.
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Parses a JSON text into plain Java objects.
     *
     * @param text JSON text
     * @return parsed value
     * @throws IllegalArgumentException on malformed input
     */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (p.pos != text.length()) {
            throw p.error("Unexpected trailing content");
        }
        return value;
    }

    /**
     * Parses a JSON text that is expected to be an object.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("JSON value is not an object");
        }
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " at position " + pos);
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            if (pos >= s.length()) {
                throw error("Unexpected end of input");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    // Python's json module (used by Moonraker) may emit these for floats
                    if (s.startsWith("NaN", pos)) {
                        pos += 3;
                        return Double.NaN;
                    }
                    if (s.startsWith("Infinity", pos)) {
                        pos += 8;
                        return Double.POSITIVE_INFINITY;
                    }
                    throw error("Unexpected character '" + c + "'");
            }
        }

        void expect(String word) {
            if (!s.startsWith(word, pos)) {
                throw error("Expected '" + word + "'");
            }
            pos += word.length();
        }

        Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("Expected object key");
                }
                String key = readString();
                skipWhitespace();
                if (peek() != ':') {
                    throw error("Expected ':'");
                }
                pos++;
                skipWhitespace();
                map.put(key, readValue());
                skipWhitespace();
                char c = peek();
                pos++;
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("Expected ',' or '}'");
                }
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(readValue());
                skipWhitespace();
                char c = peek();
                pos++;
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("Expected ',' or ']'");
                }
            }
        }

        char peek() {
            if (pos >= s.length()) {
                throw error("Unexpected end of input");
            }
            return s.charAt(pos);
        }

        String readString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("Unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw error("Invalid escape '\\" + e + "'");
                }
            }
        }

        Object readNumber() {
            int start = pos;
            if (s.charAt(pos) == '-') {
                pos++;
                if (s.startsWith("Infinity", pos)) {
                    pos += 8;
                    return Double.NEGATIVE_INFINITY;
                }
            }
            boolean floating = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    floating = true;
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            if (!floating) {
                try {
                    return Long.parseLong(num);
                } catch (NumberFormatException ignored) {
                    // too large for long, fall through to double
                }
            }
            return Double.parseDouble(num);
        }
    }

    // ------------------------------------------------------------------ serializing

    /**
     * Serializes plain Java objects (Map, Collection, arrays, String, Number, Boolean, Enum, null) to JSON.
     * Objects implementing {@link JsonSerializable} are serialized via {@link JsonSerializable#toJsonValue()}.
     */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof JsonSerializable js) {
            write(sb, js.toJsonValue());
        } else if (value instanceof String str) {
            writeString(sb, str);
        } else if (value instanceof Double d) {
            writeDouble(sb, d);
        } else if (value instanceof Float f) {
            writeDouble(sb, f.doubleValue());
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Enum<?> e) {
            writeString(sb, e.name());
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                write(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Collection<?> col) {
            sb.append('[');
            boolean first = true;
            for (Object item : col) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, item);
            }
            sb.append(']');
        } else if (value instanceof Object[] arr) {
            write(sb, List.of(arr));
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeDouble(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            sb.append("null");
        } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            sb.append((long) d);
        } else {
            sb.append(d);
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ typed access helpers

    /**
     * Navigates nested maps by key path, e.g. {@code get(root, "print_stats", "info", "current_layer")}.
     *
     * @return the value or {@code null} if any segment is missing
     */
    public static Object get(Object root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    public static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    public static Double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static Integer asInt(Object value) {
        Double d = asDouble(value);
        return d == null || d.isNaN() ? null : (int) Math.round(d);
    }

    public static Boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }
}
