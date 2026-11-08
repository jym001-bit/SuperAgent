package dev.superagent;

import dev.superagent.model.*;
import dev.superagent.storage.Store;
import dev.superagent.task.*;
import dev.superagent.tool.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static dev.superagent.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest {
    @TempDir Path temporary;
    @Test void forciblyTerminatedHostKeepsStateAndNeverReplaysUnknownCommand()throws Exception {
        Path repo=repo(temporary),state=temporary.resolve("state");
        var child=Processes.run(List.of(javaExecutable(),"-cp",classpath(),CrashProbe.class.getName(),state.toString(),repo.toString()),
                temporary,temporary.resolve("crash.log"),Duration.ofSeconds(20),p->{});
        assertEquals(0,child.exitCode(),child.output());Config c=config(state);
        ModelClient model=(messages,tools)->{
            assertTrue(messages.stream().anyMatch(m->m.path("role").asText().equals("tool")&&m.path("content").asText().contains("结果不明")));
            return answer("inspected existing work");
        };
        try(Store store=new Store(state);TaskManager manager=new TaskManager(c,store,model,repo,s->{})) {
            Task task=manager.list().get(0);assertEquals(Task.Status.INTERRUPTED,task.status);
            assertEquals(Task.Phase.UNKNOWN,task.outstanding().phase);
            Path marker=Path.of(task.workspace,"marker.txt");assertEquals(List.of("once"),Files.readAllLines(marker));
            manager.resume(task.id);manager.start();status(manager,task.id,Task.Status.READY);
            assertEquals(List.of("once"),Files.readAllLines(marker));
            assertEquals(1,manager.get(task.id).tools.size());
        }
    }
    @Test void interruptedWriteIsReconciledByItsExpectedContent()throws Exception {
        Path repo=repo(temporary),state=temporary.resolve("state");Config c=config(state);String id;
        try(Store store=new Store(state);TaskManager manager=new TaskManager(c,store,new DemoModel(),repo,s->{})) {
            Task task=manager.create("write recovery");id=task.id;new Workspaces(state).prepare(task);
            Files.writeString(Path.of(task.workspace,"new.txt"),"already written");
            store.update(id,"interrupted_write",t->{
                t.workspaceReady=true;t.status=Task.Status.INTERRUPTED;
                var reply=call("write_file",Json.object().put("path","new.txt").put("content","already written"));t.messages.add(reply.message());
                String callId=reply.message().path("tool_calls").path(0).path("id").asText();
                Task.Call tool=new Task.Call(callId,"write_file","{}");tool.phase=Task.Phase.UNKNOWN;tool.expectedPath="new.txt";tool.expectedContent="already written";t.tools.add(tool);
            });
            manager.resume(id);
            assertEquals(Task.Phase.DONE,manager.get(id).outstanding().phase);
            assertTrue(manager.get(id).outstanding().result.contains("该写入已完成"));
        }
    }
    @Test void queuedWorkAndHumanQuestionsSurviveRestart()throws Exception {
        Path repo=repo(temporary),state=temporary.resolve("state");Config c=config(state);String queued,waiting;
        try(Store store=new Store(state);TaskManager manager=new TaskManager(c,store,new DemoModel(),repo,s->{})) {
            waiting=manager.create("[ask] waiting").id;manager.start();status(manager,waiting,Task.Status.WAITING_INPUT);
            queued=manager.create("queued").id;
        }
        try(Store store=new Store(state);TaskManager manager=new TaskManager(c,store,new DemoModel(),repo,s->{})) {
            assertEquals(Task.Status.WAITING_INPUT,manager.get(waiting).status);
            manager.follow(waiting,"reply after restart");
            if(manager.get(queued).status==Task.Status.INTERRUPTED)manager.resume(queued);
            manager.start();status(manager,waiting,Task.Status.READY);status(manager,queued,Task.Status.READY);
        }
    }
}
