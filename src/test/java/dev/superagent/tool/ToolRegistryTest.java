package dev.superagent.tool;

import dev.superagent.model.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 注册表契约一致性、统一执行入口和 run_test 的真实进程行为。 */
class ToolRegistryTest {
    @TempDir Path temporary;

    @Test void specificationsRiskAndKnownAreOneToOne() {
        Set<String> expected = Set.of("list_directory", "search_text", "read_file", "write_file", "edit_file",
                "git_status", "git_diff", "git_log", "run_test", "execute_command", "ask_user");
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode spec : ToolRegistry.specifications()) {
            assertEquals("function", spec.path("type").asText());
            String name = spec.path("function").path("name").asText();
            assertTrue(names.add(name), "重复的工具定义: " + name);
            assertTrue(ToolRegistry.known(name), "定义未注册: " + name);
            assertNotNull(ToolRegistry.risk(name), "未设置风险等级: " + name);
            JsonNode parameters = spec.path("function").path("parameters");
            assertEquals("object", parameters.path("type").asText());
            for (JsonNode required : parameters.path("required"))
                assertTrue(parameters.path("properties").has(required.asText()),
                        name + " 的 required 项未在 properties 中声明: " + required.asText());
        }
        assertEquals(expected, names);
        assertEquals(ToolRisk.READ_ONLY, ToolRegistry.risk("read_file"));
        assertEquals(ToolRisk.READ_ONLY, ToolRegistry.risk("git_diff"));
        assertEquals(ToolRisk.MUTATING, ToolRegistry.risk("edit_file"));
        assertEquals(ToolRisk.EXECUTES_COMMAND, ToolRegistry.risk("run_test"));
        assertEquals(ToolRisk.EXECUTES_COMMAND, ToolRegistry.risk("execute_command"));
        assertEquals(ToolRisk.INTERACTIVE, ToolRegistry.risk("ask_user"));
        assertFalse(ToolRegistry.known("git_push"));
        assertFalse(ToolRegistry.known(null));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> ToolRegistry.risk("git_push"));
        assertTrue(unknown.getMessage().contains("未知工具"), unknown.getMessage());
    }

    @Test void interactiveToolsAreRejectedByExecute() {
        IllegalArgumentException command = assertThrows(IllegalArgumentException.class,
                () -> ToolRegistry.execute("execute_command", Json.object().put("command", "echo hi"), temporary));
        assertEquals("交互工具由调用方处理: execute_command", command.getMessage());
        IllegalArgumentException ask = assertThrows(IllegalArgumentException.class,
                () -> ToolRegistry.execute("ask_user", Json.object().put("question", "?"), temporary));
        assertEquals("交互工具由调用方处理: ask_user", ask.getMessage());
        assertEquals("交互工具由调用方处理: ask_user",
                assertThrows(IllegalArgumentException.class,
                        () -> ToolRegistry.execute("ask_user", null, temporary)).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> ToolRegistry.execute("git_push", Json.object(), temporary));
        assertThrows(IllegalArgumentException.class,
                () -> ToolRegistry.execute("read_file", Json.array(), temporary));
    }

    @Test void fileToolsRunThroughRegistry() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        String written = ToolRegistry.execute("write_file",
                Json.object().put("path", "note.txt").put("content", "hello\n"), workspace);
        assertTrue(written.contains("note.txt"), written);
        assertEquals("hello\n", Files.readString(workspace.resolve("note.txt")));
        assertEquals("hello\n", ToolRegistry.execute("read_file", Json.object().put("path", "note.txt"), workspace));
        assertTrue(ToolRegistry.execute("search_text",
                Json.object().put("path", ".").put("query", "hello"), workspace).contains("note.txt:1:hello"));
        assertThrows(IllegalArgumentException.class,
                () -> ToolRegistry.execute("read_file", Json.object().put("path", "../escape.txt"), workspace));
    }

    @Test void testPlanAutodetectsProjectTypeWithoutRunningACommand() throws Exception {
        assertPlan("pom.xml", "mvn", "-B", "test");
        assertPlan("package.json", "npm", "test");
        assertPlan("Cargo.toml", "cargo", "test");
        assertPlan("go.mod", "go", "test", "./...");

        Path npm = Files.createDirectories(temporary.resolve("npm-extra"));
        Files.writeString(npm.resolve("package.json"), "{}");
        assertEquals(List.of("npm", "test", "--", "-t", "用户名"),
                ToolRegistry.testPlan(npm, Json.object().set("args", Json.array().add("-t").add("用户名"))));

        Path custom = Files.createDirectories(temporary.resolve("custom"));
        assertEquals(List.of("java", "-version"), ToolRegistry.testPlan(custom,
                Json.object().put("framework", "custom").set("argv", Json.array().add("java").add("-version"))));

        Path empty = Files.createDirectories(temporary.resolve("empty"));
        IllegalArgumentException undetected = assertThrows(IllegalArgumentException.class,
                () -> ToolRegistry.testPlan(empty, Json.object()));
        assertTrue(undetected.getMessage().contains("无法识别测试框架"), undetected.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> ToolRegistry.testPlan(custom, Json.object().put("framework", "custom")));
        assertThrows(IllegalArgumentException.class,
                () -> ToolRegistry.testPlan(custom, Json.object().put("framework", "bazel")));
    }

    @Test void runTestExecutesRealProcessAndParsesStructuredOutput() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("run"));
        String ok = ToolRegistry.execute("run_test", Json.object().put("framework", "custom")
                .set("argv", Json.array().add(javaExecutable()).add("-version")), workspace);
        assertTrue(ok.contains("exit=0"), ok);
        assertTrue(ok.contains("timedOut=false"), ok);
        assertTrue(ok.contains("--- 输出末尾 ---"), ok);

        String failed = ToolRegistry.execute("run_test", Json.object().put("framework", "custom")
                .set("argv", Json.array().add(javaExecutable()).add("dev.superagent.NoSuchMain")), workspace);
        assertTrue(failed.contains("exit=1"), failed);

        IllegalArgumentException shell = assertThrows(IllegalArgumentException.class,
                () -> ToolRegistry.execute("run_test", Json.object().put("framework", "custom")
                        .set("argv", Json.array().add(windows() ? "cmd.exe" : "sh").add("-c").add("echo hi")), workspace));
        assertTrue(shell.getMessage().contains("shell"), shell.getMessage());
    }

    private void assertPlan(String marker, String... expected) throws Exception {
        Path directory = Files.createDirectories(temporary.resolve("project-" + marker.replace('.', '_')));
        Files.writeString(directory.resolve(marker), "");
        assertEquals(List.of(expected), ToolRegistry.testPlan(directory, Json.object()));
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString();
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }
}
