package dev.superagent;

import dev.superagent.model.Config;
import dev.superagent.storage.Store;
import dev.superagent.task.Task;
import dev.superagent.task.TaskManager;
import dev.superagent.tool.CommandPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 只读命令白名单的行为验收：默认关闭、拒绝一切可串接或可执行外部程序的命令、
 * 开启后免点击但审批记录与审计事件照样落库。
 */
class ApprovalPolicyTest {
    @TempDir Path temporary;

    @BeforeEach void clear() { System.clearProperty(CommandPolicy.ENABLED_PROPERTY); }
    @AfterEach void reset() { System.clearProperty(CommandPolicy.ENABLED_PROPERTY); }

    @Test void classificationRejectsAnythingThatCouldChainOrExec() {
        assertFalse(CommandPolicy.classify("git status", false).autoApprove(), "默认关闭，任何命令都要人工批准");
        assertTrue(CommandPolicy.classify("git status", true).autoApprove());
        assertTrue(CommandPolicy.classify("  git diff --stat  ", true).autoApprove());
        assertTrue(CommandPolicy.classify("git log -n 5", true).autoApprove());
        assertTrue(CommandPolicy.classify("mvn -v", true).autoApprove());
        assertTrue(CommandPolicy.classify("grep -rn TODO src", true).autoApprove());

        assertFalse(CommandPolicy.classify("git status; rm -rf build", true).autoApprove(), "串联命令不能免审批");
        assertFalse(CommandPolicy.classify("git status | sh", true).autoApprove(), "管道不能免审批");
        assertFalse(CommandPolicy.classify("echo x > f", true).autoApprove(), "重定向不能免审批");
        assertFalse(CommandPolicy.classify("git log $(rm -rf /)", true).autoApprove(), "命令替换不能免审批");
        assertFalse(CommandPolicy.classify("git -c alias.p=!sh p", true).autoApprove(), "git -c 可以执行外部程序");
        assertFalse(CommandPolicy.classify("git diff --ext-diff", true).autoApprove(), "--ext-diff 可以执行外部程序");
        assertFalse(CommandPolicy.classify("git push origin main", true).autoApprove(), "写操作不是只读");
        assertFalse(CommandPolicy.classify("mvn test", true).autoApprove(), "跑构建不是只读");
        assertFalse(CommandPolicy.classify("rm -rf build", true).autoApprove());
        assertFalse(CommandPolicy.classify("", true).autoApprove());
        assertTrue(CommandPolicy.allowedPrograms().contains("git"));
    }

    @Test void enabledReadOnlyCommandSkipsTheWaitButIsStillAudited() throws Exception {
        System.setProperty(CommandPolicy.ENABLED_PROPERTY, "true");
        Path state = temporary.resolve("state");
        Config config = TestSupport.config(state, 1, 30, 100_000);
        Path repo = TestSupport.repo(temporary);
        try (Store store = new Store(state);
             TaskManager manager = new TaskManager(config, store,
                     TestSupport.commandModel("git status"), repo, s -> {})) {
            manager.start();
            Task created = manager.create("查看工作区状态");
            TestSupport.status(manager, created.id, Task.Status.READY);

            Task done = store.get(created.id);
            Task.Approval approval = done.approvals.stream()
                    .filter(a -> a.command.equals("git status")).findFirst().orElseThrow();
            assertEquals("USED_AUTO", approval.decision, "审批记录本身要能看出这次不是人工点的");
            assertEquals(1, store.events(created.id, "approval_auto_approved", 0, 10).size(),
                    "自动批准必须写审计事件，否则等于无审计的绕过");
            assertTrue(done.validations.stream().anyMatch(v -> v.contains("git status")),
                    "命令必须真的执行过: " + done.validations);
        }
    }

    @Test void withoutTheSwitchTheSameCommandStillWaitsForAHuman() throws Exception {
        Path state = temporary.resolve("state");
        Config config = TestSupport.config(state, 1, 30, 100_000);
        Path repo = TestSupport.repo(temporary);
        try (Store store = new Store(state);
             TaskManager manager = new TaskManager(config, store,
                     TestSupport.commandModel("git status"), repo, s -> {})) {
            manager.start();
            Task created = manager.create("查看工作区状态");
            TestSupport.status(manager, created.id, Task.Status.WAITING_APPROVAL);

            Task waiting = store.get(created.id);
            assertTrue(waiting.validations.isEmpty(), "没有批准就不该有执行记录");
            assertEquals("PENDING", waiting.approvals.get(0).decision);
            assertEquals(0, store.events(created.id, "approval_auto_approved", 0, 10).size());

            manager.approve(waiting.approvals.get(0).id, true);
            TestSupport.status(manager, created.id, Task.Status.READY);
            assertTrue(store.get(created.id).validations.stream().anyMatch(v -> v.contains("git status")));
        }
    }
}
