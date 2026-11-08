package dev.superagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** 数据集 JSON 的小工具：字段必须明确，出错时给出可读的中文位置说明。 */
final class EvalJson {
    private EvalJson() {}

    static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank())
            throw new IllegalArgumentException("缺少字符串字段: " + field);
        return value.asText();
    }

    static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static int optionalInt(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isIntegralNumber()) throw new IllegalArgumentException(field + " 必须是整数");
        return value.asInt();
    }

    /** 字段既接受字符串，也接受字符串数组（数组按 \n 连接，便于手写 Java 源码）。 */
    static String textOrLines(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) value = node.get(field + "Lines");
        if (value == null) throw new IllegalArgumentException("缺少字段: " + field);
        return stringOrLines(value, field);
    }

    static String stringOrLines(JsonNode value, String what) {
        if (value == null || value.isNull()) throw new IllegalArgumentException("缺少内容字段: " + what);
        if (value.isTextual()) return value.asText();
        if (value.isArray()) {
            List<String> lines = new ArrayList<>();
            for (JsonNode line : value) {
                if (!line.isTextual()) throw new IllegalArgumentException(what + " 的数组元素必须是字符串");
                lines.add(line.asText());
            }
            return String.join("\n", lines);
        }
        throw new IllegalArgumentException(what + " 必须是字符串或字符串数组");
    }
}
