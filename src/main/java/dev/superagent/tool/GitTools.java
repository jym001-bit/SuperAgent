package dev.superagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 只读 git 工具。argv 直接交给 ProcessBuilder，绝不经过 shell；
 * 不提供 push/commit/reset 等写操作，且只在任务副本中运行。
 */
public final class GitTools {
    public static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_CAPTURE_BYTES = 512 * 1024;
    private static final int MAX_STATUS_ENTRIES = 500;
    private static final List<String> GLOBAL = List.of("git", "-c", "core.quotepath=false", "--no-pager");
    private final Path root;

    public GitTools(Path workspace) throws Exception {
        if (workspace == null) throw new IllegalArgumentException("缺少任务工作目录");
        Path candidate = workspace.toAbsolutePath();
        if (!Files.isDirectory(candidate)) throw new IllegalArgumentException("工作目录不存在: " + candidate);
        this.root = candidate.toRealPath();
        if (!Files.exists(root.resolve(".git")))
            throw new IllegalArgumentException("不是 git 仓库: " + root + "（缺少 .git，git 工具只在任务副本中可用）");
    }

    public String execute(String name, JsonNode args) throws Exception {
        if (args == null || !args.isObject()) throw new IllegalArgumentException("工具参数必须是对象");
        return switch (name) {
            case "git_status" -> status(args);
            case "git_diff" -> diff(args);
            case "git_log" -> log(args);
            default -> throw new IllegalArgumentException("未知 git 工具: " + name);
        };
    }

    private String status(JsonNode args) throws Exception {
        String path = pathspec(args);
        List<String> argv = new ArrayList<>(GLOBAL);
        argv.addAll(List.of("status", "--porcelain=v1", "-z"));
        if (path != null) {
            argv.add("--");
            argv.add(path);
        }
        Output output = run(argv);
        if (output.exitCode() != 0)
            throw new IllegalStateException("git status 失败（exit=" + output.exitCode() + "）: " + brief(output));
        List<String> entries = porcelain(output.text());
        if (entries.isEmpty()) return path == null ? "工作区干净，无未提交改动" : "路径干净: " + path;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < entries.size() && i < MAX_STATUS_ENTRIES; i++) text.append(entries.get(i)).append('\n');
        if (entries.size() > MAX_STATUS_ENTRIES)
            text.append("[共 ").append(entries.size()).append(" 项改动，仅显示前 ").append(MAX_STATUS_ENTRIES).append(" 项]");
        return FileTools.bounded(text.toString().stripTrailing());
    }

    private String diff(JsonNode args) throws Exception {
        String path = pathspec(args);
        boolean stat = booleanArg(args, "stat");
        List<String> argv = new ArrayList<>(GLOBAL);
        argv.addAll(List.of("diff", "--no-color", "--no-ext-diff"));
        if (stat) argv.add("--stat");
        if (path != null) {
            argv.add("--");
            argv.add(path);
        }
        Output output = run(argv);
        if (output.exitCode() != 0)
            throw new IllegalStateException("git diff 失败（exit=" + output.exitCode() + "）: " + brief(output));
        if (output.text().isBlank()) return stat ? "无差异（--stat 为空）" : "无差异";
        return FileTools.bounded(output.text());
    }

    private String log(JsonNode args) throws Exception {
        int count = Math.max(1, Math.min(50, intArg(args, "count", 10)));
        List<String> argv = new ArrayList<>(GLOBAL);
        argv.addAll(List.of("log", "--oneline", "-n", Integer.toString(count)));
        Output output = run(argv);
        if (output.exitCode() != 0)
            throw new IllegalStateException("git log 失败（exit=" + output.exitCode() + "）: " + brief(output));
        if (output.text().isBlank()) return "没有提交记录";
        return FileTools.bounded(output.text());
    }

    /** -z 输出用 NUL 分隔；重命名条目紧随原路径，不能按行解析。 */
    private static List<String> porcelain(String text) {
        List<String> entries = new ArrayList<>();
        String[] tokens = text.split("\u0000", -1);
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.length() < 4 || token.charAt(2) != ' ') continue;
            String code = token.substring(0, 2), path = token.substring(3), origin = "";
            if ((code.indexOf('R') >= 0 || code.indexOf('C') >= 0) && i + 1 < tokens.length)
                origin = " <- " + tokens[++i];
            entries.add(code + " " + path + origin);
        }
        return entries;
    }

    private Output run(List<String> argv) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(argv).directory(root.toFile());
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_PAGER", "cat");
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("无法启动 git: " + e.getMessage() + "（请确认已安装 git 并在 PATH 中）");
        }
        try {
            process.getOutputStream().close();
            // stdout 是工具结果，stderr 只用于错误提示：git 的换行符警告不能混进 diff。
            ByteArrayOutputStream stdout = new ByteArrayOutputStream(), stderr = new ByteArrayOutputStream();
            Thread out = reader(process.getInputStream(), stdout), err = reader(process.getErrorStream(), stderr);
            out.start();
            err.start();
            boolean done = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!done) Processes.kill(process);
            process.waitFor(2, TimeUnit.SECONDS);
            join(out);
            join(err);
            if (!done) throw new IllegalStateException("git 命令超时（30 秒）: " + String.join(" ", argv));
            return new Output(process.exitValue(), new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                    new String(stderr.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            Processes.kill(process);
        }
    }

    private static Thread reader(InputStream in, ByteArrayOutputStream captured) {
        Thread thread = new Thread(() -> drain(in, captured), "git-output");
        thread.setDaemon(true);
        return thread;
    }

    private static void join(Thread thread) {
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void drain(InputStream in, ByteArrayOutputStream captured) {
        byte[] chunk = new byte[8192];
        try (in) {
            int read;
            while ((read = in.read(chunk)) > 0)
                if (captured.size() < MAX_CAPTURE_BYTES) captured.write(chunk, 0, read);
        } catch (IOException ignored) {
            // 进程被中止时流会关闭，已读内容仍可用。
        }
    }

    /** path 只作为 argv 中的一个 pathspec 传给 git，永远不会被 shell 解释。 */
    private static String pathspec(JsonNode args) {
        JsonNode node = args.get("path");
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw new IllegalArgumentException("参数 path 必须是字符串");
        String value = node.textValue();
        if (value.isBlank()) throw new IllegalArgumentException("参数 path 不能为空");
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*"))
            throw new IllegalArgumentException("path 必须是仓库内的相对路径: " + value);
        for (String part : normalized.split("/")) if (part.equals(".."))
            throw new IllegalArgumentException("path 不能越出仓库: " + value);
        return value;
    }

    private static boolean booleanArg(JsonNode args, String key) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull()) return false;
        if (!node.isBoolean()) throw new IllegalArgumentException("参数 " + key + " 必须是布尔值");
        return node.booleanValue();
    }

    private static int intArg(JsonNode args, String key, int fallback) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull()) return fallback;
        if (node.isIntegralNumber()) return node.intValue();
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.textValue().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("参数 " + key + " 必须是整数");
            }
        }
        throw new IllegalArgumentException("参数 " + key + " 必须是整数");
    }

    private static String brief(Output output) {
        String value = output.error().strip();
        if (value.isBlank()) value = output.text().strip();
        if (value.isBlank()) return "无输出";
        int newline = value.indexOf('\n');
        if (newline > 0) value = value.substring(0, newline);
        return value.length() <= 300 ? value : value.substring(0, 300) + "…";
    }

    private record Output(int exitCode, String text, String error) {}
}
