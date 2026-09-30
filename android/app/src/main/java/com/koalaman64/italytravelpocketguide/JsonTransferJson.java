package com.koalaman64.italytravelpocketguide;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict, bounded JSON at the transfer boundary. No Android or third-party parser behavior. */
final class JsonTransferJson {
    static final int PAYLOAD_LIMIT = 512 * 1024;
    static final int FRAME_LIMIT = 2 * 1024 * 1024;
    static final class Failure extends Exception {
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    static byte[] utf8(String text, int limit) throws Failure {
        if (text == null || text.length() > limit) throw new Failure("FILE_LIMIT");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw new Failure("INVALID_UTF8");
            } else if (Character.isLowSurrogate(c)) throw new Failure("INVALID_UTF8");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) throw new Failure("FILE_LIMIT");
        return bytes;
    }
    static String decode(byte[] bytes) throws Failure {
        if (bytes.length > PAYLOAD_LIMIT) throw new Failure("FILE_LIMIT");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw new Failure("INVALID_UTF8"); }
    }
    static Object parse(String text, int limit) throws Failure {
        utf8(text, limit);
        Parser p = new Parser(text);
        Object value = p.value(0);
        p.space();
        if (p.at != text.length()) throw new Failure("MALFORMED");
        return value;
    }
    static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof String) {
            StringBuilder out = new StringBuilder("\"");
            String text = (String) value;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '"' || c == '\\') out.append('\\').append(c);
                else if (c < 32) out.append(String.format("\\u%04x", (int) c));
                else out.append(c);
            }
            return out.append('"').toString();
        }
        if (value instanceof Map) {
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (out.length() > 1) out.append(',');
                out.append(encode(entry.getKey())).append(':').append(encode(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : (List<?>) value) {
                if (out.length() > 1) out.append(',');
                out.append(encode(item));
            }
            return out.append(']').toString();
        }
        return value.toString();
    }
    static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static final class Parser {
        final String text;
        int at;
        Parser(String text) { this.text = text; }
        void space() { while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++; }
        boolean take(char c) { if (at < text.length() && text.charAt(at) == c) { at++; return true; } return false; }
        void require(char c) throws Failure { space(); if (!take(c)) throw new Failure("MALFORMED"); }
        Object value(int depth) throws Failure {
            space();
            if (at == text.length()) throw new Failure("MALFORMED");
            char c = text.charAt(at);
            if (c == '"') return string();
            if (c == '{' || c == '[') {
                if (depth >= 24) throw new Failure("MALFORMED");
                at++; space();
                if (c == '{') {
                    Map<String, Object> result = new LinkedHashMap<>();
                    if (take('}')) return result;
                    do {
                        space(); String key = string(); require(':');
                        if (result.containsKey(key)) throw new Failure("MALFORMED");
                        result.put(key, value(depth + 1)); space();
                        if (take('}')) return result;
                        require(',');
                    } while (true);
                }
                List<Object> result = new ArrayList<>();
                if (take(']')) return result;
                do {
                    result.add(value(depth + 1)); space();
                    if (take(']')) return result;
                    require(',');
                } while (true);
            }
            for (String literal : new String[]{"true", "false", "null"}) {
                if (text.startsWith(literal, at)) {
                    at += literal.length();
                    return literal.equals("null") ? null : Boolean.valueOf(literal);
                }
            }
            int start = at;
            while (at < text.length() && "-+0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
            String number = text.substring(start, at);
            if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw new Failure("MALFORMED");
            try {
                // Do not accept tokens which downstream JS would turn into Infinity.
                if (!Double.isFinite(Double.parseDouble(number))) throw new Failure("MALFORMED");
                return new BigDecimal(number);
            } catch (NumberFormatException e) { throw new Failure("MALFORMED"); }
        }
        String string() throws Failure {
            if (!take('"')) throw new Failure("MALFORMED");
            StringBuilder result = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') {
                    String value = result.toString(); utf8(value, FRAME_LIMIT); return value;
                }
                if (c < 32) throw new Failure("MALFORMED");
                if (c == '\\') {
                    if (at >= text.length()) throw new Failure("MALFORMED");
                    c = text.charAt(at++);
                    switch (c) {
                        case '"': case '\\': case '/': break;
                        case 'b': c = '\b'; break;
                        case 'f': c = '\f'; break;
                        case 'n': c = '\n'; break;
                        case 'r': c = '\r'; break;
                        case 't': c = '\t'; break;
                        case 'u':
                            if (at + 4 > text.length()) throw new Failure("MALFORMED");
                            String hex = text.substring(at, at + 4);
                            if (!hex.matches("[0-9a-fA-F]{4}")) throw new Failure("MALFORMED");
                            c = (char) Integer.parseInt(hex, 16); at += 4; break;
                        default: throw new Failure("MALFORMED");
                    }
                }
                result.append(c);
            }
            throw new Failure("MALFORMED");
        }
    }
}
