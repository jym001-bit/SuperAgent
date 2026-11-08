package dev.superagent.worker;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.superagent.model.Json;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上下文治理的行为验收：预算、锚点、工具协议完整性、最近一轮必须保留。
 * 删掉裁剪实现或改坏分组逻辑，这些用例会失败。
 */
class ContextPolicyTest {

    private static List<ObjectNode> conversation(int turns, int toolResultSize) {
        List<ObjectNode> messages = new ArrayList<>();
        messages.add(Json.message("system", "你是 Worker，只处理当前任务。"));
        messages.add(Json.message("user", "原始需求：把 Hello.java 的输出改成 Hello, Super-agent! 并先读文件再改。"));
        for (int i = 0; i < turns; i++) {
            ObjectNode assistant = Json.message("assistant", i % 3 == 0 ? "第" + i + "轮分析" : "");
            ObjectNode call = Json.object().put("id", "call_" + i).put("type", "function");
            call.set("function", Json.object().put("name", "read_file").put("arguments", "{\"path\":\"F" + i + ".java\"}"));
            assistant.set("tool_calls", Json.array().add(call));
            messages.add(assistant);
            messages.add(Json.message("tool", "第" + i + "轮读取结果 " + "x".repeat(toolResultSize)).put("tool_call_id", "call_" + i));
        }
        return messages;
    }

    @Test void messagesWithinBudgetArePassedThroughUntouched() {
        List<ObjectNode> messages = conversation(3, 100);
        ContextPolicy.Result result = ContextPolicy.prepare(messages, 100_000);
        assertEquals(0, result.folded());
        assertEquals(messages.size(), result.kept());
        assertFalse(result.trimmed());
        assertEquals(messages, result.messages());
        assertTrue(result.estimatedTokens() <= 100_000);
    }

    @Test void anchorsAndToolProtocolSurviveTrimming() {
        List<ObjectNode> messages = conversation(60, 2_000);
        long budget = 6_000;
        ContextPolicy.Result result = ContextPolicy.prepare(messages, budget);

        assertTrue(result.folded() > 0, "预算明显不足时必须折叠");
        assertTrue(result.kept() > 0);
        assertEquals("system", result.messages().get(0).path("role").asText(), "system 提示是锚点");
        assertEquals("user", result.messages().get(1).path("role").asText());
        assertTrue(result.messages().get(1).path("content").asText().contains("Hello, Super-agent!"),
                "原始需求与验收约束不能被裁掉");
        assertTrue(result.messages().get(2).path("content").asText().contains("上下文折叠"), "折叠说明必须在请求里可见");
        assertTrue(result.messages().get(2).path("content").asText().contains("read_file"), "摘要要保留工具调用线索");

        // 工具协议完整性：带 tool_calls 的 assistant 后面必须紧跟对应的 tool 结果。
        for (int i = 0; i < result.messages().size(); i++) {
            ObjectNode message = result.messages().get(i);
            if (!"assistant".equals(message.path("role").asText())) continue;
            var calls = message.path("tool_calls");
            if (!calls.isArray()) continue;
            for (int c = 0; c < calls.size(); c++) {
                if (i + 1 + c >= result.messages().size()) fail("工具调用被截断，缺少对应的 tool 消息");
                ObjectNode reply = result.messages().get(i + 1 + c);
                assertEquals("tool", reply.path("role").asText(), "assistant 之后必须紧跟 tool 结果");
                assertEquals(calls.get(c).path("id").asText(), reply.path("tool_call_id").asText());
            }
        }
        assertTrue(result.estimatedTokens() <= budget, "收敛后不能超出预算，实际 " + result.estimatedTokens());
    }

    @Test void latestTurnIsKeptEvenWhenItAloneExceedsBudget() {
        List<ObjectNode> messages = conversation(4, 40_000);
        ContextPolicy.Result result = ContextPolicy.prepare(messages, 2_000);
        assertEquals(3, result.folded(), "超预算的旧轮次全部折叠");
        assertEquals(1, result.kept());
        ObjectNode last = result.messages().get(result.messages().size() - 1);
        assertEquals("tool", last.path("role").asText());
        assertTrue(last.path("content").asText().contains("第3轮读取结果"), "最近一轮的工具结果必须送到模型");
    }

    @Test void estimateScalesWithContent() {
        long small = ContextPolicy.estimate(List.of(Json.message("user", "短")));
        long large = ContextPolicy.estimate(List.of(Json.message("user", "长".repeat(10_000))));
        assertTrue(large > small * 100, "估算必须随内容增长，否则预算形同虚设");
        assertTrue(ContextPolicy.budget() > 0);
    }
}
