package dev.superagent;

import dev.superagent.model.Json;
import dev.superagent.storage.Store;
import dev.superagent.task.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 状态库自检的行为验收：健康库全绿，被破坏的库必须准确指出哪一项坏了。
 */
class VerificationTest {
    @TempDir Path temporary;

    private static Task task(String id) {
        Task task = new Task();
        task.id = id;
        task.prompt = "自检";
        task.messages.add(Json.message("system", "规则"));
        return task;
    }

    @Test void healthyStorePassesEveryCheck() throws Exception {
        Path state = temporary.resolve("state");
        try (Store store = new Store(state)) {
            store.create(task("task_ok"));
            store.updateConversation("task_ok", "append", t -> t.messages.add(Json.message("user", "需求")));

            Store.Verification verification = store.verify();
            assertTrue(verification.healthy(), verification.checks().toString());
            Map<String, Boolean> byName = verification.checks().stream()
                    .collect(Collectors.toMap(Store.Check::name, Store.Check::ok));
            for (String required : new String[]{"sqlite_integrity", "schema_version", "foreign_keys",
                    "orphan_messages", "orphan_calls", "status_column", "message_sequence",
                    "call_sequence", "dangling_children", "workspace_present"})
                assertTrue(byName.containsKey(required), "缺少自检项: " + required);
            assertTrue(byName.values().stream().allMatch(Boolean::booleanValue));
        }
    }

    @Test void corruptionIsLocatedToTheExactCheck() throws Exception {
        Path state = temporary.resolve("state");
        try (Store store = new Store(state)) {
            store.create(task("task_bad"));
            store.updateConversation("task_bad", "append", t -> t.messages.add(Json.message("user", "需求")));

            // 用第二个连接直接破坏数据：孤儿行、状态列不一致、对话序号缺口。
            try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + state.resolve("state.db"));
                 Statement s = db.createStatement()) {
                s.execute("INSERT INTO task_messages(task_id,seq,data) VALUES ('task_ghost',0,'{\"role\":\"user\"}')");
                s.execute("UPDATE tasks SET status='READY' WHERE id='task_bad'");
                s.execute("INSERT INTO task_messages(task_id,seq,data) VALUES ('task_bad',5,'{\"role\":\"user\",\"content\":\"跳号\"}')");
            }

            Store.Verification verification = store.verify();
            assertFalse(verification.healthy(), "被破坏的库必须判为不健康");
            Map<String, Store.Check> byName = verification.checks().stream()
                    .collect(Collectors.toMap(Store.Check::name, check -> check));

            assertFalse(byName.get("orphan_messages").ok(), "孤儿对话记录必须被指出");
            assertTrue(byName.get("orphan_messages").detail().contains("1"));
            assertFalse(byName.get("status_column").ok(), "状态列与数据不一致必须被指出");
            assertFalse(byName.get("message_sequence").ok(), "序号缺口必须被指出");
            assertTrue(byName.get("sqlite_integrity").ok(), "SQLite 本身仍然完整，不能误报");
        }
    }

    @Test void danglingChildAndMissingWorkspaceAreReported() throws Exception {
        Path state = temporary.resolve("state");
        try (Store store = new Store(state)) {
            Task parent = task("task_parent");
            parent.planned = true;
            parent.children.put("A", "task_missing_child");
            store.create(parent);
            Task child = task("task_child");
            child.parentId = parent.id;
            child.workspaceReady = true;
            child.workspace = temporary.resolve("gone").toString();
            store.create(child);

            Store.Verification verification = store.verify();
            Map<String, Store.Check> byName = verification.checks().stream()
                    .collect(Collectors.toMap(Store.Check::name, check -> check));
            assertFalse(byName.get("dangling_children").ok(), "指向不存在任务的子任务引用必须被指出");
            assertFalse(byName.get("workspace_present").ok(), "副本目录缺失的已准备任务必须被指出");
            assertFalse(verification.healthy());
        }
    }
}
