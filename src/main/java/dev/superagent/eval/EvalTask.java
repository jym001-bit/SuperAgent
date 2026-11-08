package dev.superagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import dev.superagent.model.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一个评测任务：真实需求（prompt）、临时仓库预置内容（setup）、机械判据（checks），
 * 以及离线模式使用的确定性脚本（script）。
 */
public record EvalTask(String id, String name, String prompt, Map<String, String> setupFiles,
                       String setupCommit, List<EvalCheck> checks, List<ScriptedModel.Step> script) {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{1,63}");

    public EvalTask {
        id = requireText(id, "id");
        if (!ID.matcher(id).matches())
            throw new IllegalArgumentException("任务 id 只能是小写字母、数字和连字符: " + id);
        name = requireText(name, "name");
        prompt = requireText(prompt, "prompt");
        Map<String, String> files = new LinkedHashMap<>();
        if (setupFiles != null) {
            for (Map.Entry<String, String> entry : setupFiles.entrySet()) {
                String path = requireText(entry.getKey(), "setup.files 的路径");
                relative(path);
                files.put(path, Objects.requireNonNullElse(entry.getValue(), ""));
            }
        }
        setupFiles = Map.copyOf(files);
        setupCommit = (setupCommit == null || setupCommit.isBlank()) ? "eval: initial setup" : setupCommit.strip();
        checks = List.copyOf(checks == null ? List.of() : checks);
        if (checks.isEmpty()) throw new IllegalArgumentException("任务 " + id + " 至少需要一条 check");
        script = List.copyOf(script == null ? List.of() : script);
    }

    public static EvalTask parse(String json) {
        return fromJson(Json.parse(json));
    }

    public static EvalTask fromJson(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("任务必须是 JSON 对象");
        String id = EvalJson.requiredText(node, "id");
        Map<String, String> files = new LinkedHashMap<>();
        JsonNode setup = node.get("setup");
        if (setup != null && !setup.isNull()) {
            if (!setup.isObject()) throw new IllegalArgumentException("任务 " + id + " 的 setup 必须是对象");
            JsonNode content = setup.get("files");
            if (content != null && !content.isNull()) {
                if (!content.isObject()) throw new IllegalArgumentException("任务 " + id + " 的 setup.files 必须是对象");
                var names = content.fieldNames();
                while (names.hasNext()) {
                    String path = names.next();
                    files.put(path, EvalJson.stringOrLines(content.get(path), "setup.files." + path));
                }
            }
        }
        List<EvalCheck> checks = new ArrayList<>();
        JsonNode checkNodes = node.get("checks");
        if (checkNodes == null || !checkNodes.isArray() || checkNodes.isEmpty())
            throw new IllegalArgumentException("任务 " + id + " 至少需要一条 check");
        for (JsonNode check : checkNodes) checks.add(EvalCheck.fromJson(check));
        JsonNode scriptNode = node.get("script");
        List<ScriptedModel.Step> script = ScriptedModel.parse(scriptNode);
        return new EvalTask(id, EvalJson.requiredText(node, "name"), EvalJson.requiredText(node, "prompt"),
                files, EvalJson.optionalText(node, "setupCommit"), checks, script);
    }

    /** 保证 setup 里的路径不会写出临时仓库。 */
    static String relative(String path) {
        String value = requireText(path, "路径").replace('\\', '/');
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*"))
            throw new IllegalArgumentException("setup 路径必须是相对路径: " + path);
        for (String part : value.split("/"))
            if (part.equals("..")) throw new IllegalArgumentException("setup 路径不允许包含 ..: " + path);
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少字符串字段: " + field);
        return value.strip();
    }
}
