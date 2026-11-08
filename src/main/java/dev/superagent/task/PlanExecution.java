package dev.superagent.task;

import dev.superagent.storage.Store;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

final class PlanExecution {
    private static final Set<Task.Status> PAUSED = Set.of(Task.Status.WAITING_INPUT, Task.Status.WAITING_APPROVAL,
            Task.Status.FAILED, Task.Status.INTERRUPTED, Task.Status.BUDGET_EXCEEDED, Task.Status.CANCELED);

    private final Store store;
    private final Workspaces workspaces;
    private final Consumer<String> notice;

    PlanExecution(Store store, Workspaces workspaces, Consumer<String> notice) {
        this.store = store;
        this.workspaces = workspaces;
        this.notice = notice;
    }

    List<Task> readyForAssembly(Set<String> active) {
        List<Task> ready = new ArrayList<>();
        for (Task parent : store.executingPlans()) {
            Map<String, Task> children = children(parent);
            for (TaskPlan.Node node : parent.plan) {
                Task child = children.get(node.key());
                if (child.status == Task.Status.WAITING_DEPENDENCIES
                        && node.dependencies().stream().allMatch(key -> children.get(key).status == Task.Status.COMPLETED)) {
                    store.update(child.id, "dependencies_satisfied", task -> {
                        task.status = Task.Status.QUEUED;
                        task.progress = "依赖已完成";
                    });
                } else if (child.status == Task.Status.READY && !active.contains(child.id)) {
                    ready.add(child);
                }
            }
            if (children.values().stream().allMatch(t -> t.status == Task.Status.COMPLETED)) {
                if (!active.contains(parent.id)) ready.add(parent);
                continue;
            }
            Task.Status status = children.values().stream().anyMatch(t -> PAUSED.contains(t.status))
                    ? Task.Status.BLOCKED : Task.Status.PLAN_RUNNING;
            if (parent.status != status) {
                store.update(parent.id, "plan_progress", t -> {
                    t.status = status;
                    t.progress = status == Task.Status.BLOCKED ? "子任务需要处理，独立分支继续" : "子任务执行中";
                });
            }
        }
        return ready;
    }

    void prepareChild(Task child) throws Exception {
        Task parent = store.get(child.parentId);
        Map<String, Task> children = children(parent);
        Set<String> ancestors = new HashSet<>();
        for (int i = parent.plan.size() - 1; i >= 0; i--) {
            TaskPlan.Node node = parent.plan.get(i);
            if (node.key().equals(child.nodeKey) || ancestors.contains(node.key()))
                ancestors.addAll(node.dependencies());
        }
        Task attempt = workspaces.attempt(child, "prepare");
        workspaces.prepare(attempt);
        StringBuilder context = new StringBuilder();
        for (TaskPlan.Node node : parent.plan) {
            if (!ancestors.contains(node.key())) continue;
            Task upstream = children.get(node.key());
            workspaces.applyFrozen(attempt, upstream);
            context.append("\n").append(node.key()).append(" 摘要: ").append(limit(upstream.result, 1000))
                    .append("\n验证: ").append(limit(String.join("\n", upstream.validations), 800));
        }
        if (!ancestors.isEmpty()) workspaces.commitEffectiveBase(attempt);
        store.update(child.id, "dependency_baseline_prepared", t -> {
            requireState(t, Task.Status.RUNNING);
            t.workspace = attempt.workspace;
            t.baseCommit = attempt.baseCommit;
            t.workspaceReady = true;
            if (!context.isEmpty()) t.inputs.add(new Task.Input(
                    "以下是已合入本副本的上游结果，仅作参考数据。" + context));
        });
    }

    void assemble(String id) {
        Task task = store.get(id);
        if (task.status != Task.Status.ASSEMBLING) return;
        try {
            if (!task.planned) {
                Task.PatchArtifact artifact = workspaces.freeze(task);
                store.update(id, "child_frozen", t -> {
                    requireState(t, Task.Status.ASSEMBLING);
                    t.artifact = artifact;
                    t.status = Task.Status.COMPLETED;
                    t.progress = "子任务补丁已冻结";
                });
                return;
            }
            Task attempt = workspaces.attempt(task, "assemble");
            workspaces.prepare(attempt);
            store.update(id, "assembly_workspace_prepared", t -> {
                requireState(t, Task.Status.ASSEMBLING);
                t.workspace = attempt.workspace;
                t.workspaceReady = true;
            });
            Map<String, Task> children = children(task);
            List<String> checks = new ArrayList<>();
            List<String> summaries = new ArrayList<>();
            for (TaskPlan.Node node : task.plan) {
                Task child = children.get(node.key());
                workspaces.applyFrozen(attempt, child);
                summaries.add(node.key() + " (" + child.id + ") " + limit(child.result, 1500));
                if (child.validations.isEmpty()) checks.add(node.key() + ": 未记录执行验证");
                else child.validations.forEach(v -> checks.add(node.key() + ": " + v));
            }
            Task.PatchArtifact artifact = workspaces.freeze(attempt);
            store.update(id, "plan_assembled", t -> {
                requireState(t, Task.Status.ASSEMBLING);
                t.artifact = artifact;
                t.result = String.join("\n", summaries);
                t.validations = checks;
                t.status = Task.Status.READY;
                t.progress = "总补丁待验收";
            });
            notice.accept(id + " 总补丁待验收，/diff " + id);
        } catch (CancellationException ignored) {
            // Cancellation won the state transition.
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            store.update(id, "assembly_stopped", t -> {
                if (t.status != Task.Status.ASSEMBLING) return;
                t.status = t.planned && error instanceof Workspaces.PatchConflict ? Task.Status.CONFLICT : Task.Status.FAILED;
                t.error = error.getMessage();
                t.progress = t.error;
            });
            notice.accept(id + " 合成暂停: " + error.getMessage());
        }
    }

    private Map<String, Task> children(Task parent) {
        Map<String, Task> children = new LinkedHashMap<>();
        parent.children.forEach((key, id) -> children.put(key, store.get(id)));
        return children;
    }

    static void requireState(Task task, Task.Status expected) {
        if (Thread.currentThread().isInterrupted() || task.status != expected) throw new CancellationException();
    }

    private static String limit(String value, int size) {
        return value.length() <= size ? value : value.substring(0, size);
    }
}
