package com.nowweather.core.json;

/**
 * 递归下降 JSON 解析器。
 *
 * <p>在严格 JSON 的基础上做了几处容错（第三方 API 常见）：
 * 允许尾随逗号、允许单引号字符串、允许 {@code NaN}/{@code Infinity} 之类非标准字面量（解析为 null）。
 * 合法的标准 JSON 行为完全一致。</p>
 */
public final class JsonParser {

    private final String text;
    private int pos;

    private JsonParser(String text) {
        this.text = text;
    }

    public static JsonValue parse(String text) {
        if (text == null) {
            throw new JsonException("JSON 文本为 null");
        }
        JsonParser parser = new JsonParser(text);
        parser.skipBom();
        parser.skipWhitespace();
        if (parser.eof()) {
            throw new JsonException("JSON 文本为空");
        }
        JsonValue value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.eof()) {
            throw new JsonException("JSON 尾部存在多余内容（位置 " + parser.pos + "）");
        }
        return value;
    }

    // ------------------------------------------------------------------ 内部实现

    private JsonValue parseValue() {
        skipWhitespace();
        if (eof()) {
            throw new JsonException("JSON 意外结束");
        }
        char c = peek();
        switch (c) {
            case '{':
                return parseObject();
            case '[':
                return parseArray();
            case '"':
            case '\'':
                return JsonPrimitive.of(parseString());
            case 't':
                expectLiteral("true");
                return JsonPrimitive.of(true);
            case 'f':
                expectLiteral("false");
                return JsonPrimitive.of(false);
            case 'n':
                expectLiteral("null");
                return JsonPrimitive.nul();
            case 'N':
                expectLiteral("NaN");
                return JsonPrimitive.nul();
            default:
                if (c == '-' || c == '+' || c == '.' || (c >= '0' && c <= '9') || c == 'I') {
                    return parseNumber();
                }
                throw new JsonException("无法识别的 JSON 记号 '" + c + "'（位置 " + pos + "）");
        }
    }

    private JsonObject parseObject() {
        expect('{');
        JsonObject object = new JsonObject();
        skipWhitespace();
        if (peekIs('}')) {
            pos++;
            return object;
        }
        while (true) {
            skipWhitespace();
            if (peekIs('}')) { // 容错：尾随逗号
                pos++;
                return object;
            }
            String key;
            if (peekIs('"') || peekIs('\'')) {
                key = parseString();
            } else {
                key = parseUnquotedKey();
            }
            skipWhitespace();
            expect(':');
            JsonValue value = parseValue();
            object.put(key, value);
            skipWhitespace();
            if (peekIs(',')) {
                pos++;
                continue;
            }
            if (peekIs('}')) {
                pos++;
                return object;
            }
            throw new JsonException("对象中期望 ',' 或 '}'（位置 " + pos + "）");
        }
    }

    private JsonArray parseArray() {
        expect('[');
        JsonArray array = new JsonArray();
        skipWhitespace();
        if (peekIs(']')) {
            pos++;
            return array;
        }
        while (true) {
            skipWhitespace();
            if (peekIs(']')) { // 容错：尾随逗号
                pos++;
                return array;
            }
            array.add(parseValue());
            skipWhitespace();
            if (peekIs(',')) {
                pos++;
                continue;
            }
            if (peekIs(']')) {
                pos++;
                return array;
            }
            throw new JsonException("数组中期望 ',' 或 ']'（位置 " + pos + "）");
        }
    }

    private String parseUnquotedKey() {
        int start = pos;
        while (!eof()) {
            char c = peek();
            if (c == ':' || c == ',' || c == '}' || Character.isWhitespace(c)) {
                break;
            }
            pos++;
        }
        if (pos == start) {
            throw new JsonException("对象键为空（位置 " + pos + "）");
        }
        return text.substring(start, pos);
    }

    private JsonValue parseNumber() {
        int start = pos;
        if (peekIs('+') || peekIs('-')) {
            pos++;
        }
        if (matchesIgnoreCase("Infinity")) {
            pos += "Infinity".length();
            return JsonPrimitive.nul();
        }
        boolean floating = false;
        while (!eof()) {
            char c = peek();
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E') {
                floating = true;
                pos++;
            } else if ((c == '+' || c == '-') && pos > start && (text.charAt(pos - 1) == 'e' || text.charAt(pos - 1) == 'E')) {
                pos++;
            } else {
                break;
            }
        }
        String raw = text.substring(start, pos);
        if (raw.isEmpty() || raw.equals("-") || raw.equals("+")) {
            throw new JsonException("非法数字（位置 " + start + "）");
        }
        if (!floating) {
            try {
                return JsonPrimitive.of(Long.parseLong(raw));
            } catch (NumberFormatException ignored) {
                // 超出 long 范围，退化成 double
            }
        }
        try {
            return JsonPrimitive.of(Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            throw new JsonException("非法数字: " + raw, e);
        }
    }

    private String parseString() {
        char quote = peek();
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (eof()) {
                throw new JsonException("字符串未闭合");
            }
            char c = text.charAt(pos++);
            if (c == quote) {
                return sb.toString();
            }
            if (c == '\\') {
                if (eof()) {
                    throw new JsonException("转义序列未完成");
                }
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\'' -> sb.append('\'');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw new JsonException("\\u 转义不完整");
                        }
                        String hex = text.substring(pos, pos + 4);
                        pos += 4;
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw new JsonException("非法 \\u 转义: " + hex, e);
                        }
                    }
                    default -> sb.append(esc);
                }
                continue;
            }
            sb.append(c);
        }
    }

    // ------------------------------------------------------------------ 小工具

    private void skipBom() {
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            pos = 1;
        }
    }

    private void skipWhitespace() {
        while (!eof()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private boolean eof() {
        return pos >= text.length();
    }

    private char peek() {
        return text.charAt(pos);
    }

    private boolean peekIs(char c) {
        return !eof() && text.charAt(pos) == c;
    }

    private void expect(char c) {
        skipWhitespace();
        if (eof() || text.charAt(pos) != c) {
            throw new JsonException("期望 '" + c + "'（位置 " + pos + "）");
        }
        pos++;
    }

    private void expectLiteral(String literal) {
        if (!matchesIgnoreCase(literal)) {
            throw new JsonException("期望字面量 " + literal + "（位置 " + pos + "）");
        }
        pos += literal.length();
    }

    private boolean matchesIgnoreCase(String literal) {
        if (pos + literal.length() > text.length()) {
            return false;
        }
        return text.regionMatches(true, pos, literal, 0, literal.length());
    }
}
