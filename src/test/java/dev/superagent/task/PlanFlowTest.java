package dev.superagent.task;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.superagent.model.*;
import dev.superagent.storage.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.superagent.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanFlowTest {
    @TempDir Path temp;

    @Test void approvalStartsParallelRootsAndDownstreamGetsBothPatchesExactlyOnce() throws Exception {
        Path repo = repo(temp);
        Config config = config(temp.resolve("state"));
        CountDownLatch rootsStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            if (tools == null) return proposal(node("c", "Combine", "a", "b"), node("a", "First"), node("b", "Second"));
            executions.incrementAndGet();
            String goal = workerGoal(messages);
            var results = toolResults(messages);
            if (goal.equals("Combine")) {
                return switch (results.size()) {
                    case 0 -> call("read_file", Json.object().put("path", "a.txt"));
                    case 1 -> {
                        assertTrue(results.get(0).contains("from A"));
                        yield call("read_file", Json.object().put("path", "b.txt"));
                    }
                    case 2 -> {
                        assertTrue(results.get(1).contains("from B"));
                        yield write("a.txt", "from A\nfrom C\n");
                    }
                    default -> answer("combined");
                };
            }
            if (!results.isEmpty()) return answer("root done");
            rootsStarted.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return goal.equals("First") ? write("a.txt", "from A\n") : write("b.txt", "from B\n");
        };
        try (Store store = new Store(config.stateDir());
             TaskManager manager = new TaskManager(config, store, model, repo, s -> {})) {
            manager.start();
            Task parent = manager.plan("add two files and combine");
            assertEquals(Task.Status.PLAN_READY, parent.status, parent.error);
            assertEquals(List.of("a", "b", "c"), parent.plan.stream().map(TaskPlan.Node::key).toList());
            assertEquals(1, manager.list().size());
            assertEquals(0, executions.get());
            Task approved = manager.startPlan(parent.id);
            assertEquals(approved.children, manager.startPlan(parent.id).children);
            try {
                assertTrue(rootsStarted.await(10, TimeUnit.SECONDS), "Both independent model calls must be live");
                assertEquals(Task.Status.WAITING_DEPENDENCIES, manager.get(approved.children.get("c")).status);
            } finally { release.countDown(); }
            status(manager, parent.id, Task.Status.READY);
            Task child = manager.get(approved.children.get("c"));
            assertNotEquals(parent.baseCommit, child.baseCommit);
            String childPatch = Files.readString(manager.diff(child.id).patch());
            assertFalse(childPatch.contains("b.txt"));
            assertTrue(childPatch.contains("+from C"));
            Path patch = manager.diff(parent.id).patch();
            git(repo, "apply", "--check", patch.toString());
            assertFalse(Files.exists(repo.resolve("a.txt")));
            Task assembled = manager.get(parent.id);
            assertEquals("from A\nfrom C\n", Files.readString(Path.of(assembled.workspace, "a.txt")));
            assertEquals("from B\n", Files.readString(Path.of(assembled.workspace, "b.txt")));
            assertEquals(Workspaces.patchHash(Files.readAllBytes(patch)), assembled.artifact.sha256());
            manager.accept(parent.id);
            assertEquals(Task.Status.COMPLETED, manager.get(parent.id).status);
        }
    }

    @Test void conflictingSiblingPatchesPreserveEvidenceAndManualResolutionCanBeExported() throws Exception {
        Path repo = repo(temp);
        Config config = config(temp.resolve("state"));
        ModelClient model = (messages, tools) -> {
            if (tools == null) return proposal(node("a", "First"), node("b", "Second"));
            return toolResults(messages).isEmpty()
                    ? write("original.txt", workerGoal(messages) + "\n") : answer("changed");
        };
        try (Store store = new Store(config.stateDir());
             TaskManager manager = new TaskManager(config, store, model, repo, s -> {})) {
            manager.start();
            String id = manager.plan("conflicting edits").id;
            Task approved = manager.startPlan(id);
            status(manager, id, Task.Status.CONFLICT);
            Task conflict = manager.get(id);
            assertTrue(conflict.error.contains("子任务 b"));
            assertTrue(conflict.error.contains("original.txt"));
            assertThrows(IllegalArgumentException.class, () -> manager.diff(id));
            for (String child : approved.children.values()) assertTrue(Files.exists(manager.diff(child).patch()));
            assertEquals("original", Files.readString(repo.resolve("original.txt")).strip());
            Files.writeString(Path.of(conflict.workspace, "original.txt"), "merged manually\n");
            manager.resolve(id);
            assertTrue(Files.readString(manager.diff(id).patch()).contains("+merged manually"));
        }
    }

    @Test void pausedNodeDoesNotBlockIndependentWorkAndRestartDoesNotRerunFrozenChild() throws Exception {
        Path repo = repo(temp);
        Config config = config(temp.resolve("state"));
        AtomicInteger firstCalls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            if (tools == null) return proposal(node("a", "First"), node("b", "Question"), node("c", "After question", "b"));
            String goal = workerGoal(messages);
            if (goal.equals("First")) firstCalls.incrementAndGet();
            if (goal.equals("Question") && toolResults(messages).isEmpty())
                return call("ask_user", Json.object().put("question", "Need confirmation"));
            return answer("done");
        };
        String id;
        Map<String, String> children;
        try (Store store = new Store(config.stateDir());
             TaskManager manager = new TaskManager(config, store, model, repo, s -> {})) {
            manager.start();
            id = manager.plan("wait without blocking").id;
            children = manager.startPlan(id).children;
            status(manager, children.get("a"), Task.Status.COMPLETED);
            status(manager, children.get("b"), Task.Status.WAITING_INPUT);
            status(manager, id, Task.Status.BLOCKED);
            assertEquals(Task.Status.WAITING_DEPENDENCIES, manager.get(children.get("c")).status);
        }
        try (Store store = new Store(config.stateDir());
             TaskManager manager = new TaskManager(config, store, model, repo, s -> {})) {
            manager.start();
            assertEquals(children, manager.startPlan(id).children);
            manager.follow(children.get("b"), "yes");
            status(manager, id, Task.Status.READY);
            assertEquals(1, firstCalls.get());
            assertEquals(Task.Status.COMPLETED, manager.get(children.get("c")).status);
        }
    }

    @Test void canceledPlanCancelsRunningAndWaitingChildrenAcrossRestart() throws Exception {
        Path repo = repo(temp);
        Config config = config(temp.resolve("state"));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ModelClient model = (messages, tools) -> {
            if (tools == null) return proposal(node("a", "First"), node("b", "Second", "a"));
            started.countDown();
            try { new CountDownLatch(1).await(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { interrupted.countDown(); throw e; }
            return answer("late result");
        };
        String id;
        Map<String, String> children;
        try (Store store = new Store(config.stateDir());
             TaskManager manager = new TaskManager(config, store, model, repo, s -> {})) {
            manager.start();
            id = manager.plan("cancel").id;
            children = manager.startPlan(id).children;
            assertTrue(started.await(10, TimeUnit.SECONDS));
            manager.cancel(id);
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        }
        try (Store store = new Store(config.stateDir());
             TaskManager manager = new TaskManager(config, store, model, repo, s -> {})) {
            assertEquals(Task.Status.CANCELED, manager.get(id).status);
            for (String child : children.values()) {
                assertEquals(Task.Status.CANCELED, manager.get(child).status);
                assertThrows(IllegalArgumentException.class, () -> manager.resume(child));
            }
        }
    }

    @Test void invalidPlansNeverCreateChildren() throws Exception {
        Path repo = repo(temp);
        Config config = config(temp.resolve("state"));
        List<ModelClient.Reply> invalid = new ArrayList<>(List.of(
                proposal(node("a", "First"), node("a", "Duplicate")),
                proposal(node("a", "Missing", "z")),
                proposal(node("a", "First", "b"), node("b", "Cycle", "a"))));
        ModelClient model = (messages, tools) -> invalid.remove(0);
        try (Store store = new Store(config.stateDir());
             TaskManager manager = new TaskManager(config, store, model, repo, s -> {})) {
            for (int i = 0; i < 3; i++) {
                Task parent = manager.plan("invalid");
                assertEquals(Task.Status.FAILED, parent.status);
                assertTrue(parent.children.isEmpty());
                assertThrows(IllegalArgumentException.class, () -> manager.startPlan(parent.id));
            }
            assertEquals(3, manager.list().size());
        }
    }

    private static TaskPlan.Node node(String key, String goal, String... dependencies) {
        return new TaskPlan.Node(key, goal, "Verify changes", List.of(dependencies));
    }

    private static ModelClient.Reply proposal(TaskPlan.Node... nodes) {
        return answer(Json.text(Map.of("nodes", nodes)));
    }

    private static String workerGoal(List<ObjectNode> messages) {
        return messages.stream().filter(m -> m.path("role").asText().equals("user"))
                .map(m -> m.path("content").asText()).filter(text -> text.contains("你的子任务:\n"))
                .findFirst().orElseThrow().split("你的子任务:\n", 2)[1].lines().findFirst().orElseThrow();
    }

    private static List<String> toolResults(List<ObjectNode> messages) {
        return messages.stream().filter(m -> m.path("role").asText().equals("tool"))
                .map(m -> m.path("content").asText()).toList();
    }

    private static ModelClient.Reply write(String path, String content) {
        return call("write_file", Json.object().put("path", path).put("content", content));
    }
}
