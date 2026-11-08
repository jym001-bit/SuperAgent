package dev.superagent.tool;

import dev.superagent.model.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 统一工具面：模型可见定义、风险等级、执行入口。
 * 交互工具（ask_user / execute_command）由调用方负责审批，这里只负责其余工具。
 */
public final class ToolRegistry {
    private static final int MAX_CAPTURE_BYTES = 512 * 1024;
    private static final int TEST_TAIL = 6_000;
    private static final Set<String> SHELLS = Set.of(
            "cmd", "powershell", "pwsh", "sh", "bash", "zsh", "fish", "csh", "ksh", "dash", "cscript", "wscript");
    private static final Map<String, ToolRisk> RISKS = risks();

    private ToolRegistry() {}

    private static Map<String, ToolRisk> risks() {
        Map<String, ToolRisk> risks = new LinkedHashMap<>();
        for (String name : List.of("list_directory", "search_text", "read_file")) risks.put(name, ToolRisk.READ_ONLY);
        for (String name : List.of("git_status", "git_diff", "git_log")) risks.put(name, ToolRisk.READ_ONLY);
        for (String name : List.of("write_file", "edit_file")) risks.put(name, ToolRisk.MUTATING);
        risks.put("run_test", ToolRisk.EXECUTES_COMMAND);
        risks.put("execute_command", ToolRisk.EXECUTES_COMMAND);
        risks.put("ask_user", ToolRisk.INTERACTIVE);
        return Collections.unmodifiableMap(risks);
    }

    /** 模型可见的工具定义：file 工具 + git 工具 + run_test + execute_command + ask_user。 */
    public static ArrayNode specifications() {
        ArrayNode specs = Json.array(), base = FileTools.specifications();
        for (String name : List.of("list_directory", "search_text", "read_file", "write_file", "edit_file"))
            specs.add(copy(base, name));

        ObjectNode status = Json.object();
        status.set("path", stringSchema());
        specs.add(function("git_status", "查看任务副本的 git 工作区状态，可用 path 过滤（只读）", status, List.of()));

        ObjectNode diff = Json.object();
        diff.set("path", stringSchema());
        diff.set("stat", Json.object().put("type", "boolean"));
        specs.add(function("git_diff", "查看 git 差异，stat=true 输出 --stat 摘要，最多 24000 字符（只读）", diff, List.of()));

        ObjectNode log = Json.object();
        log.set("count", Json.object().put("type", "integer").put("minimum", 1).put("maximum", 50));
        specs.add(function("git_log", "查看最近提交，count 默认 10，上限 50（只读）", log, List.of()));

        ObjectNode test = Json.object();
        ObjectNode framework = Json.object().put("type", "string");
        framework.set("enum", Json.array().add("auto").add("maven").add("gradle").add("npm")
                .add("pytest").add("cargo").add("go").add("custom"));
        test.set("framework", framework);
        test.set("args", stringArraySchema());
        test.set("argv", stringArraySchema());
        test.set("timeout_seconds", Json.object().put("type", "integer").put("minimum", 1).put("maximum", 1800));
        specs.add(function("run_test", "运行项目测试并返回结构化结果；自动识别 Maven/Gradle/npm/pytest/Cargo/Go，参数不经 shell",
                test, List.of()));

        for (String name : List.of("execute_command", "ask_user")) specs.add(copy(base, name));
        return specs;
    }

    public static boolean known(String name) {
        return name != null && RISKS.containsKey(name);
    }

    public static ToolRisk risk(String name) {
        ToolRisk value = name == null ? null : RISKS.get(name);
        if (value == null) throw new IllegalArgumentException("未知工具: " + name);
        return value;
    }

    /**
     * 执行非交互工具（file 工具 / git 工具 / run_test）。execute_command 与 ask_user 由调用方处理。
     */
    public static String execute(String name, JsonNode args, Path workspace) throws Exception {
        if (!known(name)) throw new IllegalArgumentException("未知工具: " + name);
        if (name.equals("ask_user") || name.equals("execute_command"))
            throw new IllegalArgumentException("交互工具由调用方处理: " + name);
        if (args == null || !args.isObject()) throw new IllegalArgumentException("工具参数必须是对象");
        if (workspace == null) throw new IllegalArgumentException("缺少任务工作目录");
        return switch (name) {
            case "git_status", "git_diff", "git_log" -> new GitTools(workspace).execute(name, args);
            case "run_test" -> runTest(workspace, args);
            default -> new FileTools(workspace).execute(name, args);
        };
    }

    private static String runTest(Path workspace, JsonNode args) throws Exception {
        if (!Files.isDirectory(workspace)) throw new IllegalArgumentException("任务目录不存在: " + workspace);
        Path root = workspace.toRealPath();
        List<String> argv = testPlan(root, args);
        int timeoutSeconds = Math.max(1, Math.min(1800, intArg(args, "timeout_seconds", 300)));
        Result result = runProcess(root, argv, Duration.ofSeconds(timeoutSeconds));
        int exitCode = result.timedOut() ? -1 : result.exitCode();
        TestOutput.Summary summary = TestOutput.parse(display(argv), result.text(), exitCode, result.timedOut(), TEST_TAIL);
        StringBuilder text = new StringBuilder();
        text.append("测试结果: ").append(summary.digest()).append('\n');
        text.append("命令: ").append(display(argv)).append('\n');
        text.append("exit=").append(exitCode).append(", timedOut=").append(result.timedOut())
                .append(", testsRun=").append(summary.testsRun());
        if (!summary.failures().isEmpty()) {
            text.append('\n').append("失败用例:");
            for (String failure : summary.failures()) text.append("\n  - ").append(failure);
        }
        text.append('\n').append("--- 输出末尾 ---\n").append(summary.tail());
        return FileTools.bounded(text.toString());
    }

    /** 解析框架与 argv；只做 argv 组装，不执行命令（测试可直接验证识别逻辑）。 */
    static List<String> testPlan(Path workspace, JsonNode args) throws Exception {
        if (!Files.isDirectory(workspace)) throw new IllegalArgumentException("任务目录不存在: " + workspace);
        Path root = workspace.toRealPath();
        if (args == null || !args.isObject()) throw new IllegalArgumentException("工具参数必须是对象");
        String framework = textArg(args, "framework", "auto").toLowerCase(Locale.ROOT);
        List<String> extras = stringList(args, "args");
        return switch (framework) {
            case "auto" -> autodetect(root, extras);
            case "maven" -> withArgs(List.of("mvn", "-B", "test"), extras);
            case "gradle" -> withArgs(List.of("gradle", "test"), extras);
            case "npm" -> extras.isEmpty() ? List.of("npm", "test") : withArgs(List.of("npm", "test", "--"), extras);
            case "pytest" -> withArgs(List.of(python(), "-m", "pytest"), extras);
            case "cargo" -> withArgs(List.of("cargo", "test"), extras);
            case "go" -> withArgs(List.of("go", "test", "./..."), extras);
            case "custom" -> custom(args);
            default -> throw new IllegalArgumentException(
                    "未知测试框架: " + framework + "（支持 auto/maven/gradle/npm/pytest/cargo/go/custom）");
        };
    }

    private static List<String> autodetect(Path root, List<String> extras) {
        if (Files.exists(root.resolve("pom.xml"))) return withArgs(List.of("mvn", "-B", "test"), extras);
        if (Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts")))
            return withArgs(List.of("gradle", "test"), extras);
        if (Files.exists(root.resolve("package.json")))
            return extras.isEmpty() ? List.of("npm", "test") : withArgs(List.of("npm", "test", "--"), extras);
        if (Files.exists(root.resolve("Cargo.toml"))) return withArgs(List.of("cargo", "test"), extras);
        if (Files.exists(root.resolve("go.mod"))) return withArgs(List.of("go", "test", "./..."), extras);
        if (Files.exists(root.resolve("pyproject.toml")) || Files.exists(root.resolve("pytest.ini"))
                || Files.exists(root.resolve("setup.cfg")) || Files.exists(root.resolve("tox.ini")))
            return withArgs(List.of(python(), "-m", "pytest"), extras);
        throw new IllegalArgumentException(
                "无法识别测试框架，请显式指定 framework（maven/gradle/npm/pytest/cargo/go/custom）: " + root);
    }

    /** custom 只接受 argv 数组；拒绝 shell，避免绕过 argv 边界重新引入命令拼接。 */
    private static List<String> custom(JsonNode args) {
        List<String> argv = stringList(args, "argv");
        if (argv.isEmpty()) throw new IllegalArgumentException("framework=custom 时必须提供非空 argv 数组");
        String executable = Path.of(argv.get(0)).getFileName().toString().toLowerCase(Locale.ROOT);
        if (executable.endsWith(".exe")) executable = executable.substring(0, executable.length() - 4);
        if (SHELLS.contains(executable))
            throw new IllegalArgumentException("run_test 不允许通过 shell 执行: " + argv.get(0));
        return List.copyOf(argv);
    }

    private static List<String> withArgs(List<String> base, List<String> extras) {
        if (extras.isEmpty()) return base;
        List<String> argv = new ArrayList<>(base);
        argv.addAll(extras);
        return List.copyOf(argv);
    }

    private static String python() {
        return windows() ? "python" : "python3";
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Result runProcess(Path cwd, List<String> argv, Duration timeout) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("无法启动测试命令 " + argv.get(0) + ": " + e.getMessage());
        }
        try {
            process.getOutputStream().close();
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> drain(process.getInputStream(), captured), "run-test-output");
            reader.setDaemon(true);
            reader.start();
            boolean done = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!done) Processes.kill(process);
            process.waitFor(2, TimeUnit.SECONDS);
            try {
                reader.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String text = new String(captured.toByteArray(), StandardCharsets.UTF_8);
            return new Result(done ? process.exitValue() : -1, !done, text);
        } finally {
            Processes.kill(process);
        }
    }

    private static void drain(InputStream in, ByteArrayOutputStream captured) {
        byte[] chunk = new byte[8192];
        try (in) {
            int read;
            while ((read = in.read(chunk)) > 0)
                if (captured.size() < MAX_CAPTURE_BYTES) captured.write(chunk, 0, read);
        } catch (IOException ignored) {
            // 超时被杀后流会关闭，已读内容仍可用。
        }
    }

    private static String textArg(JsonNode args, String key, String fallback) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull()) return fallback;
        if (!node.isTextual() || node.textValue().isBlank())
            throw new IllegalArgumentException("参数 " + key + " 必须是非空字符串");
        return node.textValue().trim();
    }

    private static List<String> stringList(JsonNode args, String key) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalArgumentException("参数 " + key + " 必须是字符串数组");
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) throw new IllegalArgumentException("参数 " + key + " 必须是字符串数组");
            if (item.textValue().indexOf('\u0000') >= 0)
                throw new IllegalArgumentException("参数 " + key + " 不能包含 NUL");
            values.add(item.textValue());
        }
        if (values.size() > 64) throw new IllegalArgumentException("参数 " + key + " 最多 64 项");
        return values;
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

    private static String display(List<String> argv) {
        return String.join(" ", argv);
    }

    private static ObjectNode stringSchema() {
        return Json.object().put("type", "string");
    }

    private static ObjectNode stringArraySchema() {
        ObjectNode array = Json.object().put("type", "array");
        array.set("items", Json.object().put("type", "string"));
        return array;
    }

    private static ObjectNode function(String name, String description, ObjectNode properties, List<String> required) {
        ObjectNode parameters = Json.object().put("type", "object").put("additionalProperties", false);
        parameters.set("properties", properties);
        ArrayNode keys = Json.array();
        for (String key : required) keys.add(key);
        parameters.set("required", keys);
        ObjectNode function = Json.object().put("name", name).put("description", description);
        function.set("parameters", parameters);
        ObjectNode spec = Json.object().put("type", "function");
        spec.set("function", function);
        return spec;
    }

    private static ObjectNode copy(ArrayNode base, String name) {
        for (JsonNode spec : base) if (spec.path("function").path("name").asText().equals(name))
            return spec.deepCopy();
        throw new IllegalStateException("缺少基线工具定义: " + name);
    }

    private record Result(int exitCode, boolean timedOut, String text) {}
}
