package dev.superagent.worker;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.superagent.model.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文治理：任务历史完整保存，但送给模型的请求必须受预算约束。
 *
 * <p>裁剪规则（顺序敏感）：
 * <ol>
 *   <li>system 提示与首条用户需求是「锚点」，永不裁剪，否则模型会丢失目标与约束；</li>
 *   <li>工具调用协议不可拆开：一条带 tool_calls 的 assistant 消息与其 tool 结果同属一轮，整轮保留或整轮折叠；</li>
 *   <li>从最新一轮往前保留，放不下的早期轮次折叠成一条确定性摘要（抓取式，不是模型生成，不伪造结论）；</li>
 *   <li>至少保留最后一轮，即使单轮超预算，也要让模型看到最近的工具结果。</li>
 * </ol>
 */
public final class ContextPolicy {
    /** 每次请求的上下文预算（token 估算值），可用环境变量覆盖。 */
    public static final String BUDGET_ENV = "SUPERAGENT_CONTEXT_TOKENS";
    private static final long DEFAULT_BUDGET = 48_000;
    /** 摘要本身也必须受预算约束：折叠轮次很多时只保留最近若干轮的线索，其余只报数量。 */
    private static final long SUMMARY_RESERVE = 1_400;
    private static final int SUMMARY_CHARS = 2_400;
    private static final int SUMMARY_ASSISTANT = 200;
    private static final int SUMMARY_TOOL = 160;

    private ContextPolicy() {}

    public static long budget() {
        String raw = System.getenv(BUDGET_ENV);
        if (raw == null || raw.isBlank()) return DEFAULT_BUDGET;
        try {
            long value = Long.parseLong(raw.strip());
            return value > 0 ? value : DEFAULT_BUDGET;
        } catch (NumberFormatException ignored) { return DEFAULT_BUDGET; }
    }

    public record Result(List<ObjectNode> messages, int folded, int kept, long estimatedTokens) {
        public boolean trimmed() { return folded > 0; }
    }

    /** 4 字符 ≈ 1 token 是英文口径，中文更密；这里按 2 字符 = 1 token 保守估算。 */
    public static long estimate(List<ObjectNode> messages) {
        long characters = 0;
        for (ObjectNode message : messages) characters += Json.text(message).length();
        return Math.max(1, characters / 2);
    }

    public static Result prepare(List<ObjectNode> messages, long budgetTokens) {
        long total = estimate(messages);
        if (total <= budgetTokens || messages.size() <= 2)
            return new Result(messages, 0, messages.size(), total);

        int head = 0;
        List<ObjectNode> anchor = new ArrayList<>();
        List<ObjectNode> rest = new ArrayList<>(messages);
        if (!rest.isEmpty() && "system".equals(rest.get(0).path("role").asText())) {
            anchor.add(rest.get(0));
            head = 1;
        }
        // 首条用户需求是第二锚点：它携带原始任务与验收约束。
        if (rest.size() > head && "user".equals(rest.get(head).path("role").asText())) {
            anchor.add(rest.get(head));
            head++;
        }
        rest = new ArrayList<>(rest.subList(head, rest.size()));

        List<List<ObjectNode>> turns = group(rest);
        long fixed = estimate(anchor);
        int firstKept = turns.size();
        long keptCost = 0;
        while (firstKept > 0) {
            List<ObjectNode> turn = turns.get(firstKept - 1);
            long cost = estimate(turn);
            if (fixed + keptCost + cost + SUMMARY_RESERVE > budgetTokens) break;
            keptCost += cost;
            firstKept--;
        }
        if (firstKept == turns.size() && !turns.isEmpty()) {
            // 连最后一轮都放不下：无论如何保留它，宁可超预算也不能丢失最近的工具结果。
            firstKept = turns.size() - 1;
            keptCost = estimate(turns.get(firstKept));
        }

        List<ObjectNode> result = new ArrayList<>(anchor);
        if (firstKept > 0) result.add(summary(turns.subList(0, firstKept), firstKept));
        for (int i = firstKept; i < turns.size(); i++) result.addAll(turns.get(i));

        // 摘要文本长度事先不可知，用实际估算再收敛一次。
        while (firstKept < turns.size() - 1 && estimate(result) > budgetTokens) {
            firstKept++;
            result = new ArrayList<>(anchor);
            result.add(summary(turns.subList(0, firstKept), firstKept));
            for (int i = firstKept; i < turns.size(); i++) result.addAll(turns.get(i));
        }
        return new Result(List.copyOf(result), firstKept, turns.size() - firstKept, estimate(result));
    }

    /** 按协议分组：一条 assistant 消息开启一轮，其后直到下一条 assistant 的消息属于该轮。 */
    private static List<List<ObjectNode>> group(List<ObjectNode> messages) {
        List<List<ObjectNode>> turns = new ArrayList<>();
        List<ObjectNode> current = null;
        for (ObjectNode message : messages) {
            boolean assistant = "assistant".equals(message.path("role").asText());
            if (assistant || current == null) {
                current = new ArrayList<>();
                turns.add(current);
            }
            current.add(message);
        }
        return turns;
    }

    private static ObjectNode summary(List<List<ObjectNode>> dropped, int count) {
        List<String> lines = new ArrayList<>();
        int used = 0;
        int omitted = 0;
        for (int i = dropped.size() - 1; i >= 0; i--) {
            String line = describe(dropped.get(i));
            if (used + line.length() > SUMMARY_CHARS) { omitted = i + 1; break; }
            lines.add(line);
            used += line.length();
        }
        java.util.Collections.reverse(lines);
        StringBuilder text = new StringBuilder("[上下文折叠] 早期 ").append(count)
                .append(" 轮已从请求中省略，完整记录仍保存在任务历史里。");
        if (omitted > 0) text.append("其中最早的 ").append(omitted).append(" 轮连摘要也省略。");
        text.append("\n确定性摘要：\n");
        for (String line : lines) text.append(line).append('\n');
        return Json.message("user", text.toString());
    }

    private static String describe(List<ObjectNode> turn) {
        StringBuilder line = new StringBuilder("- ");
        for (ObjectNode message : turn) {
            String role = message.path("role").asText();
            if ("assistant".equals(role)) {
                String content = message.path("content").asText("").strip();
                if (!content.isEmpty()) line.append("助手: ").append(clip(content, SUMMARY_ASSISTANT)).append("; ");
                var calls = message.path("tool_calls");
                if (calls.isArray()) for (var call : calls)
                    line.append("调用 ").append(call.path("function").path("name").asText("?")).append("; ");
            } else if ("tool".equals(role)) {
                line.append("工具结果: ").append(clip(message.path("content").asText("").strip(), SUMMARY_TOOL)).append("; ");
            } else if ("user".equals(role)) {
                line.append("用户: ").append(clip(message.path("content").asText("").strip(), SUMMARY_ASSISTANT)).append("; ");
            }
        }
        return line.toString();
    }

    private static String clip(String value, int size) {
        String flat = value.replace('\n', ' ');
        return flat.length() <= size ? flat : flat.substring(0, size) + "…";
    }
}
