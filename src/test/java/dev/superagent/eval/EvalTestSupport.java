package dev.superagent.eval;

import dev.superagent.model.Config;
import dev.superagent.tool.Processes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 评测测试的本地夹具：临时仓库、离线配置、真实命令字符串。 */
final class EvalTestSupport {
    private EvalTestSupport() {}

    static Config config(Path state) {
        return new Config(state, "http://127.0.0.1:1", "scripted", "offline", "standard", false, 1, 40,
                1_000_000L, Duration.ofSeconds(120), Duration.ofSeconds(5));
    }

    /** 与 CLI 离线模式一致的 runner：脚本化模型，走真实 TaskManager/Worker/Workspaces。 */
    static EvalRunner runner(Path artifacts) {
        return new EvalRunner(artifacts, EvalTestSupport::config,
                (task, config) -> new ScriptedModel(task.script()), "offline", "scripted",
                EvalRunner.Options.standard());
    }

    static Path repository(Path root, Map<String, String> files) throws Exception {
        Files.createDirectories(root);
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path target = root.resolve(entry.getKey());
            Files.createDirectories(target.getParent() == null ? root : target.getParent());
            Files.writeString(target, entry.getValue());
        }
        git(root, "init", "-q");
        git(root, "add", "-A");
        git(root, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-q", "-m", "setup");
        return root;
    }

    static String git(Path cwd, String... args) throws Exception {
        List<String> argv = new ArrayList<>(List.of("git", "-c", "core.autocrlf=false", "--no-pager"));
        argv.addAll(List.of(args));
        Path log = Files.createTempFile("eval-git-", ".log");
        Processes.Result result = Processes.run(argv, cwd, log, Duration.ofSeconds(30), process -> {});
        if (result.timedOut() || result.exitCode() != 0)
            throw new IllegalStateException("Git 失败: " + result.output());
        return result.output();
    }

    static String head(Path repository) throws Exception {
        return git(repository, "rev-parse", "HEAD").strip();
    }

    static boolean windows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString();
    }

    static String javaVersionCommand() {
        return windows() ? "& '" + javaExecutable() + "' -version" : "'" + javaExecutable() + "' -version";
    }

    /** 先写文件再以非零退出码结束，用来证明 command 判据真的执行了命令。 */
    static String writeThenFailCommand() {
        return windows() ? "Set-Content -LiteralPath proof.txt -Value ran; exit 7"
                : "printf ran > proof.txt; exit 7";
    }
}
