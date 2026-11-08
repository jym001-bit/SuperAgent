package dev.superagent;

import dev.superagent.task.Task;
import dev.superagent.task.Workspaces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 副本准备的恢复语义。真实故障来源：关闭进程会中断 {@code git clone}，留下没有有效仓库的目录，
 * 旧实现在这种目录里跑 git 必然失败，任务直接 FAILED 且无法恢复（CI 在 Linux 上暴露）。
 *
 * <p>两条边界必须同时成立：从未完成准备的副本可以重建；有成果的副本绝不删除。
 */
class WorkspaceRecoveryTest {
    @TempDir Path temporary;

    private Task task(Workspaces workspaces, Path repo, String id) throws Exception {
        Workspaces.Source source = workspaces.inspect(repo);
        Task task = new Task();
        task.id = id;
        task.repo = repo.toString();
        task.baseCommit = source.commit();
        task.workspace = workspaces.path(task.id).toString();
        return task;
    }

    @Test void interruptedCloneIsRebuiltInsteadOfFailingTheTask() throws Exception {
        Path repo = TestSupport.repo(temporary);
        Workspaces workspaces = new Workspaces(temporary.resolve("state"));
        Task task = task(workspaces, repo, "task_partial");

        // 模拟克隆被打断：目录存在，但没有可用的仓库。
        Path workspace = Path.of(task.workspace);
        Files.createDirectories(workspace.resolve(".git/refs"));
        Files.writeString(workspace.resolve(".git/HEAD"), "ref: refs/heads/nonexistent\n");
        Files.writeString(workspace.resolve("half-cloned.txt"), "半成品");

        assertTrue(workspaces.prepare(task), "从未完成准备的副本应当重建");
        assertTrue(Files.exists(workspace.resolve("original.txt")), "重建后应当是源仓库的完整副本");
        assertFalse(Files.exists(workspace.resolve("half-cloned.txt")), "半成品内容不应残留");
        assertEquals("agent/" + task.id,
                TestSupport.gitOutput(workspace, "branch", "--show-current").strip());
        assertEquals(task.baseCommit, TestSupport.gitOutput(workspace, "rev-parse", "HEAD").strip());

        assertFalse(workspaces.prepare(task), "已准备好的副本不应重复重建");
    }

    @Test void validRepositoryWithWrongBaselineIsPreservedAndReported() throws Exception {
        Path repo = TestSupport.repo(temporary);
        Workspaces workspaces = new Workspaces(temporary.resolve("state"));
        Task task = task(workspaces, repo, "task_drifted");
        workspaces.prepare(task);

        // 副本里已经有工作成果，只是基线漂移了：必须保留现场并报错，不能删。
        Path workspace = Path.of(task.workspace);
        Files.writeString(workspace.resolve("work.txt"), "agent 的成果");
        TestSupport.git(workspace, "add", "-A");
        TestSupport.git(workspace, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "commit", "-q", "-m", "drift");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> workspaces.prepare(task));
        assertTrue(error.getMessage().contains("基线不符"), error.getMessage());
        assertTrue(Files.exists(workspace.resolve("work.txt")), "有成果的副本不能被删除");
    }
}
