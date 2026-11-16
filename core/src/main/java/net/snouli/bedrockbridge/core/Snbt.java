package net.snouli.bedrockbridge.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the item component string Bukkit produces with {@code ItemMeta#getAsComponentString()},
 * e.g. {@code [minecraft:max_stack_size=16,minecraft:food={nutrition:4,saturation:2.4f}]}.
 *
 * <p>Going through this text form keeps the plugin independent of the (still changing) component
 * classes of the server API. Values become {@link Map}, {@link List}, {@link String},
 * {@link Double} or {@link Boolean}.
 */
final class Snbt {

    /** Value of a component that the item removes from its base item ({@code !minecraft:food}). */
    static final Object REMOVED = new Object();

    private final String text;
    private int position;

    private Snbt(String text) {
        this.text = text;
    }

    /**
     * @return component name without the {@code minecraft:} prefix to its value; empty if the text
     *         is empty or malformed
     */
    static Map<String, Object> components(String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        Snbt parser = new Snbt(text.trim());
        try {
            parser.expect('[');
            parser.skipSpace();
            while (parser.peek() != ']') {
                boolean removed = parser.peek() == '!';
                if (removed) {
                    parser.position++;
                }
                String key = stripNamespace(parser.token("=,]").trim());
                if (removed) {
                    result.put(key, REMOVED);
                } else {
                    parser.expect('=');
                    result.put(key, parser.value());
                }
                parser.skipSpace();
                if (parser.peek() == ',') {
                    parser.position++;
                    parser.skipSpace();
                }
            }
        } catch (RuntimeException e) {
            // keep what was read so far; an unreadable tail must not break the build
        }
        return result;
    }

    static String stripNamespace(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private char peek() {
        if (position >= text.length()) {
            throw new IllegalStateException("unexpected end");
        }
        return text.charAt(position);
    }

    private void skipSpace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }

    private void expect(char c) {
        skipSpace();
        if (peek() != c) {
            throw new IllegalStateException("expected " + c + " at " + position);
        }
        position++;
    }

    private String token(String terminators) {
        int start = position;
        while (position < text.length() && terminators.indexOf(text.charAt(position)) < 0) {
            position++;
        }
        return text.substring(start, position);
    }

    private Object value() {
        skipSpace();
        char c = peek();
        if (c == '{') {
            position++;
            Map<String, Object> map = new LinkedHashMap<>();
            skipSpace();
            while (peek() != '}') {
                String key = peek() == '"' || peek() == '\'' ? quoted() : token(":").trim();
                expect(':');
                map.put(stripNamespace(key), value());
                skipSpace();
                if (peek() == ',') {
                    position++;
                    skipSpace();
                }
            }
            position++;
            return map;
        }
        if (c == '[') {
            position++;
            List<Object> list = new ArrayList<>();
            skipSpace();
            // typed arrays: [I;1,2,3]
            if (position + 1 < text.length() && text.charAt(position + 1) == ';' && "BILbil".indexOf(peek()) >= 0) {
                position += 2;
                skipSpace();
            }
            while (peek() != ']') {
                list.add(value());
                skipSpace();
                if (peek() == ',') {
                    position++;
                    skipSpace();
                }
            }
            position++;
            return list;
        }
        if (c == '"' || c == '\'') {
            return quoted();
        }
        String raw = token(",]}").trim();
        if (raw.equals("true") || raw.equals("false")) {
            return Boolean.parseBoolean(raw);
        }
        if (raw.matches("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?[bBsSlLfFdD]?")) {
            String digits = "bBsSlLfFdD".indexOf(raw.charAt(raw.length() - 1)) >= 0 ? raw.substring(0, raw.length() - 1) : raw;
            return Double.parseDouble(digits);
        }
        return raw;
    }

    private String quoted() {
        char quote = text.charAt(position++);
        StringBuilder out = new StringBuilder();
        while (peek() != quote) {
            char c = text.charAt(position++);
            if (c == '\\' && position < text.length()) {
                c = text.charAt(position++);
            }
            out.append(c);
        }
        position++;
        return out.toString();
    }
}
