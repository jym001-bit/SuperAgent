package dev.superagent;

import dev.superagent.model.Json;
import dev.superagent.storage.Store;
import dev.superagent.task.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 生命周期治理契约：只归档已结束任务、证据先导出后删除、审计随任务一起移出、父任务存活时子任务不得单独归档。
 */
class RetentionTest {
    @TempDir Path temporary;

    private static Task task(String id, String prompt, Task.Status status) {
        Task task = new Task();
        task.id = id;
        task.prompt = prompt;
        task.status = status;
        task.messages.add(Json.message("system", "规则"));
        return task;
    }

    @Test void archivesOnlyTerminalTasksAndWritesEvidenceFirst() throws Exception {
        Path state = temporary.resolve("state");
        Path archive = state.resolve("archive");
        try (Store store = new Store(state)) {
            store.create(task("task_done", "已完成", Task.Status.QUEUED));
            store.update("task_done", "finish", t -> t.status = Task.Status.COMPLETED);
            store.create(task("task_canceled", "已取消", Task.Status.QUEUED));
            store.update("task_canceled", "cancel", t -> t.status = Task.Status.CANCELED);
            store.create(task("task_running", "运行中", Task.Status.QUEUED));
            store.update("task_running", "run", t -> t.status = Task.Status.RUNNING);
            store.create(task("task_ready", "待验收", Task.Status.QUEUED));
            store.update("task_ready", "ready", t -> t.status = Task.Status.READY);

            Store.ArchiveSummary summary = store.archiveBefore(Instant.now().plusSeconds(60), archive);
            assertEquals(2, summary.tasks(), "只归档 COMPLETED / CANCELED");
            assertNotNull(summary.file());
            assertTrue(Files.exists(summary.file()));

            assertThrows(IllegalArgumentException.class, () -> store.get("task_done"));
            assertThrows(IllegalArgumentException.class, () -> store.get("task_canceled"));
            assertEquals(Task.Status.RUNNING, store.get("task_running").status);
            assertEquals(Task.Status.READY, store.get("task_ready").status);
            assertTrue(store.events("task_done", null, 0, 100).isEmpty(), "归档必须连同审计记录一起移出主库");

            List<String> lines = Files.readAllLines(summary.file());
            assertEquals(2, lines.size(), "每个归档任务一行，含完整任务与事件");
            assertTrue(lines.stream().anyMatch(line -> line.contains("task_done")), "导出文件必须能证明归档内容");
            assertTrue(lines.stream().anyMatch(line -> line.contains("\"messages\"")), "导出必须包含对话原文，而不是骨架");

            assertEquals(0, store.archiveBefore(Instant.now().plusSeconds(60), archive).tasks(), "重复归档不应重复处理");
        }
    }

    @Test void childOfLiveParentIsNeverArchivedAlone() throws Exception {
        Path state = temporary.resolve("state");
        try (Store store = new Store(state)) {
            Task parent = task("task_parent", "父任务", Task.Status.QUEUED);
            parent.planned = true;
            store.create(parent);
            store.update(parent.id, "plan", t -> t.status = Task.Status.PLAN_RUNNING);
            Task child = task("task_child", "子任务", Task.Status.QUEUED);
            child.parentId = parent.id;
            store.create(child);
            store.update(child.id, "done", t -> t.status = Task.Status.COMPLETED);

            Store.ArchiveSummary summary = store.archiveBefore(Instant.now().plusSeconds(60), state.resolve("archive"));
            assertEquals(0, summary.tasks(), "父任务仍在运行，单独归档子任务会让父任务的 children 指向不存在的任务");
            assertEquals(Task.Status.COMPLETED, store.get("task_child").status);
        }
    }
}
