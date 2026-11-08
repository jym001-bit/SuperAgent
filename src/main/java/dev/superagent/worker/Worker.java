package dev.superagent.worker;

import dev.superagent.model.*;
import dev.superagent.storage.Store;
import dev.superagent.task.*;
import dev.superagent.tool.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Executes one durable step, then returns the pool thread to the scheduler. */
public final class Worker {
    private final Store store;
    private final Workspaces workspaces;
    private final ModelClient model;
    private final Config config;
    private final Consumer<String> notice;
    private final BiConsumer<String,Process> processStarted;
    private final CommandRunner commands;
    public Worker(Store store,Workspaces workspaces,ModelClient model,Config config,
                  Consumer<String> notice,BiConsumer<String,Process> processStarted,CommandRunner commands) {
        this.store=store;this.workspaces=workspaces;this.model=model;this.config=config;
        this.notice=notice;this.processStarted=processStarted;
        this.commands=commands;
    }
    private void update(String id,String event,Consumer<Task> change) {
        if(Thread.currentThread().isInterrupted()) throw new Stopped();
        store.update(id,event,t->{ if(t.status!=Task.Status.RUNNING) throw new Stopped(); change.accept(t); });
    }
    /** 只有真的在追加对话时才写消息表；其余状态推进不读不写历史。 */
    private void updateConversation(String id,String event,Consumer<Task> change) {
        if(Thread.currentThread().isInterrupted()) throw new Stopped();
        store.updateConversation(id,event,t->{ if(t.status!=Task.Status.RUNNING) throw new Stopped(); change.accept(t); });
    }
    private static final class Stopped extends RuntimeException {}
    public void step(String id) {
        try {
            Task task=store.get(id);
            if(task.status!=Task.Status.RUNNING) return;
            if(!task.workspaceReady) {
                workspaces.prepare(task);
                update(id,"workspace_prepared",t->{ t.workspaceReady=true; t.progress="任务副本已准备"; });
                task=store.get(id);
            }
            Task.Call pending=task.outstanding();
            if(pending!=null) {
                tool(task,pending);
                return;
            }
            updateConversation(id,"inputs_consumed",t->{
                for(Task.Input in:t.inputs) if(!in.consumed) {
                    t.messages.add(Json.message("user",in.text)); in.consumed=true;
                }
            });
            task=store.get(id);
            if(task.calls>=config.maxCalls() || task.tokens>=config.maxTokens()) {
                update(id,"budget_exhausted",t->{t.status=Task.Status.BUDGET_EXCEEDED;t.progress="预算耗尽，可 /resume";});
                notice.accept(id+" 预算耗尽，工作已保留");return;
            }
            update(id,"model_started",t->{ t.calls++; t.progress="等待模型回复"; });
            var tools = ToolRegistry.specifications();
            if (!commands.enabled()) {
                for (int i = tools.size() - 1; i >= 0; i--) {
                    String name = tools.get(i).path("function").path("name").asText();
                    if (name.equals("execute_command") || name.equals("run_test")) tools.remove(i);
                }
            }
            ContextPolicy.Result context=ContextPolicy.prepare(task.messages,ContextPolicy.budget());
            if(context.trimmed()) notice.accept(id+" 上下文已折叠 "+context.folded()+" 轮早期对话（原始记录仍在历史中）");
            ModelClient.Reply reply=model.complete(context.messages(),tools);
            ObjectNode message=reply.message();
            List<Task.Call> calls=parseCalls(message);
            updateConversation(id,"model_response",t->{
                t.tokens+=Math.max(1,reply.tokens());
                t.contextTokens=context.estimatedTokens();
                if(context.trimmed())t.contextTrims++;
                t.messages.add(message);
                t.tools.addAll(calls);
                if(!Set.of("stop","tool_calls").contains(reply.finishReason())) {
                    t.status=Task.Status.FAILED;t.error="模型未正常结束: "+reply.finishReason();t.progress=t.error;
                } else if(!calls.isEmpty()) {
                    t.status=Task.Status.QUEUED;t.progress="等待执行工具";
                } else if(message.path("content").asText("").isBlank()) {
                    t.status=Task.Status.FAILED;t.error="模型返回空结果";t.progress=t.error;
                } else {
                    t.result=message.path("content").asText();
                    boolean more=t.inputs.stream().anyMatch(in->!in.consumed);
                    t.status=more?Task.Status.QUEUED:Task.Status.READY;
                    t.progress=more?"处理追加需求":"待验收";
                }
            });
            Task current=store.get(id);
            if(current.status==Task.Status.READY) notice.accept(id+" 待验收："+shorten(current.result)+"（/show "+id+"，/diff "+id+"）");
            if(current.status==Task.Status.FAILED) notice.accept(id+" "+current.error);
        } catch(Stopped e) {
            // Cancellation or shutdown changed the durable state; discard late replies.
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch(Exception e) {
            try {
                store.update(id,"step_failed",t->{
                    if(t.status==Task.Status.RUNNING) {
                        t.status=Task.Status.FAILED;t.error=e.getClass().getSimpleName()+": "+e.getMessage();t.progress=t.error;
                    }
                });
                notice.accept(id+" 执行失败："+e.getMessage()+"（工作已保留，/resume "+id+"）");
            } catch(Exception ignored) { /* store may already be shutting down */ }
        }
    }
    private List<Task.Call> parseCalls(ObjectNode message) {
        List<Task.Call> calls=new ArrayList<>();
        JsonNode tools=message.path("tool_calls");
        if(tools.isMissingNode()||tools.isNull()) return calls;
        if(!tools.isArray()) throw new IllegalArgumentException("tool_calls 必须为数组");
        Set<String> ids=new HashSet<>();
        for(JsonNode tool:tools) {
            String id=tool.path("id").asText(),name=tool.path("function").path("name").asText();
            JsonNode args=tool.path("function").path("arguments");
            if(id.isBlank()||name.isBlank()||!args.isTextual()||!ids.add(id)||!tool.path("type").asText().equals("function"))
                throw new IllegalArgumentException("无效的模型工具调用");
            calls.add(new Task.Call(id,name,args.asText()));
        }
        return calls;
    }
    private void tool(Task task,Task.Call call) throws Exception {
        if(call.phase==Task.Phase.DONE) { append(task.id,call.id);return; }
        if(call.phase==Task.Phase.UNKNOWN||call.phase==Task.Phase.EXECUTING)
            throw new IllegalStateException("工具结果不明，需要 /resume 核对");
        JsonNode args;
        try { args=Json.parse(call.arguments);if(!args.isObject()) throw new IllegalArgumentException("参数必须为对象"); }
        catch(Exception e) { complete(task.id,call.id,"工具错误: "+e.getMessage());return; }
        try {
            if(call.name.equals("ask_user")) {
                String question=FileTools.required(args,"question");
                update(task.id,"waiting_input",t->{
                    List<Task.Input> arrived=t.inputs.stream().filter(in->!in.consumed).toList();
                    if(!arrived.isEmpty()) {
                        Task.Call c=find(t,call.id);c.phase=Task.Phase.DONE;
                        c.result="提出问题前收到用户补充，请先核对是否足以回答问题：\n"+String.join("\n",arrived.stream().map(in->in.text).toList());
                        arrived.forEach(in->in.consumed=true);t.status=Task.Status.QUEUED;t.progress="处理提问前收到的补充";
                    } else {
                        find(t,call.id).phase=Task.Phase.WAITING_INPUT;
                        t.question=question;t.progress="等待回复";t.status=Task.Status.WAITING_INPUT;
                    }
                });
                if(store.get(task.id).status==Task.Status.WAITING_INPUT)
                    notice.accept(task.id+" 需要回复："+question+"（/follow "+task.id+" <回答>）");
                return;
            }
            if(call.name.equals("execute_command")||call.name.equals("run_test")) {
                if (!commands.enabled()) {
                    complete(task.id, call.id, "当前入口已关闭命令执行，请报告未执行的验证。");
                    return;
                }
                // run_test 同样执行仓库里的构建脚本，必须走正式审批，不能因为「只是测试」免批。
                String command=call.name.equals("run_test")
                        ? "run_test "+Json.text(args)
                        : FileTools.required(args,"command");
                if(call.phase==Task.Phase.PENDING) {
                    // 只读白名单默认关闭；开启时也照样写入审批记录与审计事件，只是不需要人点。
                    CommandPolicy.Decision policy = call.name.equals("execute_command")
                            ? CommandPolicy.classify(command)
                            : CommandPolicy.Decision.manual("run_test 执行仓库构建脚本，一律人工审批");
                    if(policy.autoApprove()) {
                        Task.Approval approval=new Task.Approval(call.id,command,task.workspace);
                        approval.decision="AUTO_APPROVED";
                        update(task.id,"approval_auto_approved",t->{
                            t.approvals.add(approval);Task.Call c=find(t,call.id);c.phase=Task.Phase.WAITING_APPROVAL;c.approvalId=approval.id;
                            t.status=Task.Status.QUEUED;t.progress="只读命令自动批准: "+policy.reason();
                        });
                        notice.accept(task.id+" 只读命令自动批准（"+policy.reason()+"）: "+command);
                        return;
                    }
                    Task.Approval approval=new Task.Approval(call.id,command,task.workspace);
                    update(task.id,"approval_requested",t->{
                        t.approvals.add(approval);Task.Call c=find(t,call.id);c.phase=Task.Phase.WAITING_APPROVAL;c.approvalId=approval.id;
                        t.status=Task.Status.WAITING_APPROVAL;t.progress="等待命令审批";
                    });
                    notice.accept(task.id+" 命令待批准 "+approval.id+"\n目录: "+task.workspace+"\n命令: "+command+"\n/approve "+approval.id+" 或 /deny "+approval.id);return;
                }
                Task.Approval approval=task.approvals.stream().filter(a->a.id.equals(call.approvalId)).findFirst().orElseThrow();
                if(approval.decision.equals("DENIED")) { complete(task.id,call.id,"用户拒绝执行该命令，请换方法或询问用户。");return; }
                if(!Set.of("APPROVED","AUTO_APPROVED").contains(approval.decision)||!approval.command.equals(command)||!approval.cwd.equals(task.workspace))
                    throw new IllegalStateException("审批已失效，需要重新申请");
                if(call.name.equals("run_test")) {
                    Path log=config.stateDir().resolve("logs").resolve(task.id).resolve("test-"+UUID.randomUUID()+".log");
                    update(task.id,"test_started",t->{
                        Task.Call c=find(t,call.id);c.phase=Task.Phase.EXECUTING;c.logPath=log.toString();
                        t.approvals.stream().filter(a->a.id.equals(call.approvalId)).findFirst().orElseThrow()
                                .decision=approval.decision.equals("AUTO_APPROVED")?"USED_AUTO":"USED";
                        t.progress="测试命令执行中";
                    });
                    String output=ToolRegistry.execute(call.name,args,Path.of(task.workspace));
                    Files.createDirectories(log.getParent());
                    Files.writeString(log,output,StandardCharsets.UTF_8);
                    update(task.id,"test_result",t->{
                        t.validations.add(digest(output));
                        Task.Call c=find(t,call.id);c.result=output;c.phase=Task.Phase.DONE;t.status=Task.Status.QUEUED;
                    });return;
                }
                Path log=config.stateDir().resolve("logs").resolve(task.id).resolve("command-"+UUID.randomUUID()+".log");
                update(task.id,"command_started",t->{
                    Task.Call c=find(t,call.id);c.phase=Task.Phase.EXECUTING;c.logPath=log.toString();
                    t.approvals.stream().filter(a->a.id.equals(call.approvalId)).findFirst().orElseThrow()
                            .decision=approval.decision.equals("AUTO_APPROVED")?"USED_AUTO":"USED";
                    t.progress="命令执行中";
                });
                Processes.Result result=commands.run(command,Path.of(task.workspace),log,config.commandTimeout(),p->processStarted.accept(task.id,p));
                String output="exit="+result.exitCode()+", timedOut="+result.timedOut()+", log="+log+"\n"+result.output();
                update(task.id,"command_result",t->{
                    t.validations.add(command+" => exit="+result.exitCode()+", timedOut="+result.timedOut()+", log="+log);
                    Task.Call c=find(t,call.id);c.result=output;c.phase=Task.Phase.DONE;t.status=Task.Status.QUEUED;
                });return;
            }
            if(!ToolRegistry.known(call.name))throw new IllegalArgumentException("未知工具: "+call.name);
            FileTools files=new FileTools(Path.of(task.workspace));
            String expectedPath=null,expectedContent=null;
            if(call.name.equals("write_file")||call.name.equals("edit_file")) {
                // 机械闸门：需求指不出改哪里时，不允许落笔。提示词管不住的推断，由执行层兜住。
                // 计划子任务例外：整个计划已由人工批准，范围以该节点的目标为准。
                ScopeGate.Decision scope=task.parentId!=null
                        ? new ScopeGate.Decision(true,"计划已获人工批准，范围以计划节点为准")
                        : ScopeGate.check(requestText(task));
                if(ScopeGate.strict()&&!scope.allowed()) {
                    update(task.id,"scope_blocked",t->t.validations.add("范围闸门拦截: "+scope.reason()));
                    complete(task.id,call.id,"写入被拒绝（改动范围未确认）："+scope.reason()
                            +"。请先用 ask_user 询问用户要改哪个文件、类或方法，以及用什么命令或检查验收；"
                            +"在得到明确目标之前不要重试这次写入。");
                    return;
                }
                expectedPath=FileTools.required(args,"path");
                Path target=files.resolve(expectedPath);
                if(call.name.equals("write_file"))expectedContent=FileTools.required(args,"content");
                else {
                    if(Files.size(target)>1_000_000)throw new IllegalArgumentException("文件超过 1MB");
                    String content=Files.readString(target),old=FileTools.required(args,"old_text"),replacement=FileTools.required(args,"new_text");
                    int at=content.indexOf(old);
                    if(at<0||content.indexOf(old,at+1)>=0)throw new IllegalArgumentException("old_text 必须精确且唯一匹配");
                    expectedContent=content.substring(0,at)+replacement+content.substring(at+old.length());
                }
            }
            String pathIntent=expectedPath,contentIntent=expectedContent;
            // Mark the intended resulting content before disk writes, including precise edits.
            update(task.id,"file_tool_started",t->{
                Task.Call c=find(t,call.id);c.phase=Task.Phase.EXECUTING;c.expectedPath=pathIntent;c.expectedContent=contentIntent;
                t.progress="工具: "+call.name;
            });
            String result=ToolRegistry.execute(call.name,args,Path.of(task.workspace));
            complete(task.id,call.id,result);
        } catch(Stopped|InterruptedException e) {throw e;}
        catch(Exception e) {
            Task latest=store.get(task.id);
            Task.Call current=latest.outstanding();
            if(call.name.equals("execute_command")&&current!=null&&current.phase==Task.Phase.EXECUTING) {
                update(task.id,"command_uncertain",t->{
                    find(t,call.id).phase=Task.Phase.UNKNOWN;t.status=Task.Status.INTERRUPTED;
                    t.error="命令结果不明: "+e.getMessage();t.progress="需检查目录和日志后 /resume";
                });
                notice.accept(task.id+" 命令结果不明，已暂停，检查后 /resume");
            } else complete(task.id,call.id,"工具错误: "+e.getMessage());
        }
    }
    private void complete(String id,String callId,String result) {
        update(id,"tool_result",t->{Task.Call c=find(t,callId);c.result=FileTools.bounded(result);c.phase=Task.Phase.DONE;t.status=Task.Status.QUEUED;});
    }
    private void append(String id,String callId) {
        updateConversation(id,"tool_result_delivered",t->{
            Task.Call c=find(t,callId);
            t.messages.add(Json.message("tool",c.result).put("tool_call_id",callId));c.appended=true;t.status=Task.Status.QUEUED;
        });
    }
    public static Task.Call find(Task t,String id) {return t.tools.stream().filter(c->c.id.equals(id)&&!c.appended).findFirst().orElseThrow();}
    /** 需求原文 + 用户全部补充：闸门看的是「到此刻为止用户说过的所有话」。 */
    private static String requestText(Task task) {
        StringBuilder text=new StringBuilder(task.prompt==null?"":task.prompt);
        for(Task.Input input:task.inputs) if(input.text!=null) text.append('\n').append(input.text);
        return text.toString();
    }
    private static String digest(String output) {
        for (String line : output.split("\n")) if (!line.isBlank()) return line.strip();
        return "run_test => 无输出";
    }
    private static String shorten(String s) {return s.length()>180?s.substring(0,180)+"…":s;}
}
