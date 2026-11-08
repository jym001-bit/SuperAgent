package dev.superagent;

import dev.superagent.model.*;
import dev.superagent.task.*;
import dev.superagent.tool.Processes;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

public final class TestSupport {
    public static Config config(Path state){return config(state,2,30,100000);}
    public static Config config(Path state,int workers,int calls,long tokens) {
        return new Config(state,"http://127.0.0.1:1","mock","test","standard",false,workers,calls,tokens,Duration.ofSeconds(5),Duration.ofSeconds(2));
    }
    public static Path repo(Path parent) throws Exception {
        Path repo=Files.createDirectories(parent.resolve("source"));
        Files.writeString(repo.resolve("original.txt"),"original\n");
        git(repo,"init");git(repo,"add",".");git(repo,"-c","user.name=Test","-c","user.email=test@example.invalid","commit","-m","initial");
        return repo;
    }
    public static void git(Path cwd,String... args)throws Exception {
        List<String> argv=new ArrayList<>(List.of("git","-c","core.hooksPath="));argv.addAll(List.of(args));
        var result=Processes.run(argv,cwd,cwd.getParent().resolve(UUID.randomUUID()+".log"),Duration.ofSeconds(15),p->{});
        assertEquals(0,result.exitCode(),result.output());
    }
    public static void await(BooleanSupplier condition)throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(20);
        assertTrue(condition.getAsBoolean(),"condition did not become true within 15s");
    }
    public static void status(TaskManager manager,String id,Task.Status state)throws Exception {
        await(()->manager.get(id).status==state);
    }
    public static ModelClient.Reply call(String name,ObjectNode args) {
        ObjectNode message=Json.message("assistant","");
        ObjectNode tool=Json.object().put("type","function").put("id","call_"+UUID.randomUUID());
        tool.set("function",Json.object().put("name",name).put("arguments",Json.text(args)));
        message.set("tool_calls",Json.array().add(tool));return new ModelClient.Reply(message,10,"tool_calls");
    }
    public static ModelClient.Reply answer(String content){return new ModelClient.Reply(Json.message("assistant",content),10,"stop");}
    public static ModelClient commandModel(String command) {
        return (messages,tools)->messages.stream().noneMatch(m->m.path("role").asText().equals("tool"))
                ?call("execute_command",Json.object().put("command",command)):answer("command handled");
    }
    public static boolean windows(){return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");}
    public static String markerCommand(){return windows()?"Set-Content -LiteralPath marker.txt -Value executed":"printf executed > marker.txt";}
    public static String javaExecutable(){return Path.of(System.getProperty("java.home"),"bin",windows()?"java.exe":"java").toString();}
    public static String classpath(){return System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));}
}
