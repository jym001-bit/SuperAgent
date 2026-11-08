package dev.superagent.eval;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.superagent.model.Json;
import java.util.List;

/** 单个任务的评测结果与指标。 */
public record EvalTaskResult(String id, String name, boolean success, String status, String error,
                             List<String> failures, int modelCalls, long tokens, long durationMs,
                             String artifactsDir, String answer, List<EvalChecks.Outcome> checks,
                             List<String> commands) {

    private static final int ANSWER_LIMIT = 2000;

    public EvalTaskResult {
        failures = List.copyOf(failures == null ? List.of() : failures);
        checks = List.copyOf(checks == null ? List.of() : checks);
        commands = List.copyOf(commands == null ? List.of() : commands);
    }

    public ObjectNode toJson() {
        ObjectNode root = Json.object();
        root.put("id", id);
        root.put("name", name);
        root.put("success", success);
        root.put("status", status);
        root.put("error", error);
        ArrayNode failureArray = Json.array();
        failures.forEach(failureArray::add);
        root.set("failures", failureArray);
        root.put("modelCalls", modelCalls);
        root.put("tokens", tokens);
        root.put("durationMs", durationMs);
        root.put("artifactsDir", artifactsDir);
        root.put("answer", clip(answer));
        ArrayNode checkArray = Json.array();
        for (EvalChecks.Outcome check : checks) {
            ObjectNode node = Json.object();
            node.put("op", check.op());
            node.put("passed", check.passed());
            node.put("detail", check.detail());
            checkArray.add(node);
        }
        root.set("checks", checkArray);
        ArrayNode commandArray = Json.array();
        commands.forEach(commandArray::add);
        root.set("commands", commandArray);
        return root;
    }

    private static String clip(String value) {
        if (value == null) return "";
        return value.length() <= ANSWER_LIMIT ? value : value.substring(0, ANSWER_LIMIT) + "…";
    }
}
