package dev.superagent;

import dev.superagent.model.*;
import dev.superagent.router.Router;
import dev.superagent.storage.Store;
import dev.superagent.task.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import static dev.superagent.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class RouterTest {
    @TempDir Path temporary;
    @Test void newFollowClarifyAndInvalidDecisions()throws Exception {
        Path repo=repo(temporary);Config c=config(temporary.resolve("state"));
        AtomicReference<String> decision=new AtomicReference<>("{\"decision\":\"new\",\"text\":\"original task\"}");
        ModelClient model=(messages,tools)->{assertNull(tools,"Router must not receive tools");return answer(decision.get());};
        try(Store store=new Store(c.stateDir());TaskManager manager=new TaskManager(c,store,new DemoModel(),repo,s->{})) {
            Router router=new Router(store,manager,model);router.route("create task");String id=manager.list().get(0).id;
            decision.set(Json.text(Json.object().put("decision","follow").put("task_id",id).put("text","specific followup")));
            router.route("add requirement");assertEquals("specific followup",manager.get(id).inputs.get(1).text);
            decision.set("{\"decision\":\"clarify\",\"text\":\"which task?\"}");assertEquals("which task?",router.route("fix it"));
            assertEquals(1,manager.list().size());
            for(String bad:new String[]{"not json","{\"decision\":\"execute\",\"text\":\"bad\"}","{\"decision\":\"follow\",\"task_id\":\"missing\",\"text\":\"bad\"}","{\"decision\":\"new\",\"text\":\"bad\"} trailing"}) {
                decision.set(bad);assertThrows(IllegalArgumentException.class,()->router.route("bad"));assertEquals(1,manager.list().size());
            }
        }
    }
}
