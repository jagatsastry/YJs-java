package dev.yjs.lib0;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON serializer/parser matching JavaScript's {@code JSON.stringify}/{@code JSON.parse}
 * semantics for the value types produced by this port.
 *
 * <p>This is used by the V1 update encoder/decoder for {@code ContentEmbed} (which historically
 * encodes embeds as JSON strings via {@code writeJSON}/{@code readJSON}), so it must round-trip
 * losslessly for: {@code null}, {@link Boolean}, {@link String}, integral numbers
 * ({@link Long}/{@link Integer}), decimal numbers ({@link Double}), {@link List} (JSON arrays),
 * and {@link Map} (JSON objects, insertion order preserved).
 *
 * <p>Number formatting mirrors JS: an integer-valued {@code Double} prints without a trailing
 * decimal ({@code 1.0 -> "1"}); {@link Long}/{@link Integer} print as plain integers.
 *
 * <p>{@code parse} returns {@link Long} for integers, {@link Double} for decimals, {@link String},
 * {@link Boolean}, {@code null}, {@link ArrayList} for arrays and {@link LinkedHashMap} for objects.
 */
public final class Json {
    private Json() {}

    /* ===================== stringify ===================== */

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        stringifyValue(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void stringifyValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            stringifyString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            sb.append(formatDouble(((Number) value).doubleValue()));
        } else if (value instanceof Number n) {
            // Long, Integer, Short, Byte, BigInteger -> plain integer string.
            sb.append(n.toString());
        } else if (value instanceof Map<?, ?> map) {
            stringifyObject(sb, (Map<String, Object>) map);
        } else if (value instanceof List<?> list) {
            stringifyArray(sb, list);
        } else {
            // Mirror JSON.stringify falling back: unknown types are not expected here.
            throw new IllegalArgumentException("Cannot JSON-stringify value of type " + value.getClass());
        }
    }

    private static void stringifyObject(StringBuilder sb, Map<String, Object> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            stringifyString(sb, e.getKey());
            sb.append(':');
            stringifyValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void stringifyArray(StringBuilder sb, List<?> list) {
        sb.append('[');
        boolean first = true;
        for (Object o : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            stringifyValue(sb, o);
        }
        sb.append(']');
    }

    private static void stringifyString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int p = hex.length(); p < 4; p++) {
                            sb.append('0');
                        }
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /**
     * Format a double the way JS does for {@code JSON.stringify}: integer-valued finite doubles
     * print without a decimal part ({@code 1.0 -> "1"}); others use the shortest round-tripping
     * representation. {@code NaN}/{@code Infinity} stringify to {@code null} (as in JS JSON).
     */
    private static String formatDouble(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "null";
        }
        if (d == Math.floor(d) && Math.abs(d) < 9.007199254740992e15) {
            long l = (long) d;
            return Long.toString(l);
        }
        // Double.toString gives the shortest decimal that round-trips, matching JS closely for
        // the common cases used in this port.
        return Double.toString(d);
    }

    /* ===================== parse ===================== */

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object result = p.parseValue();
        p.skipWhitespace();
        if (p.pos != text.length()) {
            throw new IllegalArgumentException("Unexpected trailing characters in JSON at position " + p.pos);
        }
        return result;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
            this.pos = 0;
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

        Object parseValue() {
            if (pos >= s.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                case 'f':
                    return parseBoolean();
                case 'n':
                    return parseNull();
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected character '" + c + "' at position " + pos);
            }
        }

        Map<String, Object> parseObject() {
            Map<String, Object> obj = new LinkedHashMap<>();
            pos++; // consume '{'
            skipWhitespace();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return obj;
            }
            while (true) {
                skipWhitespace();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw new IllegalArgumentException("Expected string key in object at position " + pos);
                }
                String key = parseString();
                skipWhitespace();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw new IllegalArgumentException("Expected ':' in object at position " + pos);
                }
                pos++; // consume ':'
                skipWhitespace();
                Object value = parseValue();
                obj.put(key, value);
                skipWhitespace();
                if (pos >= s.length()) {
                    throw new IllegalArgumentException("Unexpected end of JSON input in object");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return obj;
                } else {
                    throw new IllegalArgumentException("Expected ',' or '}' in object at position " + pos);
                }
            }
        }

        List<Object> parseArray() {
            List<Object> arr = new ArrayList<>();
            pos++; // consume '['
            skipWhitespace();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return arr;
            }
            while (true) {
                skipWhitespace();
                arr.add(parseValue());
                skipWhitespace();
                if (pos >= s.length()) {
                    throw new IllegalArgumentException("Unexpected end of JSON input in array");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return arr;
                } else {
                    throw new IllegalArgumentException("Expected ',' or ']' in array at position " + pos);
                }
            }
        }

        String parseString() {
            StringBuilder sb = new StringBuilder();
            pos++; // consume opening quote
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                } else if (c == '\\') {
                    if (pos >= s.length()) {
                        throw new IllegalArgumentException("Unexpected end of JSON input in string escape");
                    }
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'u':
                            if (pos + 4 > s.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape in JSON string");
                            }
                            String hex = s.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                            break;
                        default:
                            throw new IllegalArgumentException("Invalid escape character '" + esc + "' in JSON string");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string in JSON input");
        }

        Boolean parseBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Invalid literal in JSON at position " + pos);
        }

        Object parseNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Invalid literal in JSON at position " + pos);
        }

        Object parseNumber() {
            int start = pos;
            boolean isDecimal = false;
            if (pos < s.length() && s.charAt(pos) == '-') {
                pos++;
            }
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isDecimal = true;
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            if (isDecimal) {
                return Double.parseDouble(num);
            }
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                // Integer too large for long: fall back to double (matches JS, which has one number type).
                return Double.parseDouble(num);
            }
        }
    }
}
