package dev.superagent;

import dev.superagent.model.*;
import dev.superagent.storage.Store;
import dev.superagent.task.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.superagent.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeTest {
    @TempDir Path temporary;

    // 这些用例用 "A"/"B" 之类极短提示词当夹具，验证调度与预算，不验证范围策略；
    // 范围闸门本身由 ScopeGateTest 覆盖。所以这里显式切到探索模式。
    @BeforeEach void allowTerseFixturePrompts(){System.setProperty(ScopeGate.EXPLORATORY_PROPERTY,"true");}
    @AfterEach void restoreScopePolicy(){System.clearProperty(ScopeGate.EXPLORATORY_PROPERTY);}

    @Test void twoWorkersCanHaveModelRequestsInFlightAtTheSameTime()throws Exception {
        Path repo=repo(temporary);Config c=config(temporary.resolve("state"));
        CountDownLatch bothEntered=new CountDownLatch(2),release=new CountDownLatch(1);
        ModelClient model=(messages,tools)->{bothEntered.countDown();release.await();return answer("done");};
        try(Store store=new Store(c.stateDir());TaskManager manager=new TaskManager(c,store,model,repo,s->{})) {
            Task a=manager.create("A"),b=manager.create("B");manager.start();
            try {assertTrue(bothEntered.await(10,TimeUnit.SECONDS),"Both tasks must reach the model before either finishes");}
            finally {release.countDown();}
            status(manager,a.id,Task.Status.READY);status(manager,b.id,Task.Status.READY);
        }
    }
    @Test void followupArrivingWhileModelAsksAQuestionIsNotLeftWaiting()throws Exception {
        Path repo=repo(temporary);Config c=config(temporary.resolve("state"));
        CountDownLatch modelEntered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        ModelClient model=(messages,tools)->{
            if(calls.getAndIncrement()==0) {
                modelEntered.countDown();release.await();return call("ask_user",Json.object().put("question","title?"));
            }
            assertTrue(messages.stream().anyMatch(m->m.path("role").asText().equals("tool")&&m.path("content").asText().contains("already supplied title")));
            return answer("done with followup");
        };
        try(Store store=new Store(c.stateDir());TaskManager manager=new TaskManager(c,store,model,repo,s->{})) {
            Task t=manager.create("question race");manager.start();assertTrue(modelEntered.await(10,TimeUnit.SECONDS));
            manager.follow(t.id,"already supplied title");release.countDown();status(manager,t.id,Task.Status.READY);
            assertTrue(manager.get(t.id).inputs.stream().allMatch(in->in.consumed));
        } finally {release.countDown();}
    }

    @Test void independentTasksKeepSourceUntouchedAndExportNewFiles()throws Exception {
        Path repo=repo(temporary);Files.writeString(repo.resolve("original.txt"),"dirty source");
        Files.writeString(repo.resolve("untracked.txt"),"private source");
        Config c=config(temporary.resolve("state"));List<String> notices=new CopyOnWriteArrayList<>();
        try(Store store=new Store(c.stateDir());TaskManager manager=new TaskManager(c,store,new DemoModel(),repo,notices::add)) {
            Task a=manager.create("task A"),b=manager.create("task B");manager.start();
            status(manager,a.id,Task.Status.READY);status(manager,b.id,Task.Status.READY);
            assertTrue(Files.readString(Path.of(a.workspace,"demo-result.txt")).contains("task A"));
            assertTrue(Files.readString(Path.of(b.workspace,"demo-result.txt")).contains("task B"));
            assertEquals("original\n",Files.readString(Path.of(a.workspace,"original.txt")));
            assertFalse(Files.exists(Path.of(a.workspace,"untracked.txt")));
            assertEquals("dirty source",Files.readString(repo.resolve("original.txt")));
            assertFalse(Files.exists(repo.resolve("demo-result.txt")));
            var delivery=manager.diff(a.id);String patch=Files.readString(delivery.patch());
            assertTrue(patch.contains("new file mode"));assertTrue(patch.contains("demo-result.txt"));
            Path apply=temporary.resolve("apply-check");git(repo,"clone","--no-hardlinks","--",repo.toString(),apply.toString());
            git(apply,"apply","--check",delivery.patch().toString());
            assertTrue(notices.stream().anyMatch(n->n.contains("未提交")));
            manager.accept(a.id);assertEquals(Task.Status.COMPLETED,manager.get(a.id).status);
            manager.follow(a.id,"new requirement");status(manager,a.id,Task.Status.READY);
            assertTrue(manager.get(a.id).messages.stream().anyMatch(m->m.path("content").asText().equals("new requirement")));
        }
    }
    @Test void waitingQuestionReleasesTheOnlyWorkerAndFollowupIsDelivered()throws Exception {
        Path repo=repo(temporary);Config c=config(temporary.resolve("state"),1,30,100000);
        try(Store store=new Store(c.stateDir());TaskManager manager=new TaskManager(c,store,new DemoModel(),repo,s->{})) {
            Task a=manager.create("[ask] task A");manager.start();status(manager,a.id,Task.Status.WAITING_INPUT);
            Task b=manager.create("task B");status(manager,b.id,Task.Status.READY);
            manager.follow(a.id,"专用标题");status(manager,a.id,Task.Status.READY);
            assertTrue(manager.get(a.id).messages.stream().anyMatch(m->m.path("role").asText().equals("tool")&&m.path("content").asText().contains("专用标题")));
            assertFalse(manager.get(b.id).messages.stream().anyMatch(m->m.path("content").asText().contains("专用标题")));
        }
    }
    @Test void denialNeverRunsCommandAndApprovalRunsOnlyOnce()throws Exception {
        Path repo=repo(temporary);Config c=config(temporary.resolve("state"));
        try(Store store=new Store(c.stateDir());TaskManager manager=new TaskManager(c,store,commandModel(markerCommand()),repo,s->{})) {
            Task denied=manager.create("deny");manager.start();status(manager,denied.id,Task.Status.WAITING_APPROVAL);
            assertFalse(Files.exists(Path.of(denied.workspace,"marker.txt")));
            String approval=manager.get(denied.id).outstanding().approvalId;
            manager.approve(approval,false);status(manager,denied.id,Task.Status.READY);
            assertFalse(Files.exists(Path.of(denied.workspace,"marker.txt")));
            assertThrows(IllegalArgumentException.class,()->manager.approve(approval,true));
            Task allowed=manager.create("allow");status(manager,allowed.id,Task.Status.WAITING_APPROVAL);
            String allowedApproval=manager.get(allowed.id).outstanding().approvalId;
            manager.approve(allowedApproval,true);status(manager,allowed.id,Task.Status.READY);
            assertTrue(Files.readString(Path.of(allowed.workspace,"marker.txt")).contains("executed"));
            assertEquals(1,manager.get(allowed.id).validations.size());
            assertEquals("USED",manager.get(allowed.id).approvals.get(0).decision);
        }
        try(Store store=new Store(c.stateDir());TaskManager manager=new TaskManager(c,store,new DemoModel(),repo,s->{})) {
            assertTrue(manager.list().stream().allMatch(t->t.status==Task.Status.READY));
        }
    }
    @Test void oldApprovalsExpireAcrossRestartAndRequireNewApproval()throws Exception {
        Path repo=repo(temporary);Config c=config(temporary.resolve("state"));String id,old;
        try(Store store=new Store(c.stateDir());TaskManager manager=new TaskManager(c,store,commandModel(markerCommand()),repo,s->{})) {
            id=manager.create("restart").id;manager.start();status(manager,id,Task.Status.WAITING_APPROVAL);old=manager.get(id).outstanding().approvalId;
        }
        try(Store store=new Store(c.stateDir());TaskManager manager=new TaskManager(c,store,commandModel(markerCommand()),repo,s->{})) {
            assertEquals(Task.Status.INTERRUPTED,manager.get(id).status);
            assertThrows(IllegalArgumentException.class,()->manager.approve(old,true));
            manager.resume(id);manager.start();status(manager,id,Task.Status.WAITING_APPROVAL);
            assertNotEquals(old,manager.get(id).outstanding().approvalId);
            assertFalse(Files.exists(Path.of(manager.get(id).workspace,"marker.txt")));
        }
    }
    @Test void callAndTokenBudgetsStopWithoutLosingWrittenFiles()throws Exception {
        Path repo=repo(temporary);
        for(int mode=0;mode<2;mode++) {
            Config c=config(temporary.resolve("budget"+mode),1,mode==0?1:30,mode==0?100000:10);
            try(Store store=new Store(c.stateDir());TaskManager manager=new TaskManager(c,store,new DemoModel(),repo,s->{})) {
                String id=manager.create("budget").id;manager.start();status(manager,id,Task.Status.BUDGET_EXCEEDED);
                assertTrue(Files.exists(Path.of(manager.get(id).workspace,"demo-result.txt")));
                assertEquals(1,manager.get(id).calls);
            }
        }
    }
    @Test void cancelInterruptsModelButDoesNotAffectOtherTasks()throws Exception {
        Path repo=repo(temporary);Config c=config(temporary.resolve("state"));
        CountDownLatch entered=new CountDownLatch(1),interrupted=new CountDownLatch(1);
        ModelClient model=(messages,tools)->{
            if(messages.stream().anyMatch(m->m.path("content").asText().equals("block"))) {
                entered.countDown();try{Thread.sleep(30000);}catch(InterruptedException e){interrupted.countDown();throw e;}
            }
            return answer("done");
        };
        try(Store store=new Store(c.stateDir());TaskManager manager=new TaskManager(c,store,model,repo,s->{})) {
            Task blocked=manager.create("block");manager.start();assertTrue(entered.await(10,TimeUnit.SECONDS));
            Task normal=manager.create("normal");status(manager,normal.id,Task.Status.READY);
            manager.cancel(blocked.id);assertTrue(interrupted.await(3,TimeUnit.SECONDS));
            assertEquals(Task.Status.CANCELED,manager.get(blocked.id).status);
            assertEquals(Task.Status.READY,manager.get(normal.id).status);
        }
    }
    @Test void cancelStopsRunningCommandBeforeItsDelayedWrite()throws Exception {
        Path repo=repo(temporary);Config c=config(temporary.resolve("state"));
        String command=windows()?"Write-Output started; Start-Sleep -Seconds 30; Set-Content marker.txt bad":"echo started; sleep 30; echo bad > marker.txt";
        try(Store store=new Store(c.stateDir());TaskManager manager=new TaskManager(c,store,commandModel(command),repo,s->{})) {
            Task t=manager.create("command cancel");manager.start();status(manager,t.id,Task.Status.WAITING_APPROVAL);
            manager.approve(manager.get(t.id).outstanding().approvalId,true);
            await(()->{
                Task current=manager.get(t.id);return current.outstanding()!=null&&current.outstanding().phase==Task.Phase.EXECUTING;
            });
            // Wait for shell output, so cancellation covers a started process, not just a queued step.
            await(()->{
                try(var logs=Files.list(c.stateDir().resolve("logs").resolve(t.id))) {
                    return logs.anyMatch(p->{try{return Files.readString(p).contains("started");}catch(Exception e){return false;}});
                }catch(Exception e){return false;}
            });
            manager.cancel(t.id);await(()->manager.get(t.id).status==Task.Status.CANCELED);
            assertFalse(Files.exists(Path.of(t.workspace,"marker.txt")));
        }
    }
    @Test void unknownToolAndModelErrorsProduceReadableStates()throws Exception {
        Path repo=repo(temporary);Config c=config(temporary.resolve("state"));AtomicInteger invocations=new AtomicInteger();
        ModelClient model=(messages,tools)->{
            if(invocations.getAndIncrement()==0)return call("made_up_tool",Json.object());
            assertTrue(messages.stream().anyMatch(m->m.path("content").asText().contains("未知工具")));
            throw new java.io.IOException("mock network failure");
        };
        try(Store store=new Store(c.stateDir());TaskManager manager=new TaskManager(c,store,model,repo,s->{})) {
            Task t=manager.create("failure");manager.start();status(manager,t.id,Task.Status.FAILED);
            assertTrue(manager.get(t.id).error.contains("mock network failure"));assertEquals(2,invocations.get());
        }
    }
}
