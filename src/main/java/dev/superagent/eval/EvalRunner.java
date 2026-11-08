package dev.superagent.eval;

import dev.superagent.model.Config;
import dev.superagent.model.ModelClient;
import dev.superagent.storage.Store;
import dev.superagent.task.Task;
import dev.superagent.task.TaskManager;
import dev.superagent.task.Workspaces;
import dev.superagent.tool.Processes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 评测执行器：每个任务独立临时仓库 + 独立状态目录，走真实的
 * {@link TaskManager} / Worker / {@link Workspaces} / {@link Store} 链路，
 * 结束后按 checks 判定并保留证据目录。
 */
public final class EvalRunner {

    @FunctionalInterface
    public interface ModelFactory {
        ModelClient create(EvalTask task, Config config) throws Exception;
    }

    public record Options(int maxCalls, long maxTokens, Duration taskTimeout, Duration commandTimeout,
                          boolean autoApprove) {
        public Options {
            if (maxCalls < 1) throw new IllegalArgumentException("maxCalls 必须大于零");
            if (maxTokens < 1) throw new IllegalArgumentException("maxTokens 必须大于零");
            if (taskTimeout == null || taskTimeout.isNegative() || taskTimeout.isZero())
                throw new IllegalArgumentException("任务超时必须大于零");
            if (commandTimeout == null || commandTimeout.isNegative() || commandTimeout.isZero())
                throw new IllegalArgumentException("命令超时必须大于零");
        }

        public static Options standard() {
            return new Options(40, 1_000_000L, Duration.ofSeconds(300), Duration.ofSeconds(180), true);
        }
    }

    private static final Set<Task.Status> TERMINAL = EnumSet.of(Task.Status.READY, Task.Status.COMPLETED,
            Task.Status.FAILED, Task.Status.CANCELED, Task.Status.INTERRUPTED, Task.Status.BUDGET_EXCEEDED,
            Task.Status.WAITING_INPUT);
    private static final Set<Task.Status> FAILED_STATUSES = EnumSet.of(Task.Status.FAILED,
            Task.Status.INTERRUPTED, Task.Status.BUDGET_EXCEEDED);
    private static final Duration CANCEL_GRACE = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private final Path artifactsRoot;
    private final Function<Path, Config> configs;
    private final ModelFactory models;
    private final String mode;
    private final String modelName;
    private final Options options;

    public EvalRunner(Path artifactsRoot, Function<Path, Config> configs, ModelFactory models,
                      String mode, String modelName, Options options) {
        this.artifactsRoot = artifactsRoot.toAbsolutePath().normalize();
        this.configs = configs;
        this.models = models;
        this.mode = mode;
        this.modelName = modelName;
        this.options = options;
    }

    public EvalReport run(EvalSuite suite) {
        Instant started = Instant.now();
        List<EvalTaskResult> results = new ArrayList<>();
        for (EvalTask task : suite.tasks()) results.add(runOne(task));
        return new EvalReport(mode, modelName, started.toString(), Instant.now().toString(),
                artifactsRoot.toString(), results);
    }

    /** 跑一个任务。任何异常都转成该任务的失败原因，不中断整个数据集。 */
    public EvalTaskResult runOne(EvalTask task) {
        Path directory = artifactsRoot.resolve("eval-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        Path source = directory.resolve("source");
        Path state = directory.resolve("state");
        Path evidence = directory.resolve("evidence");
        List<String> notices = Collections.synchronizedList(new ArrayList<>());
        long start = System.nanoTime();
        List<String> failures = new ArrayList<>();
        List<EvalChecks.Outcome> outcomes = List.of();
        Task snapshot = null;
        boolean timedOut = false;
        try {
            Files.createDirectories(evidence);
            createRepository(source, evidence, task);
            Config config = configs.apply(state);
            ModelClient model = models.create(task, config);
            try (Store store = new Store(state);
                 TaskManager manager = new TaskManager(config, store, model, source, notices::add)) {
                manager.start();
                Task created = manager.create(task.prompt());
                Await awaited = awaitTerminal(manager, created.id);
                snapshot = awaited.task();
                timedOut = awaited.timedOut();
                captureEvidence(manager, snapshot, evidence, notices);
                outcomes = EvalChecks.evaluate(task.checks(), new EvalChecks.Context(
                        Path.of(snapshot.workspace), snapshot.baseCommit, combinedAnswer(snapshot),
                        evidence, options.commandTimeout()));
            }
        } catch (Exception error) {
            failures.add("执行异常: " + describe(error));
        }
        if (snapshot != null) {
            if (timedOut) {
                failures.add("任务在 " + options.taskTimeout().toSeconds() + " 秒内未结束，已取消");
            } else if (FAILED_STATUSES.contains(snapshot.status)) {
                failures.add("任务结束状态 " + snapshot.status
                        + (isBlank(snapshot.error) ? "" : "：" + snapshot.error));
            } else if (!options.autoApprove() && snapshot.status == Task.Status.WAITING_APPROVAL) {
                failures.add("命令仍在等待审批，评测没有自动批准（autoApprove=false）");
            }
        }
        for (int i = 0; i < outcomes.size(); i++) {
            EvalChecks.Outcome outcome = outcomes.get(i);
            if (outcome.passed()) continue;
            String label = i < task.checks().size() ? task.checks().get(i).describe() : outcome.op();
            failures.add(label + " 未通过: " + outcome.detail());
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        return new EvalTaskResult(task.id(), task.name(), failures.isEmpty(),
                snapshot == null ? "ERROR" : snapshot.status.name(),
                snapshot == null ? "" : snapshot.error, failures,
                snapshot == null ? 0 : snapshot.calls, snapshot == null ? 0 : snapshot.tokens,
                durationMs, directory.toString(), snapshot == null ? "" : combinedAnswer(snapshot),
                outcomes, snapshot == null ? List.of() : List.copyOf(snapshot.validations));
    }

    // ---------------------------------------------------------------- 执行

    private record Await(Task task, boolean timedOut) {}

    /**
     * 轮询到任务进入终态。命令执行按需自动批准（评测数据集是受信任的本地样例），
     * 但每条命令仍由 Worker 正式走审批流程并记录退出码。
     */
    private Await awaitTerminal(TaskManager manager, String id) throws Exception {
        long deadline = System.nanoTime() + options.taskTimeout().toNanos();
        while (true) {
            Task current = manager.get(id);
            if (current.status == Task.Status.WAITING_APPROVAL) {
                if (!options.autoApprove()) return new Await(current, false);
                Task.Call call = current.outstanding();
                if (call != null && call.approvalId != null) {
                    try {
                        manager.approve(call.approvalId, true);
                    } catch (IllegalArgumentException ignored) {
                        // 审批已处理或已失效，下一轮重新读取状态。
                    }
                }
            }
            if (TERMINAL.contains(current.status)) return new Await(current, false);
            if (System.nanoTime() > deadline) {
                try {
                    manager.cancel(id);
                } catch (RuntimeException ignored) {
                    // 状态已经变化，下面的等待会读到最新状态。
                }
                long grace = System.nanoTime() + CANCEL_GRACE.toNanos();
                Task latest = current;
                while (System.nanoTime() < grace) {
                    latest = manager.get(id);
                    if (TERMINAL.contains(latest.status)) break;
                    Thread.sleep(POLL_INTERVAL.toMillis());
                }
                return new Await(latest, true);
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
    }

    private void createRepository(Path source, Path evidence, EvalTask task) throws Exception {
        Files.createDirectories(source);
        if (task.setupFiles().isEmpty()) Files.writeString(source.resolve(".gitkeep"), "", StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : task.setupFiles().entrySet()) {
            Path target = EvalChecks.resolve(source, entry.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
        }
        git(source, evidence, "init", "-q");
        git(source, evidence, "add", "-A");
        git(source, evidence, "-c", "user.name=Super-agent Eval", "-c", "user.email=eval@example.invalid",
                "commit", "-q", "-m", task.setupCommit());
    }

    private void captureEvidence(TaskManager manager, Task snapshot, Path evidence, List<String> notices) {
        try {
            Files.writeString(evidence.resolve("task.log"),
                    String.join("\n", new ArrayList<>(notices)) + "\n", StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 证据不是判定依据，写失败不影响结论。
        }
        try {
            Path workspace = Path.of(snapshot.workspace);
            String head = git(workspace, evidence, "rev-parse", "HEAD");
            String status = git(workspace, evidence, "status", "--porcelain");
            Files.writeString(evidence.resolve("git-status.txt"),
                    "HEAD " + head.strip() + "\n" + status, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 任务可能还没准备副本。
        }
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                Workspaces.Delivery delivery = manager.diff(snapshot.id);
                Files.copy(delivery.patch(), evidence.resolve("changes.patch"),
                        StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IllegalArgumentException busy) {
                try {
                    Thread.sleep(POLL_INTERVAL.toMillis());
                } catch (InterruptedException interrupted) {
                    return;
                }
            } catch (Exception other) {
                return;
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    private static String git(Path cwd, Path evidence, String... args) throws Exception {
        List<String> argv = new ArrayList<>(List.of("git", "-c", "core.hooksPath=", "-c", "core.autocrlf=false",
                "-c", "core.fsmonitor=false", "--no-pager", "--no-optional-locks"));
        argv.addAll(List.of(args));
        Path log = evidence.resolve("git-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + ".log");
        Processes.Result result = Processes.run(argv, cwd, log, Duration.ofSeconds(60), process -> {});
        if (result.timedOut() || result.exitCode() != 0)
            throw new IllegalStateException("Git 失败（" + String.join(" ", args) + "）: " + clip(result.output()));
        return result.output();
    }

    private static String combinedAnswer(Task task) {
        StringBuilder text = new StringBuilder();
        if (!isBlank(task.result)) text.append(task.result.strip());
        if (!isBlank(task.question)) {
            if (text.length() > 0) text.append('\n');
            text.append(task.question.strip());
        }
        return text.toString();
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (isBlank(message) ? "" : ": " + message);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String clip(String value) {
        if (value == null) return "";
        String flat = value.replace("\r", " ").replace('\n', ' ').strip();
        return flat.length() <= 600 ? flat : flat.substring(0, 600) + "…";
    }
}
