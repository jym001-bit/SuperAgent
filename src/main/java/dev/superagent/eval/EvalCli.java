package dev.superagent.eval;

import dev.superagent.model.Config;
import dev.superagent.model.HttpModelClient;
import dev.superagent.tool.Processes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 评测入口。默认离线模式（脚本化模型）；只有显式传 {@code -Real} 才调用真实模型。
 *
 * <p>退出码：0 = 全部任务通过；1 = 有任务未通过；2 = 参数、数据集或模型配置错误。
 */
public final class EvalCli {
    private static final String USAGE = """
            用法: eval.ps1 [选项]

              (无参数)            离线模式：ScriptedModel 按脚本执行，只验证 harness 与执行链路
              -Real               真实模型模式：从环境变量或 .env 读取 Key，显式传参才会运行
              -Only a,b           只运行指定任务 id（逗号分隔，避免拼错后静默跑全量）
              -Tasks <目录>       数据集目录，默认 eval/tasks
              -Out <目录>         报告输出目录，默认 target/eval-reports
              -Artifacts <目录>   证据目录根，默认 target（每个任务一个 eval-<uuid> 子目录）
              -TimeoutSeconds N   单任务超时秒数，默认 300
              -List               只列出数据集任务，不执行
              -Help               打印本帮助

            退出码: 0 全部通过；1 存在未通过任务；2 参数、数据集或模型配置错误。
            """;

    private EvalCli() {}

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    static int execute(String[] args) {
        List<String> only = new ArrayList<>();
        Path tasks = Path.of("eval", "tasks");
        Path reports = Path.of("target", "eval-reports");
        Path artifacts = Path.of("target");
        int timeoutSeconds = 300;
        boolean real = false;
        boolean list = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "-Real", "-real", "--real" -> real = true;
                    case "-List", "-list", "--list" -> list = true;
                    case "-Tasks", "-tasks", "--tasks" -> tasks = Path.of(value(args, ++i, args[i - 1]));
                    case "-Out", "-out", "--out" -> reports = Path.of(value(args, ++i, args[i - 1]));
                    case "-Artifacts", "-artifacts", "--artifacts" ->
                            artifacts = Path.of(value(args, ++i, args[i - 1]));
                    case "-TimeoutSeconds", "-timeout", "--timeout" ->
                            timeoutSeconds = Integer.parseInt(value(args, ++i, args[i - 1]));
                    case "-Only", "-only", "--only" -> {
                        for (String id : value(args, ++i, args[i - 1]).split(","))
                            if (!id.isBlank()) only.add(id.strip());
                    }
                    case "-Help", "-help", "--help", "-h" -> {
                        System.out.println(USAGE);
                        return 0;
                    }
                    default -> {
                        System.err.println("未知参数: " + args[i]);
                        System.err.println(USAGE);
                        return 2;
                    }
                }
            }
            if (timeoutSeconds < 1) throw new IllegalArgumentException("-TimeoutSeconds 必须大于零");
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            System.err.println(USAGE);
            return 2;
        }

        EvalSuite suite;
        try {
            suite = EvalSuite.load(tasks);
            suite = suite.only(only);
        } catch (Exception error) {
            System.err.println("读取评测数据集失败: " + message(error));
            return 2;
        }
        if (list) {
            for (EvalTask task : suite.tasks())
                System.out.printf(Locale.ROOT, "%-24s %-34s checks=%d script=%d%n",
                        task.id(), task.name(), task.checks().size(), task.script().size());
            return 0;
        }

        EvalRunner.Options options = new EvalRunner.Options(40, 1_000_000L,
                Duration.ofSeconds(timeoutSeconds), Duration.ofSeconds(180), true);
        String mode;
        String modelName;
        Function<Path, Config> configs;
        EvalRunner.ModelFactory models;
        if (real) {
            Config environment = realEnvironment();
            if (environment == null) return 2;
            mode = "real";
            modelName = environment.model();
            configs = state -> new Config(state, environment.baseUrl(), environment.model(),
                    environment.apiKey(), environment.provider(), environment.thinking(), 1,
                    options.maxCalls(), options.maxTokens(), environment.commandTimeout(),
                    environment.httpTimeout());
            models = (task, config) -> new HttpModelClient(config);
            System.out.println("模式: 真实模型（" + environment.model() + " @ " + environment.baseUrl()
                    + "）；会产生模型调用费用，结果随模型和提示词波动。");
        } else {
            mode = "offline";
            modelName = "scripted";
            configs = state -> new Config(state, "http://127.0.0.1:1", "scripted", "offline", "standard",
                    false, 1, options.maxCalls(), options.maxTokens(), options.commandTimeout(),
                    Duration.ofSeconds(10));
            models = (task, config) -> {
                if (task.script().isEmpty())
                    throw new IllegalArgumentException("离线模式需要任务脚本 script: " + task.id());
                return new ScriptedModel(task.script());
            };
            System.out.println("模式: 离线（ScriptedModel 按脚本执行，只验证评测 harness 与执行链路，"
                    + "不评价真实模型的编码能力）。");
        }
        preflight();
        System.out.println("数据集: " + tasks.toAbsolutePath().normalize() + "（" + suite.size() + " 个任务）");
        System.out.println("证据目录: " + artifacts.toAbsolutePath().normalize());
        EvalReport report = new EvalRunner(artifacts, configs, models, mode, modelName, options).run(suite);
        try {
            report.writeTo(reports);
        } catch (Exception error) {
            System.err.println("写入评测报告失败: " + message(error));
            return 2;
        }
        printReport(report, reports, real);
        return report.failed() == 0 ? 0 : 1;
    }

    /** 真实模型模式必须有 Key；缺失时给出可操作的中文提示，绝不悄悄换成离线模式。 */
    private static Config realEnvironment() {
        Config environment;
        try {
            environment = Config.environment();
        } catch (RuntimeException error) {
            System.err.println("读取模型配置失败: " + message(error));
            return null;
        }
        if (environment.apiKey() == null || environment.apiKey().isBlank()) {
            System.err.println("真实模型模式未找到 API Key。请先设置环境变量 SUPERAGENT_API_KEY 或 DEEPSEEK_API_KEY，");
            System.err.println("或在项目根目录的 .env 中配置 DEEPSEEK_API_KEY（.env 已被 .gitignore 忽略）。");
            System.err.println("不想调用真实模型时不要传 -Real：离线模式不联网、不消耗额度。");
            return null;
        }
        return environment;
    }

    private static void preflight() {
        warn("git", "--version", "所有任务都需要 Git 创建隔离仓库，no_changes 判据也依赖 Git");
        warn("javac", "--version", "部分任务用 javac 编译检查");
        warn("java", "--version", "部分任务用 java 运行检查");
        warn("mvn", "--version", "部分任务用 mvn -o test 检查");
    }

    private static void warn(String command, String flag, String consequence) {
        if (!available(command, flag))
            System.out.println("警告: PATH 中找不到 " + command + "，" + consequence + "。");
    }

    private static boolean available(String command, String flag) {
        try {
            Path log = Files.createTempFile("eval-preflight-", ".log");
            // 通过 shell 解析（Windows 上 mvn 是 mvn.cmd，ProcessBuilder 不能直接执行脚本）。
            Processes.Result result = Processes.run(Processes.shell(command + " " + flag),
                    Path.of(".").toAbsolutePath(), log, Duration.ofSeconds(60), process -> {});
            return !result.timedOut() && result.exitCode() == 0;
        } catch (Exception error) {
            return false;
        }
    }

    private static void printReport(EvalReport report, Path reports, boolean real) {
        System.out.println();
        System.out.printf(Locale.ROOT, "总任务 %d，通过 %d，失败 %d，通过率 %.1f%%%n",
                report.total(), report.passed(), report.failed(), report.passRate() * 100);
        System.out.printf(Locale.ROOT,
                "平均模型调用 %.2f 次，平均 tokens %.1f，平均耗时 %.0f ms；总计 %d 次调用 / %d tokens / %d ms%n",
                report.avgModelCalls(), report.avgTokens(), report.avgDurationMs(),
                report.totalModelCalls(), report.totalTokens(), report.totalDurationMs());
        System.out.println();
        for (EvalTaskResult task : report.tasks()) {
            System.out.printf(Locale.ROOT, "[%s] %-24s 状态=%-16s 模型调用=%d tokens=%d %d ms%n",
                    task.success() ? "PASS" : "FAIL", task.id(), task.status(),
                    task.modelCalls(), task.tokens(), task.durationMs());
            for (String failure : task.failures()) System.out.println("       - " + failure);
            System.out.println("       证据: " + task.artifactsDir());
        }
        System.out.println();
        System.out.println("报告: " + reports.resolve(EvalReport.FILE_JSON).toAbsolutePath());
        System.out.println("      " + reports.resolve(EvalReport.FILE_MARKDOWN).toAbsolutePath());
        System.out.println(real
                ? "提醒: 真实模型结果随模型、提示词和网络波动，不能与离线脚本结果混为一谈。"
                : "提醒: 离线模式通过只证明评测 harness 与执行链路可用，不代表真实模型的编码能力。");
    }

    private static String value(String[] args, int index, String flag) {
        if (index >= args.length) throw new IllegalArgumentException("参数 " + flag + " 缺少取值");
        return args[index];
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
