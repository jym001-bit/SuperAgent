package dev.superagent.eval;

import dev.superagent.tool.Processes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 解释执行 checks。判据失败必须能定位到具体文件、命令或回答片段，
 * 这样报告里的 failures 才能直接用来排查，而不是只给一个 false。
 */
public final class EvalChecks {

    /** 一条判据的执行结论。 */
    public record Outcome(String op, boolean passed, String detail) {}

    /**
     * @param workspace    任务副本（模型改动的目录）
     * @param baseCommit   任务基线提交，供 no_changes 使用
     * @param answer       模型最终文本（含提问）
     * @param evidenceDir  命令日志等证据目录
     */
    public record Context(Path workspace, String baseCommit, String answer, Path evidenceDir,
                          Duration commandTimeout) {}

    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(120);

    private EvalChecks() {}

    public static List<Outcome> evaluate(List<EvalCheck> checks, Context context) {
        List<Outcome> outcomes = new ArrayList<>();
        boolean workspace = context.workspace() != null && Files.isDirectory(context.workspace());
        for (EvalCheck check : checks) {
            if (!workspace) {
                outcomes.add(new Outcome(check.op(), false,
                        "任务副本不存在，无法判定: " + context.workspace()));
                continue;
            }
            try {
                outcomes.add(evaluateOne(check, context));
            } catch (Exception error) {
                outcomes.add(new Outcome(check.op(), false, "判据执行失败: " + message(error)));
            }
        }
        return outcomes;
    }

    private static Outcome evaluateOne(EvalCheck check, Context context) throws Exception {
        return switch (check.op()) {
            case "file_equals" -> fileEquals(check, context);
            case "file_contains" -> fileContains(check, context);
            case "file_absent" -> fileAbsent(check, context);
            case "no_changes" -> noChanges(check, context);
            case "command" -> command(check, context);
            case "answer_contains" -> answerContains(check, context);
            default -> new Outcome(check.op(), false, "未知判据 op: " + check.op());
        };
    }

    private static Outcome fileEquals(EvalCheck check, Context context) throws IOException {
        Path file = resolve(context.workspace(), check.path());
        if (!Files.isRegularFile(file)) return fail(check, "文件不存在: " + check.path());
        String actual = normalize(Files.readString(file, StandardCharsets.UTF_8));
        String expected = normalize(check.content());
        if (!actual.equals(expected))
            return fail(check, "内容不一致: " + check.path() + difference(expected, actual));
        return pass(check);
    }

    private static Outcome fileContains(EvalCheck check, Context context) throws IOException {
        Path file = resolve(context.workspace(), check.path());
        if (!Files.isRegularFile(file)) return fail(check, "文件不存在: " + check.path());
        if (!Files.readString(file, StandardCharsets.UTF_8).contains(check.text()))
            return fail(check, "不包含预期文本 <" + clip(check.text()) + ">: " + check.path());
        return pass(check);
    }

    private static Outcome fileAbsent(EvalCheck check, Context context) {
        if (Files.exists(resolve(context.workspace(), check.path())))
            return fail(check, "文件仍然存在: " + check.path());
        return pass(check);
    }

    private static Outcome noChanges(EvalCheck check, Context context) throws Exception {
        Path workspace = context.workspace();
        String status;
        try {
            status = git(workspace, context.evidenceDir(), "status", "--porcelain").strip();
        } catch (Exception error) {
            return fail(check, "无法读取 Git 状态: " + message(error));
        }
        List<String> problems = new ArrayList<>();
        if (!status.isEmpty()) problems.add("工作区有改动: " + summarize(status));
        if (context.baseCommit() != null && !context.baseCommit().isBlank()) {
            String head = git(workspace, context.evidenceDir(), "rev-parse", "HEAD").strip();
            if (!head.equals(context.baseCommit()))
                problems.add("HEAD 已从基线移动: " + shortHash(context.baseCommit()) + " -> " + shortHash(head));
        }
        if (!problems.isEmpty()) return fail(check, String.join("；", problems));
        return pass(check);
    }

    private static Outcome command(EvalCheck check, Context context) throws Exception {
        Duration timeout = check.timeoutSeconds() > 0
                ? Duration.ofSeconds(check.timeoutSeconds()) : commandTimeout(context);
        Path log = logFile(context.evidenceDir(), "check-command-");
        Processes.Result result = Processes.run(Processes.shell(check.command()), context.workspace(), log,
                timeout, process -> {});
        if (result.timedOut())
            return fail(check, "命令超时（" + timeout.toSeconds() + " 秒）: " + check.command() + "，日志: " + log);
        if (result.exitCode() != check.exitCode())
            return fail(check, "命令退出码 " + result.exitCode() + "，期望 " + check.exitCode() + ": "
                    + check.command() + outputHint(result.output()) + "，日志: " + log);
        if (check.outputContains() != null && !result.output().contains(check.outputContains()))
            return fail(check, "命令输出不包含 <" + clip(check.outputContains()) + ">: " + check.command()
                    + outputHint(result.output()) + "，日志: " + log);
        return pass(check);
    }

    private static Outcome answerContains(EvalCheck check, Context context) {
        String answer = context.answer() == null ? "" : context.answer();
        if (answer.isBlank()) return fail(check, "模型没有给出最终文本");
        if (!answer.contains(check.text()))
            return fail(check, "最终回答不包含 <" + clip(check.text()) + ">，实际回答: " + clip(answer));
        return pass(check);
    }

    // ---------------------------------------------------------------- helpers

    static Path resolve(Path root, String relative) {
        if (relative == null || relative.isBlank()) throw new IllegalArgumentException("缺少路径参数");
        Path base = root.toAbsolutePath().normalize();
        Path path = base.resolve(relative.replace('\\', '/')).normalize();
        if (!path.startsWith(base)) throw new IllegalArgumentException("路径越出任务副本: " + relative);
        return path;
    }

    /**
     * 比较前统一换行符、去掉每行行尾空白和末尾空行。
     * 允许模型在行尾多一个空格或文件末尾多一个换行，但不忽略缩进或内容差异。
     */
    static String normalize(String text) {
        String[] lines = (text == null ? "" : text).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> kept = new ArrayList<>(lines.length);
        for (String line : lines) kept.add(line.stripTrailing());
        while (!kept.isEmpty() && kept.get(kept.size() - 1).isEmpty()) kept.remove(kept.size() - 1);
        return String.join("\n", kept);
    }

    private static String difference(String expected, String actual) {
        String[] left = expected.split("\n", -1);
        String[] right = actual.split("\n", -1);
        for (int i = 0; i < Math.min(left.length, right.length); i++)
            if (!left[i].equals(right[i]))
                return "，第 " + (i + 1) + " 行: 期望 <" + clip(left[i]) + ">，实际 <" + clip(right[i]) + ">";
        if (left.length != right.length)
            return "，行数: 期望 " + left.length + "，实际 " + right.length;
        return "";
    }

    private static String summarize(String status) {
        List<String> lines = new ArrayList<>();
        for (String line : status.split("\n")) {
            if (line.isBlank()) continue;
            lines.add(line.strip());
            if (lines.size() >= 8) break;
        }
        return String.join(" | ", lines) + (status.split("\n").length > lines.size() ? " | …" : "");
    }

    private static String shortHash(String value) {
        return value == null || value.length() <= 12 ? String.valueOf(value) : value.substring(0, 12);
    }

    private static String outputHint(String output) {
        return output == null || output.isBlank() ? "" : "，输出: " + clip(output);
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static Duration commandTimeout(Context context) {
        return context.commandTimeout() == null ? DEFAULT_COMMAND_TIMEOUT : context.commandTimeout();
    }

    private static String clip(String value) {
        if (value == null) return "";
        String flat = value.replace("\r", " ").replace('\n', ' ').strip();
        return flat.length() <= 400 ? flat : flat.substring(0, 400) + "…";
    }

    private static Path logFile(Path evidenceDir, String prefix) throws IOException {
        Path directory = evidenceDir == null ? Files.createTempDirectory("eval-check-") : evidenceDir;
        Files.createDirectories(directory);
        return directory.resolve(prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + ".log");
    }

    private static String git(Path workspace, Path evidenceDir, String... args) throws Exception {
        List<String> argv = new ArrayList<>(List.of("git", "-c", "core.hooksPath=", "-c", "core.autocrlf=false",
                "-c", "core.fsmonitor=false", "--no-pager", "--no-optional-locks"));
        argv.addAll(List.of(args));
        Path log = logFile(evidenceDir, "git-");
        Processes.Result result = Processes.run(argv, workspace, log, Duration.ofSeconds(30), process -> {});
        if (result.timedOut() || result.exitCode() != 0)
            throw new IllegalStateException("Git 命令失败（" + String.join(" ", args) + "）: " + clip(result.output()));
        return result.output();
    }

    private static Outcome pass(EvalCheck check) {
        return new Outcome(check.op(), true, "");
    }

    private static Outcome fail(EvalCheck check, String detail) {
        return new Outcome(check.op(), false, detail);
    }
}
