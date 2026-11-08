package dev.superagent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.superagent.model.Config;
import dev.superagent.model.Json;
import dev.superagent.model.ModelClient;
import dev.superagent.storage.Store;
import dev.superagent.task.ScopeGate;
import dev.superagent.task.Task;
import dev.superagent.task.TaskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 改动范围闸门的行为验收：
 * 数据集里只有歧义需求会被挡住；被挡住的写入不落盘且留审计；用户补充明确目标后闸门自动打开；
 * 显式探索模式放行。
 */
class ScopeGateTest {
    @TempDir Path temporary;

    @BeforeEach void strict() { System.clearProperty(ScopeGate.EXPLORATORY_PROPERTY); }
    @AfterEach void reset() { System.clearProperty(ScopeGate.EXPLORATORY_PROPERTY); }

    /**
     * 与评测数据集绑定的契约：闸门不能误伤已有任务（否则等于把功能改坏），也不能放过歧义需求
     * （否则等于没做）。新增任务时如果提示词没有锚点，这个用例会先失败。
     */
    @Test void onlyTheAmbiguousTaskIsBlockedAcrossTheEvaluationDataset() throws Exception {
        Path tasks = Path.of("eval", "tasks");
        assertTrue(Files.isDirectory(tasks), "缺少评测数据集目录: " + tasks.toAbsolutePath());
        List<String> blocked = new ArrayList<>();
        try (var files = Files.list(tasks)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                JsonNode node = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
                if (!ScopeGate.check(node.path("prompt").asText()).allowed())
                    blocked.add(node.path("id").asText());
            }
        }
        assertFalse(blocked.isEmpty(), "数据集必须包含一个歧义需求，否则这条契约失去意义");
        assertEquals(List.of("ambiguous-refusal"), blocked,
                "被闸门挡住的应该只有歧义需求，实际: " + blocked);
        assertFalse(ScopeGate.check("优化一下这个项目的性能，让它更快。").allowed());
        assertFalse(ScopeGate.check("   ").allowed());
        assertTrue(ScopeGate.check("把 src/main/java/demo/Hello.java 的输出改掉").allowed());
        assertTrue(ScopeGate.check("StringTools.normalize 现在只返回原值").allowed());
        assertTrue(ScopeGate.check("让运行结果正好是 \"Hello, world!\"。").allowed());
        assertTrue(ScopeGate.check("先运行 mvn -o -B -q test 观察失败").allowed());
    }

    @Test void vagueRequestCannotWriteAndTheGateOpensAfterTheUserNamesATarget() throws Exception {
        Path state = temporary.resolve("state");
        Config config = TestSupport.config(state, 1, 20, 100_000);
        Path repo = TestSupport.repo(temporary);
        try (Store store = new Store(state);
             TaskManager manager = new TaskManager(config, store, scriptedWriter(), repo, s -> {})) {
            manager.start();
            Task created = manager.create("优化一下这个项目的性能，让它更快。");

            // 第一次写入必须被拒，并进入等待用户回答
            TestSupport.status(manager, created.id, Task.Status.WAITING_INPUT);
            Task blocked = store.get(created.id);
            assertFalse(Files.exists(Path.of(blocked.workspace, "note.txt")), "范围未确认时不允许写入");
            assertTrue(blocked.validations.stream().anyMatch(v -> v.contains("范围闸门")),
                    "拦截必须留下可审计的记录: " + blocked.validations);
            assertTrue(blocked.question.contains("改哪个文件"), "模型应当转为询问范围: " + blocked.question);

            // 用户补充了明确目标后，闸门打开，同一条写入被放行
            manager.follow(created.id, "改 note.txt 这个文件，内容写 x");
            TestSupport.await(() -> Files.exists(Path.of(store.get(created.id).workspace, "note.txt")));
            assertEquals("x", Files.readString(Path.of(store.get(created.id).workspace, "note.txt")).strip());
            TestSupport.status(manager, created.id, Task.Status.READY);
        }
    }

    @Test void exploratoryModeAllowsTheSameWriteWithoutAsking() throws Exception {
        System.setProperty(ScopeGate.EXPLORATORY_PROPERTY, "true");
        Path state = temporary.resolve("state");
        Config config = TestSupport.config(state, 1, 20, 100_000);
        Path repo = TestSupport.repo(temporary);
        ModelClient writer = (messages, tools) -> messages.stream()
                .noneMatch(m -> m.path("role").asText().equals("tool"))
                ? TestSupport.call("write_file", Json.object().put("path", "note.txt").put("content", "x"))
                : TestSupport.answer("done");
        try (Store store = new Store(state);
             TaskManager manager = new TaskManager(config, store, writer, repo, s -> {})) {
            manager.start();
            Task created = manager.create("优化一下这个项目的性能，让它更快。");
            TestSupport.status(manager, created.id, Task.Status.READY);
            assertTrue(Files.exists(Path.of(store.get(created.id).workspace, "note.txt")),
                    "探索模式下不应该拦截");
        }
    }

    /** 模拟真实模型的合理行为：写入被拒 → 询问范围 → 拿到回答后重新写入。 */
    private static ModelClient scriptedWriter() {
        return (messages, tools) -> {
            String lastTool = "";
            for (var message : messages)
                if ("tool".equals(message.path("role").asText())) lastTool = message.path("content").asText();
            if (lastTool.startsWith("用户回复")) return TestSupport.call("write_file",
                    Json.object().put("path", "note.txt").put("content", "x"));
            if (lastTool.startsWith("写入被拒绝")) return TestSupport.call("ask_user",
                    Json.object().put("question", "要改哪个文件？请给出文件名和验收方式。"));
            if (lastTool.startsWith("已写入")) return TestSupport.answer("done");
            if (messages.size() <= 2) return TestSupport.call("write_file",
                    Json.object().put("path", "note.txt").put("content", "x"));
            return TestSupport.answer("done");
        };
    }
}
