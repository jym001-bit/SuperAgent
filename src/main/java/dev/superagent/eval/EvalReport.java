package dev.superagent.eval;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.superagent.model.Json;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** 一次评测运行的汇总：逐任务结果 + 可量化指标，并输出 JSON / Markdown 两份报告。 */
public record EvalReport(String mode, String model, String startedAt, String finishedAt,
                         String artifactsRoot, List<EvalTaskResult> tasks) {

    public static final String FILE_JSON = "eval-report.json";
    public static final String FILE_MARKDOWN = "eval-report.md";

    public EvalReport {
        tasks = List.copyOf(tasks == null ? List.of() : tasks);
    }

    public int total() {
        return tasks.size();
    }

    public int passed() {
        return (int) tasks.stream().filter(EvalTaskResult::success).count();
    }

    public int failed() {
        return total() - passed();
    }

    public double passRate() {
        return total() == 0 ? 0d : passed() / (double) total();
    }

    /** 传输层失败的个数：这些任务根本没跑起来，不能算作模型能力失败。 */
    public int infraFailures() {
        return (int) tasks.stream().filter(EvalTaskResult::infraFailure).count();
    }

    /** 扣掉传输层失败后，真正由模型/判据决定的任务数。 */
    public int modelAttributable() {
        return total() - infraFailures();
    }

    /** 模型口径通过率：分母不含传输层失败。 */
    public double modelPassRate() {
        return modelAttributable() == 0 ? 0d : passed() / (double) modelAttributable();
    }

    public long totalModelCalls() {
        return tasks.stream().mapToLong(EvalTaskResult::modelCalls).sum();
    }

    public long totalTokens() {
        return tasks.stream().mapToLong(EvalTaskResult::tokens).sum();
    }

    public long totalDurationMs() {
        return tasks.stream().mapToLong(EvalTaskResult::durationMs).sum();
    }

    public double avgModelCalls() {
        return total() == 0 ? 0d : totalModelCalls() / (double) total();
    }

    public double avgTokens() {
        return total() == 0 ? 0d : totalTokens() / (double) total();
    }

    public double avgDurationMs() {
        return total() == 0 ? 0d : totalDurationMs() / (double) total();
    }

    public String json() {
        ObjectNode root = Json.object();
        root.put("schema", 1);
        root.put("mode", mode);
        root.put("model", model);
        root.put("modeNote", modeNote());
        root.put("startedAt", startedAt);
        root.put("finishedAt", finishedAt);
        root.put("artifactsRoot", artifactsRoot);
        root.put("total", total());
        root.put("passed", passed());
        root.put("failed", failed());
        root.put("passRate", round(passRate(), 4));
        root.put("infraFailures", infraFailures());
        root.put("modelAttributable", modelAttributable());
        root.put("modelPassRate", round(modelPassRate(), 4));
        root.put("totalModelCalls", totalModelCalls());
        root.put("avgModelCalls", round(avgModelCalls(), 2));
        root.put("totalTokens", totalTokens());
        root.put("avgTokens", round(avgTokens(), 2));
        root.put("totalDurationMs", totalDurationMs());
        root.put("avgDurationMs", round(avgDurationMs(), 1));
        ArrayNode array = Json.array();
        for (EvalTaskResult task : tasks) array.add(task.toJson());
        root.set("tasks", array);
        try {
            return Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception error) {
            throw new IllegalStateException("无法序列化评测报告", error);
        }
    }

    public String markdown() {
        StringBuilder text = new StringBuilder();
        text.append("# Super-agent 评测报告\n\n");
        text.append("- 模式：").append(modeLabel()).append('\n');
        text.append("- 模型：").append(model).append('\n');
        text.append("- 开始：").append(startedAt).append('\n');
        text.append("- 结束：").append(finishedAt).append('\n');
        text.append("- 证据目录：`").append(artifactsRoot)
                .append("`（每个任务一个 `eval-<uuid>` 子目录，保留 source / state / evidence）\n");
        text.append("- 汇总：总任务 ").append(total()).append(" / 通过 ").append(passed())
                .append(" / 失败 ").append(failed()).append(" / 通过率 ")
                .append(percent()).append('\n');
        text.append("- 口径：其中传输层失败 ").append(infraFailures())
                .append(" 个（连接失败、请求超时、5xx，不属于模型能力）；模型口径通过率 ")
                .append(String.format(Locale.ROOT, "%.1f%%", modelPassRate() * 100))
                .append("（").append(passed()).append('/').append(modelAttributable()).append("）\n");
        text.append("- 平均：模型调用 ").append(format(avgModelCalls(), 2))
                .append(" 次，tokens ").append(format(avgTokens(), 1))
                .append("，耗时 ").append(format(avgDurationMs(), 0)).append(" ms；总计模型调用 ")
                .append(totalModelCalls()).append(" 次，tokens ").append(totalTokens())
                .append("，耗时 ").append(totalDurationMs()).append(" ms\n\n");
        text.append("> ").append(modeNote()).append("\n\n");
        text.append("| 任务 | 名称 | 结果 | 状态 | 模型调用 | Tokens | 耗时(ms) | 失败原因 |\n");
        text.append("|---|---|---|---|---:|---:|---:|---|\n");
        for (EvalTaskResult task : tasks) {
            text.append("| `").append(task.id()).append("` | ").append(escape(task.name()))
                    .append(" | ").append(task.success() ? "通过" : (task.infraFailure() ? "失败(传输)" : "失败"))
                    .append(" | ").append(task.status())
                    .append(" | ").append(task.modelCalls())
                    .append(" | ").append(task.tokens())
                    .append(" | ").append(task.durationMs())
                    .append(" | ").append(task.failures().isEmpty() ? "" : escape(String.join("；", task.failures())))
                    .append(" |\n");
        }
        List<EvalTaskResult> failedTasks = tasks.stream().filter(task -> !task.success()).toList();
        text.append("\n## 证据目录\n\n");
        text.append("| 任务 | 证据目录（source / state / evidence 均已保留） |\n|---|---|\n");
        for (EvalTaskResult task : tasks)
            text.append("| `").append(task.id()).append("` | `").append(escape(task.artifactsDir())).append("` |\n");
        if (!failedTasks.isEmpty()) {
            text.append("\n## 判据失败明细\n");
            for (EvalTaskResult task : failedTasks) {
                text.append("\n### ").append(task.id()).append("（").append(task.status()).append("）\n");
                for (String failure : task.failures()) text.append("- ").append(escape(failure)).append('\n');
                text.append("- 证据目录：`").append(task.artifactsDir()).append("`\n");
            }
        }
        return text.toString();
    }

    public void writeTo(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(FILE_JSON), json() + "\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(FILE_MARKDOWN), markdown(), StandardCharsets.UTF_8);
    }

    /** 明确说明这份数字能证明什么、不能证明什么，避免把脚本化流程通过写成模型能力已验证。 */
    public String modeNote() {
        if ("real".equals(mode))
            return "真实模型模式：使用真实 HTTP 模型服务，结果包含模型自身的成功率和成本；同一数据集多次运行会有波动。";
        return "离线模式：ScriptedModel 按脚本返回固定工具调用，只验证评测 harness 与执行链路（Git 副本、Worker、"
                + "命令审批、判据、报告），不评价真实模型的编码能力。";
    }

    private String modeLabel() {
        return "real".equals(mode) ? "真实模型（真实 HTTP 调用）" : "离线（脚本化模型）";
    }

    private String percent() {
        return String.format(Locale.ROOT, "%.1f%%", passRate() * 100);
    }

    private static String format(double value, int scale) {
        return new BigDecimal(value).setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private static double round(double value, int scale) {
        return new BigDecimal(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }
}
