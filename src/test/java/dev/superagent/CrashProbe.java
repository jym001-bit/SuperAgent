package dev.superagent;

import dev.superagent.model.*;
import dev.superagent.storage.Store;
import dev.superagent.task.*;
import dev.superagent.tool.Processes;
import java.nio.file.*;
import java.time.Duration;

/** A separate JVM dies after an external command succeeds but before saving its result. */
public final class CrashProbe {
    public static void main(String[] args)throws Exception {
        Path state=Path.of(args[0]),repo=Path.of(args[1]);Config config=TestSupport.config(state);
        Store store=new Store(state);
        TaskManager manager=new TaskManager(config,store,new DemoModel(),repo,s->{});
        Task task=manager.create("crash-recovery");new Workspaces(state).prepare(task);
        String command=TestSupport.windows()?"Add-Content -LiteralPath marker.txt -Value once":"echo once >> marker.txt";
        var reply=TestSupport.call("execute_command",Json.object().put("command",command));
        String callId=reply.message().path("tool_calls").path(0).path("id").asText();
        store.update(task.id,"external_intent",t->{
            t.workspaceReady=true;t.status=Task.Status.RUNNING;
            for(Task.Input input:t.inputs){t.messages.add(Json.message("user",input.text));input.consumed=true;}
            t.messages.add(reply.message());Task.Call call=new Task.Call(callId,"execute_command",Json.text(Json.object().put("command",command)));
            call.phase=Task.Phase.EXECUTING;t.tools.add(call);
            Task.Approval approval=new Task.Approval(callId,command,t.workspace);approval.decision="USED";t.approvals.add(approval);
        });
        var result=Processes.run(Processes.shell(command),Path.of(task.workspace),state.resolve("external.log"),Duration.ofSeconds(5),p->{});
        if(result.exitCode()!=0)throw new IllegalStateException(result.output());
        Runtime.getRuntime().halt(0);
    }
}
