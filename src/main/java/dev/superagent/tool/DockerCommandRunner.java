package dev.superagent.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Each approved command gets a fresh, offline container with a read-only input snapshot. */
public final class DockerCommandRunner implements CommandRunner {
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(15);
    private static final String SCRIPT = """
            set -eu
            for path in /input/.[!.]* /input/..?* /input/*; do
                [ -e "$path" ] || [ -L "$path" ] || continue
                [ "$path" = /input/.git ] || cp -R -P -- "$path" /work/
            done
            cd /work
            exec /bin/sh -c "$1"
            """;
    private final Path state;
    private final String image;
    private final String label;

    public DockerCommandRunner(Path state, String image) throws Exception {
        if (!image.matches("[a-zA-Z0-9][a-zA-Z0-9./:_-]*@sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("SUPERAGENT_SANDBOX_IMAGE 必须使用带 SHA-256 digest 的镜像引用");
        this.state = state;
        this.image = image;
        this.label = "dev.superagent.state=" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(state.toRealPath().toString().getBytes(StandardCharsets.UTF_8)));
    }

    public void verifyAndClean() throws Exception {
        var context = control(List.of("context", "inspect", "--format", "{{.Endpoints.docker.Host}}"));
        checked(context, "无法检查 Docker context");
        if (!context.output().strip().startsWith("unix:///"))
            throw new IllegalStateException("沙箱 Docker context 必须使用本机 Unix socket");
        var info = control(List.of("info", "--format",
                "{{.CgroupVersion}} {{.CgroupDriver}} {{.MemoryLimit}} {{.SwapLimit}} {{.CPUCfsQuota}} {{.PidsLimit}} {{json .SecurityOptions}}"));
        checked(info, "无法连接 Docker");
        if (info.output().lines().noneMatch(line -> line.startsWith("2 systemd true true true true [")
                && line.contains("\"name=rootless\"")))
            throw new IllegalStateException("需要 rootless Docker、systemd cgroup v2 及 CPU、内存、swap、进程数限制");
        checked(control(List.of("image", "inspect", "--format", "{{.Id}}", image)), "镜像不可用");
        var existing = control(List.of("ps", "-aq", "--filter", "label=" + label));
        checked(existing, "无法查询沙箱");
        for (String id : existing.output().lines().filter(line -> !line.isBlank()).toList()) {
            if (!id.matches("[0-9a-f]{12,64}")) throw new IllegalStateException("Docker 返回了无效容器 ID");
            checked(control(List.of("rm", "-f", id)), "无法回收遗留沙箱");
        }
    }

    List<String> arguments(String command, Path workspace, String name) {
        if (workspace.toString().contains(",") || workspace.toString().contains("\n")
                || workspace.toString().contains("\r") || workspace.toString().contains("\""))
            throw new IllegalArgumentException("沙箱工作目录包含不支持的字符");
        return List.of("docker", "create", "--name", name, "--label", label, "--pull=never",
                "--network=none", "--cap-drop=ALL", "--security-opt=no-new-privileges=true",
                "--read-only", "--user=0:0", "--pids-limit=128", "--cpus=1",
                "--memory=768m", "--memory-swap=768m", "--log-driver=none",
                "--tmpfs", "/work:rw,nosuid,nodev,size=512m,mode=1777",
                "--tmpfs", "/tmp:rw,nosuid,nodev,size=128m,mode=1777",
                "--mount", "type=bind,source=" + workspace + ",target=/input,readonly,bind-recursive=disabled",
                "--env", "HOME=/tmp", "--entrypoint", "/bin/sh", image, "-c", SCRIPT, "superagent", command);
    }

    @Override public Processes.Result run(String command, Path workspace, Path log, Duration timeout,
                                          Consumer<Process> started) throws Exception {
        String name = "sa-" + UUID.randomUUID();
        try {
            var created = Processes.run(arguments(command, workspace.toRealPath(), name), state,
                    log.resolveSibling(log.getFileName() + ".create"), CONTROL_TIMEOUT, process -> {});
            checked(created, "无法创建沙箱");
            return Processes.runLimited(List.of("docker", "start", "-a", name), state,
                    log, timeout, started, 8 * 1024 * 1024);
        } finally {
            // Interrupting docker's client is not sufficient to stop its container.
            boolean interrupted = Thread.interrupted();
            try {
                var cleanup = control(List.of("rm", "-f", name));
                if (cleanup.timedOut() || (cleanup.exitCode() != 0 && !cleanup.output().contains("No such container")))
                    throw new IllegalStateException("沙箱清理结果不明，容器 " + name);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private Processes.Result control(List<String> args) throws Exception {
        List<String> argv = new ArrayList<>(List.of("docker"));
        argv.addAll(args);
        return Processes.run(argv, state, state.resolve("logs").resolve("docker-" + UUID.randomUUID() + ".log"),
                CONTROL_TIMEOUT, process -> {});
    }

    private static void checked(Processes.Result result, String message) {
        if (result.timedOut() || result.exitCode() != 0) throw new IllegalStateException(message + "，查看 " + result.log());
    }
}
