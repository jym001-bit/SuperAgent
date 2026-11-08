package dev.superagent.model;

import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Deterministic offline demonstration, not an LLM or a coding capability benchmark. */
public final class DemoModel implements ModelClient {
    @Override public Reply complete(List<ObjectNode> messages,ArrayNode tools) {
        if(tools==null) {
            String input=messages.get(messages.size()-1).path("content").asText();
            boolean ambiguous=input.contains("它")||input.contains("那个任务");
            ObjectNode decision=Json.object().put("decision",ambiguous?"clarify":"new")
                    .put("text",ambiguous?"[离线演示] 请用 /follow <任务ID> <补充> 明确指定任务。":input);
            return reply(Json.message("assistant",Json.text(decision)),"stop");
        }
        long results=messages.stream().filter(m->m.path("role").asText().equals("tool")).count();
        String prompt=messages.stream().filter(m->m.path("role").asText().equals("user"))
                .map(m->m.path("content").asText()).findFirst().orElse("演示任务");
        if(results==0) return call("write_file",Json.object().put("path","demo-result.txt").put("content","离线演示任务："+prompt+"\n"));
        if(results==1) {
            if(prompt.contains("[ask]")) return call("ask_user",Json.object().put("question","演示：你希望使用什么标题？"));
            if(prompt.contains("[command]")) return call("execute_command",Json.object().put("command","echo superagent-demo"));
            return call("read_file",Json.object().put("path","demo-result.txt"));
        }
        String last=messages.get(messages.size()-1).path("content").asText();
        return reply(Json.message("assistant","[离线演示] 文件工具流程已完成。最近结果："+last+"\n真实编码能力需要配置模型后验收。"),"stop");
    }
    private Reply call(String name,ObjectNode arguments) {
        ObjectNode message=Json.message("assistant","");
        ObjectNode call=Json.object().put("id","call_"+UUID.randomUUID()).put("type","function");
        call.set("function",Json.object().put("name",name).put("arguments",Json.text(arguments)));
        message.set("tool_calls",Json.array().add(call));return reply(message,"tool_calls");
    }
    private Reply reply(ObjectNode message,String reason){return new Reply(message,20,reason);}
}
