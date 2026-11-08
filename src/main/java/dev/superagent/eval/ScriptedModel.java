package dev.superagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.superagent.model.Json;
import dev.superagent.model.ModelClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 离线评测用的确定性模型替身。它按数据集里的 script 顺序返回固定的工具调用或最终回答，
 * 用于验证评测 harness 自身与「Router/TaskManager/Worker/Workspaces/Store」执行链路，
 * 不代表任何真实模型的能力。
 */
public final class ScriptedModel implements ModelClient {
    public sealed interface Step permits ToolCall, Answer, Failure {}

    public record ToolCall(String name, ObjectNode arguments) implements Step {}

    public record Answer(String content) implements Step {}

    public record Failure(String message) implements Step {}

    private static final String SCRIPT_EXHAUSTED = "脚本已执行完毕（离线评测按脚本终止）";
    private static final long DEFAULT_TOKENS = 8;

    private final List<Step> steps;
    private final long tokensPerCall;
    private final AtomicInteger invocations = new AtomicInteger();

    public ScriptedModel(List<Step> steps) {
        this(steps, DEFAULT_TOKENS);
    }

    public ScriptedModel(List<Step> steps, long tokensPerCall) {
        this.steps = List.copyOf(steps == null ? List.of() : steps);
        this.tokensPerCall = Math.max(1, tokensPerCall);
    }

    /** 实际被调用的次数，用于交叉核对报告里的 modelCalls。 */
    public int invocations() {
        return invocations.get();
    }

    @Override
    public Reply complete(List<ObjectNode> messages, ArrayNode tools) {
        int index = invocations.getAndIncrement();
        if (index >= steps.size())
            return new Reply(Json.message("assistant", SCRIPT_EXHAUSTED), 1, "stop");
        Step step = steps.get(index);
        if (step instanceof ToolCall call) {
            ObjectNode message = Json.message("assistant", "");
            ObjectNode tool = Json.object().put("type", "function").put("id", "call_script_" + index);
            tool.set("function", Json.object().put("name", call.name())
                    .put("arguments", Json.text(call.arguments())));
            message.set("tool_calls", Json.array().add(tool));
            return new Reply(message, tokensPerCall, "tool_calls");
        }
        if (step instanceof Answer answer)
            return new Reply(Json.message("assistant", answer.content()), tokensPerCall, "stop");
        Failure failure = (Failure) step;
        throw new IllegalStateException("脚本第 " + (index + 1) + " 步要求失败: " + failure.message());
    }

    public static List<Step> parse(JsonNode script) {
        if (script == null || script.isNull()) return List.of();
        if (!script.isArray()) throw new IllegalArgumentException("script 必须是数组");
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < script.size(); i++) {
            JsonNode node = script.get(i);
            if (!node.isObject())
                throw new IllegalArgumentException("script 第 " + (i + 1) + " 步必须是对象");
            if (node.hasNonNull("tool")) {
                String name = EvalJson.requiredText(node, "tool");
                ObjectNode arguments = arguments(node);
                steps.add(new ToolCall(name, arguments));
            } else if (node.hasNonNull("answer")) {
                steps.add(new Answer(EvalJson.requiredText(node, "answer")));
            } else if (node.hasNonNull("error")) {
                steps.add(new Failure(EvalJson.requiredText(node, "error")));
            } else {
                throw new IllegalArgumentException("script 第 " + (i + 1) + " 步需要 tool、answer 或 error 之一");
            }
        }
        return steps;
    }

    private static ObjectNode arguments(JsonNode step) {
        JsonNode raw = step.get("arguments");
        ObjectNode arguments;
        if (raw == null || raw.isNull()) {
            arguments = Json.object();
        } else if (raw.isObject()) {
            arguments = ((ObjectNode) raw).deepCopy();
        } else {
            throw new IllegalArgumentException("script 的 arguments 必须是对象");
        }
        linesToText(arguments, "contentLines", "content");
        return arguments;
    }

    /** 允许把 content 写成字符串数组，手写 JSON 时不必转义整段源码。 */
    private static void linesToText(ObjectNode arguments, String linesField, String textField) {
        JsonNode lines = arguments.get(linesField);
        if (lines == null) {
            JsonNode text = arguments.get(textField);
            if (text == null || !text.isArray()) return;
            arguments.set(textField, Json.MAPPER.valueToTree(join(text, textField)));
            return;
        }
        if (!lines.isArray()) throw new IllegalArgumentException(linesField + " 必须是字符串数组");
        arguments.set(textField, Json.MAPPER.valueToTree(join(lines, linesField)));
        arguments.remove(linesField);
    }

    private static String join(JsonNode lines, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode line : lines) {
            if (!line.isTextual()) throw new IllegalArgumentException(field + " 的数组元素必须是字符串");
            values.add(line.asText());
        }
        return String.join("\n", values);
    }
}
