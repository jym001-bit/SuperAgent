package dev.superagent.eval;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 数据集契约：全部可解析、id 唯一、每个任务至少一条 check，并覆盖全部判据类型。 */
class EvalSuiteTest {
    private static final Path DATASET = Path.of("eval", "tasks");

    private static final List<String> REQUIRED_TASKS = List.of(
            "hello-output", "username-validation", "add-tests-maven", "fix-compile-error",
            "cross-file-greeting", "read-only-analysis", "test-then-fix", "ambiguous-refusal",
            "exact-config-file");

    @Test
    void datasetSatisfiesTheDataContract() throws Exception {
        EvalSuite suite = EvalSuite.load(DATASET);
        assertTrue(suite.size() >= 8, "至少 8 个任务，实际 " + suite.size());

        Set<String> ids = new HashSet<>();
        Set<String> ops = new HashSet<>();
        for (EvalTask task : suite.tasks()) {
            assertTrue(ids.add(task.id()), "任务 id 必须唯一: " + task.id());
            assertFalse(task.name().isBlank(), task.id());
            assertFalse(task.prompt().isBlank(), task.id());
            assertFalse(task.checks().isEmpty(), "任务至少一条 check: " + task.id());
            assertFalse(task.setupFiles().isEmpty(), "任务必须有预置文件: " + task.id());
            assertFalse(task.script().isEmpty(), "离线模式需要脚本: " + task.id());
            for (EvalCheck check : task.checks()) {
                ops.add(check.op());
                if (check.op().equals("command")) assertFalse(check.command().isBlank(), task.id());
                if (Set.of("file_equals", "file_contains", "file_absent").contains(check.op()))
                    assertNotNull(check.path(), task.id());
            }
        }
        assertEquals(EvalCheck.OPS, ops, "数据集应覆盖全部判据类型");
        for (String id : REQUIRED_TASKS) assertTrue(ids.contains(id), "缺少必要任务: " + id);
    }

    @Test
    void onlyFiltersAndRejectsUnknownIds() throws Exception {
        EvalSuite suite = EvalSuite.load(DATASET);
        assertEquals(1, suite.only(List.of("hello-output")).size());
        assertEquals("hello-output", suite.only(List.of("hello-output")).tasks().get(0).id());
        assertThrows(IllegalArgumentException.class, () -> suite.only(List.of("no-such-task")));
    }

    @Test
    void duplicateIdsAreRejected() {
        EvalTask task = EvalTask.parse("""
                { "id": "dup", "name": "n", "prompt": "p",
                  "setup": { "files": { "a.txt": ["a"] } },
                  "checks": [ { "op": "no_changes" } ] }
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EvalSuite.of(List.of(task, task)));
        assertTrue(error.getMessage().contains("dup"));
    }

    @Test
    void invalidChecksAreRejectedBeforeRunning() {
        assertThrows(IllegalArgumentException.class, () -> EvalTask.parse("""
                { "id": "unknown-op", "name": "n", "prompt": "p",
                  "setup": { "files": { "a.txt": ["a"] } },
                  "checks": [ { "op": "magic" } ] }
                """));
        assertThrows(IllegalArgumentException.class, () -> EvalTask.parse("""
                { "id": "no-checks", "name": "n", "prompt": "p",
                  "setup": { "files": { "a.txt": ["a"] } },
                  "checks": [] }
                """));
        assertThrows(IllegalArgumentException.class, () -> EvalTask.parse("""
                { "id": "traversal", "name": "n", "prompt": "p",
                  "setup": { "files": { "../outside.txt": ["a"] } },
                  "checks": [ { "op": "no_changes" } ] }
                """));
        assertThrows(IllegalArgumentException.class, () -> EvalTask.parse("""
                { "id": "Bad Id", "name": "n", "prompt": "p",
                  "setup": { "files": { "a.txt": ["a"] } },
                  "checks": [ { "op": "no_changes" } ] }
                """));
    }
}
