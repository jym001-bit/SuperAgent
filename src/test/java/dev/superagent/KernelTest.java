package dev.superagent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.superagent.model.Json;
import dev.superagent.storage.Store;
import dev.superagent.task.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 存储内核的行为级验收：迁移、追加语义、快照一致性、调度载荷契约、审计游标。
 * 这些用例在删掉对应实现后会失败，不是形式测试。
 */
class KernelTest {
    @TempDir Path temporary;

    @Test void legacyDatabaseIsMigratedInPlaceAndKeepsHistory() throws Exception {
        Path state = Files.createDirectories(temporary.resolve("state"));
        Task legacy = new Task();
        legacy.id = "task_legacy001";
        legacy.prompt = "历史任务";
        legacy.createdAt = "2026-01-01T00:00:00Z";
        legacy.result = "旧结果";
        legacy.messages.add(Json.message("system", "规则"));
        legacy.messages.add(Json.message("user", "需求"));
        Task.Call call = new Task.Call("call_1", "read_file", "{\"path\":\"a.txt\"}");
        call.phase = Task.Phase.DONE;
        call.result = "内容";
        legacy.tools.add(call);

        // 手工造一个 v1 库：单表 + 整块 JSON，没有 status 列，user_version 为 0。
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + state.resolve("state.db"));
             Statement s = db.createStatement()) {
            s.execute("CREATE TABLE tasks (id TEXT PRIMARY KEY, data TEXT NOT NULL)");
            try (PreparedStatement insert = db.prepareStatement("INSERT INTO tasks VALUES (?,?)")) {
                insert.setString(1, legacy.id);
                insert.setString(2, Json.text(legacy));
                insert.executeUpdate();
            }
        }

        try (Store store = new Store(state)) {
            assertEquals(2, store.schemaVersion(), "打开旧库后应升级到 v2");
            assertTrue(Files.exists(state.resolve("state.db.v1.bak")), "迁移前必须留 v1 备份");
            Task restored = store.get(legacy.id);
            assertEquals("历史任务", restored.prompt);
            assertEquals("旧结果", restored.result);
            assertEquals(2, restored.messages.size(), "对话必须从骨架里拆出来并完整保留");
            assertEquals("需求", restored.messages.get(1).path("content").asText());
            assertEquals(1, restored.tools.size());
            assertEquals("内容", restored.tools.get(0).result);
            assertEquals(Task.Status.QUEUED, store.skeleton(legacy.id).status, "status 列必须回填");
        }
        try (Store store = new Store(state)) { // 迁移幂等：再次打开不重复写、不重复追加
            assertEquals(2, store.get(legacy.id).messages.size());
            assertEquals(1, store.get(legacy.id).tools.size());
        }
    }

    @Test void conversationAppendsStayOrderedAndSkeletonUpdatesNeverDisturbThem() throws Exception {
        Path state = temporary.resolve("state");
        try (Store store = new Store(state)) {
            Task task = new Task();
            task.id = "task_append";
            task.prompt = "追加";
            task.messages.add(Json.message("system", "规则"));
            store.create(task);
            assertEquals(1, store.get(task.id).messages.size());

            for (int i = 0; i < 5; i++) {
                int step = i;
                store.updateConversation(task.id, "append", t -> t.messages.add(Json.message("user", "m" + step)));
            }
            // 纯状态推进不读不写对话表，但也不能让已有对话丢失
            store.update(task.id, "status_only", t -> { t.status = Task.Status.RUNNING; t.progress = "执行中"; });

            List<ObjectNode> messages = store.get(task.id).messages;
            assertEquals(6, messages.size(), "追加条数必须与写入次数一致，不能重复也不能丢");
            for (int i = 1; i <= 5; i++)
                assertEquals("m" + (i - 1), messages.get(i).path("content").asText(), "对话顺序必须保持");
            assertEquals(Task.Status.RUNNING, store.skeleton(task.id).status);
        }
    }

    @Test void failedConversationStepRollsBackTheAppendedMessage() throws Exception {
        Path state = temporary.resolve("state");
        try (Store store = new Store(state)) {
            Task task = new Task();
            task.id = "task_rollback";
            task.prompt = "回滚";
            task.messages.add(Json.message("system", "规则"));
            store.create(task);
            assertThrows(IllegalArgumentException.class, () -> store.updateConversation(task.id, "bad", t -> {
                t.messages.add(Json.message("user", "不该留下"));
                throw new IllegalArgumentException("回滚");
            }));
            assertEquals(1, store.get(task.id).messages.size(), "同一事务内追加的消息必须随回滚消失");
        }
    }

    @Test void skeletonReadsSkipConversationsButKeepToolCalls() throws Exception {
        Path state = temporary.resolve("state");
        try (Store store = new Store(state)) {
            Task task = new Task();
            task.id = "task_skeleton";
            task.prompt = "骨架";
            task.messages.add(Json.message("system", "规则"));
            task.tools.add(new Task.Call("call_1", "read_file", "{}"));
            store.create(task);

            Task skeleton = store.skeleton(task.id);
            assertTrue(skeleton.messages.isEmpty(), "状态推进不该反序列化整个对话");
            assertEquals(1, skeleton.tools.size(), "但未完成的工具调用必须在骨架里，恢复逻辑依赖它");
            assertEquals(1, store.get(task.id).messages.size());
        }
    }

    /** 调度只取骨架：这是「历史越长、每步越慢」被修掉的契约，删掉分表实现此用例即失败。 */
    @Test void schedulingPayloadExcludesConversationsAndStaysFast() throws Exception {
        Path state = temporary.resolve("state");
        try (Store store = new Store(state)) {
            Task heavy = new Task();
            heavy.id = "task_heavy";
            heavy.prompt = "长对话";
            heavy.messages.add(Json.message("system", "规则"));
            store.create(heavy);
            for (int i = 0; i < 200; i++)
                store.updateConversation(heavy.id, "append", t -> t.messages.add(Json.message("user", "第" + "x".repeat(200))));
            for (int i = 0; i < 40; i++) {
                Task small = new Task();
                small.id = String.format("task_small%03d", i);
                small.prompt = "小任务 " + i;
                small.messages.add(Json.message("system", "规则"));
                store.create(small);
            }

            List<Task> queued = store.queued();
            assertEquals(41, queued.size());
            assertTrue(queued.stream().allMatch(t -> t.messages.isEmpty()), "调度载荷必须只有骨架");
            assertEquals(Map.of("QUEUED", 41L), store.counts());

            long started = System.nanoTime();
            assertEquals(41, store.list().size());
            assertEquals(41, store.recent().size());
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            assertTrue(elapsed.toSeconds() < 10, "列出任务不应反序列化 200 条对话，实际耗时 " + elapsed.toMillis() + "ms");
            assertEquals(201, store.get("task_heavy").messages.size());
        }
    }

    @Test void readersNeverObserveTornSnapshotsWhileConversationGrows() throws Exception {
        Path state = temporary.resolve("state");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try (Store store = new Store(state)) {
            Task task = new Task();
            task.id = "task_torn";
            task.prompt = "并发读";
            task.progress = "0";
            task.messages.add(Json.message("system", "规则"));
            store.create(task);

            AtomicBoolean stop = new AtomicBoolean();
            List<Future<Integer>> readers = new ArrayList<>();
            for (int i = 0; i < 3; i++) readers.add(pool.submit(() -> {
                int checks = 0;
                while (!stop.get()) {
                    Task read = store.get("task_torn");
                    int progress = Integer.parseInt(read.progress);
                    // 骨架与对话必须来自同一快照：1 条 system + progress 条追加消息
                    if (read.messages.size() != 1 + progress)
                        throw new AssertionError("读到撕裂快照: progress=" + progress + " messages=" + read.messages.size());
                    assertTrue(store.skeleton("task_torn").tools.isEmpty());
                    checks++;
                }
                return checks;
            }));
            for (int i = 1; i <= 120; i++) {
                int step = i;
                store.updateConversation("task_torn", "append", t -> {
                    t.messages.add(Json.message("user", "m" + step));
                    t.progress = String.valueOf(step);
                });
            }
            stop.set(true);
            for (Future<Integer> reader : readers)
                assertDoesNotThrow(() -> reader.get(30, TimeUnit.SECONDS), "并发读期间不得出现撕裂快照或异常");
            assertEquals(121, store.get("task_torn").messages.size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void auditEventsAreOrderedAndFilterable() throws Exception {
        Path state = temporary.resolve("state");
        try (Store store = new Store(state)) {
            Task task = new Task();
            task.id = "task_audit";
            task.prompt = "审计";
            store.create(task);
            store.update(task.id, "step_started", t -> t.progress = "开始");
            store.update(task.id, "step_finished", t -> t.progress = "结束");

            List<Store.Event> events = store.events(task.id, null, 0, 100);
            assertEquals(List.of("created", "step_started", "step_finished"),
                    events.stream().map(Store.Event::kind).toList(), "审计必须按写入顺序返回");
            assertTrue(events.get(0).seq() < events.get(2).seq());
            assertEquals(1, store.events(task.id, "step_started", 0, 100).size());
            assertEquals(0, store.events(task.id, "step_started", events.get(1).seq(), 100).size(), "游标之后的同类事件应为空");
            assertEquals(1, store.events(task.id, null, events.get(1).seq(), 100).size(), "游标之后应只剩最后一条");
            assertTrue(store.eventCounts().getOrDefault("created", 0L) >= 1);
        }
    }

    @Test void tokenAndCallTotalsAggregateAcrossTasks() throws Exception {
        Path state = temporary.resolve("state");
        try (Store store = new Store(state)) {
            for (int i = 0; i < 3; i++) {
                Task task = new Task();
                task.id = "task_cost" + i;
                task.prompt = "成本";
                task.tokens = 100L * (i + 1);
                task.calls = i + 1;
                store.create(task);
            }
            assertEquals(600L, store.totalTokens());
            assertEquals(6L, store.totalModelCalls());
        }
    }
}
