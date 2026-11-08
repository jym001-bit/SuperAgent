package dev.superagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/**
 * 一条机械判据。数据集只用 JSON 描述「怎么判」，本类负责解析并校验参数，
 * 真正执行在 {@link EvalChecks} 中完成。
 */
public record EvalCheck(String op, String path, String text, String content, String command,
                        int exitCode, String outputContains, int timeoutSeconds) {

    public static final Set<String> OPS = Set.of(
            "file_equals", "file_contains", "file_absent", "no_changes", "command", "answer_contains");

    public static EvalCheck fromJson(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("check 必须是 JSON 对象");
        String op = EvalJson.requiredText(node, "op");
        return switch (op) {
            case "file_equals" -> new EvalCheck(op, EvalJson.requiredText(node, "path"), null,
                    EvalJson.textOrLines(node, "content"), null, 0, null, 0);
            case "file_contains" -> new EvalCheck(op, EvalJson.requiredText(node, "path"),
                    EvalJson.requiredText(node, "text"), null, null, 0, null, 0);
            case "file_absent" -> new EvalCheck(op, EvalJson.requiredText(node, "path"), null, null, null, 0, null, 0);
            case "no_changes" -> new EvalCheck(op, null, null, null, null, 0, null, 0);
            case "command" -> new EvalCheck(op, null, null, null, EvalJson.requiredText(node, "command"),
                    EvalJson.optionalInt(node, "exitCode", 0), EvalJson.optionalText(node, "outputContains"),
                    EvalJson.optionalInt(node, "timeoutSeconds", 0));
            case "answer_contains" -> new EvalCheck(op, null, EvalJson.requiredText(node, "text"),
                    null, null, 0, null, 0);
            default -> throw new IllegalArgumentException("未知判据 op: " + op + "（支持 " + OPS + "）");
        };
    }

    /** 报告里使用的简短描述。 */
    public String describe() {
        return switch (op) {
            case "file_equals" -> "file_equals " + path;
            case "file_contains" -> "file_contains " + path + " <" + clip(text) + ">";
            case "file_absent" -> "file_absent " + path;
            case "no_changes" -> "no_changes（相对基线无改动）";
            case "command" -> "command " + clip(command) + "（期望退出码 " + exitCode + "）";
            case "answer_contains" -> "answer_contains <" + clip(text) + ">";
            default -> op;
        };
    }

    private static String clip(String value) {
        if (value == null) return "";
        String flat = value.replace('\n', ' ');
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }
}
