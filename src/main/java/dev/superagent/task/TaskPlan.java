package dev.superagent.task;

import com.fasterxml.jackson.databind.JsonNode;
import dev.superagent.model.Json;
import dev.superagent.model.ModelClient;
import java.util.*;

public final class TaskPlan {
    public record Node(String key, String goal, String acceptance, List<String> dependencies) {}
    record Proposal(List<Node> nodes, long tokens) {}

    private TaskPlan() {}

    static Proposal propose(ModelClient model, String goal) throws Exception {
        var response = model.complete(List.of(
                Json.message("system", """
                        你是代码任务规划器。仅输出 JSON 对象，格式为
                        {"nodes":[{"key":"a","goal":"具体工作","acceptance":"验收条件","dependencies":[]}]}
                        拆为 1 至 8 个子任务，key 使用英文字母、数字或下划线。
                        dependencies 是必须先交付的节点 key。独立工作并行，修改同一文件的步骤优先串行。
                        子任务在独立代码副本里执行，只获得其依赖的改动。保留原需求与约束。
                        如果用户要求先审批、获批后运行测试，安排执行节点调用 execute_command 触发正式审批，
                        获批后执行并报告实际退出码。不要把“等批准再执行”改写成“本轮只提议命令”。
                        只有用户明确要求只提供命令时，才安排不执行命令的交付。
                        """),
                Json.message("user", goal)), null);
        JsonNode calls = response.message().path("tool_calls");
        if (!"stop".equals(response.finishReason()) || (!calls.isMissingNode() && !calls.isNull()
                && (!calls.isArray() || !calls.isEmpty())))
            throw new IllegalArgumentException("模型未返回完整计划");
        return new Proposal(parse(Json.parse(response.message().path("content").asText())), response.tokens());
    }

    static List<Node> parse(JsonNode document) {
        if (document == null || !document.isObject()) throw new IllegalArgumentException("计划必须是 JSON 对象");
        JsonNode nodes = document.path("nodes");
        if (!nodes.isArray() || nodes.isEmpty() || nodes.size() > 8)
            throw new IllegalArgumentException("计划必须包含 1 至 8 个子任务");
        Map<String, Node> byKey = new LinkedHashMap<>();
        for (JsonNode node : nodes) {
            String key = required(node, "key", 32);
            if (!key.matches("[a-zA-Z][a-zA-Z0-9_]{0,31}") || byKey.containsKey(key))
                throw new IllegalArgumentException("无效或重复的节点 ID: " + key);
            JsonNode references = node.path("dependencies");
            if (!references.isArray() || references.size() > 8)
                throw new IllegalArgumentException("依赖必须是节点 ID 数组");
            Set<String> dependencies = new LinkedHashSet<>();
            for (JsonNode ref : references) {
                if (!ref.isTextual() || !dependencies.add(ref.asText()))
                    throw new IllegalArgumentException("无效或重复的依赖: " + key);
            }
            byKey.put(key, new Node(key, required(node, "goal", 4000),
                    required(node, "acceptance", 2000), List.copyOf(dependencies)));
        }
        for (Node node : byKey.values()) {
            if (!byKey.keySet().containsAll(node.dependencies()))
                throw new IllegalArgumentException("节点引用了不存在的依赖: " + node.key());
        }
        List<Node> sorted = new ArrayList<>();
        Set<String> included = new HashSet<>();
        while (sorted.size() < byKey.size()) {
            Node next = byKey.values().stream()
                    .filter(n -> !included.contains(n.key()) && included.containsAll(n.dependencies()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("计划包含循环依赖"));
            sorted.add(next);
            included.add(next.key());
        }
        return List.copyOf(sorted);
    }

    private static String required(JsonNode object, String field, int limit) {
        JsonNode value = object.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > limit)
            throw new IllegalArgumentException("计划字段无效: " + field);
        return value.asText().strip();
    }
}
