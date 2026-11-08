package dev.superagent.cli;

import dev.superagent.model.*;
import dev.superagent.router.Router;
import dev.superagent.server.Service;
import dev.superagent.storage.Store;
import dev.superagent.task.*;
import org.jline.reader.*;
import org.jline.terminal.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class Main {
    private Main() {}
    public static void main(String[] args) {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out),true,StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err),true,StandardCharsets.UTF_8));
        try {run(args);}catch(Exception e){System.err.println("启动失败: "+e.getMessage());System.exit(1);}
    }
    static void run(String[] args) throws Exception {
        Path repo=null;boolean demo=false;boolean serve=false;boolean serveLocal=false;
        for(int i=0;i<args.length;i++) {
            switch(args[i]) {
                case "--repo" -> {if(++i>=args.length)throw new IllegalArgumentException("--repo 缺少路径");repo=Path.of(args[i]).toAbsolutePath().normalize();}
                case "--demo" -> demo=true;
                case "--serve" -> serve=true;
                case "--serve-local" -> serveLocal=true;
                case "--help","-h" -> {System.out.println("用法: java -jar superagent-java.jar --repo <Git仓库根目录> [--demo | --serve-local | --serve]\n"+HELP);return;}
                default -> throw new IllegalArgumentException("未知参数: "+args[i]);
            }
        }
        if(repo==null)throw new IllegalArgumentException("请通过 --repo 指定至少有一次提交的 Git 仓库");
        Config config=Config.environment();
        if (serve || serveLocal) {
            if (demo || (serve && serveLocal)) throw new IllegalArgumentException("启动模式不能同时使用");
            if (serveLocal) Service.runLocal(repo, config);
            else Service.run(repo, config);
            return;
        }
        try(Terminal terminal=CliTerminal.open();Store store=new Store(config.stateDir())) {
            store.requireLocal();
            LineReader reader=LineReaderBuilder.builder().terminal(terminal).build();
            Consumer<String> output=reader::printAbove;
            ModelClient model=demo?new DemoModel():new HttpModelClient(config);
            try(TaskManager tasks=new TaskManager(config,store,model,repo,output)) {
                Router router=new Router(store,tasks,model);
                ThreadPoolExecutor routing=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),r->{
                    Thread thread=new Thread(r,"superagent-router");thread.setDaemon(true);return thread;
                });
                AtomicBoolean stopping=new AtomicBoolean();
                Runnable shutdown=()->{
                    if(stopping.compareAndSet(false,true)) {
                        routing.shutdownNow();
                        try {routing.awaitTermination(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                        tasks.close();
                    }
                };
                Thread hook=new Thread(shutdown,"superagent-shutdown");Runtime.getRuntime().addShutdownHook(hook);
                try {
                    output.accept("Super-agent Java 0.1.0 | "+(demo?"离线模拟模型（不调用 API）":config.provider()+" / "+config.model())
                            +"\n目标仓库: "+repo+"\n状态目录: "+config.stateDir()+"\n输入需求创建任务，/help 查看命令。命令审批不构成系统沙箱。");
                    tasks.start();
                    while(!stopping.get()) {
                        String line;
                        try {line=reader.readLine("superagent> ").strip();}
                        catch(UserInterruptException e){output.accept("用 /cancel <任务ID> 取消任务，/exit 退出。");continue;}
                        catch(EndOfFileException e){break;}
                        if(line.isBlank())continue;
                        if(line.equals("/exit"))break;
                        try {
                            if (line.equals("/plan") || line.matches("/plan\\s+.*")) {
                                String goal = line.substring("/plan".length()).strip();
                                require(goal, "/plan <目标>");
                                routing.execute(() -> {
                                    try {
                                        Task task = tasks.plan(goal);
                                        output.accept(task.id + " | " + task.status + " | " + task.progress
                                                + (task.error.isBlank() ? "" : "\n" + task.error)
                                                + "\n用 /show " + task.id + " 查看计划");
                                    } catch (Exception error) {
                                        if (!stopping.get()) output.accept("规划失败: " + error.getMessage());
                                    }
                                });
                            } else if(line.startsWith("/")) command(line,tasks,output);
                            else routing.execute(()->{
                                try{output.accept(router.route(line));}
                                catch(Exception e){if(!stopping.get())output.accept("调度失败: "+e.getMessage());}
                            });
                        } catch(RejectedExecutionException e){output.accept("调度消息队列已满，请等待或使用明确命令。");}
                        catch(Exception e){output.accept("操作失败: "+e.getMessage());}
                    }
                } finally {
                    shutdown.run();
                    try {Runtime.getRuntime().removeShutdownHook(hook);}catch(IllegalStateException ignored){}
                }
            }
        }
    }
    private static void command(String line,TaskManager tasks,Consumer<String> out) throws Exception {
        String[] pair=line.split("\\s+",2);String verb=pair[0],rest=pair.length==2?pair[1]:"";
        switch(verb) {
            case "/help" -> out.accept(HELP);
            case "/new" -> {require(rest,"/new <需求>");tasks.create(rest);}
            case "/tasks" -> {
                List<Task> all=tasks.list();if(all.isEmpty())out.accept("暂无任务");
                for(Task t:all)out.accept(t.id+" | "+t.status+" | "+t.progress+" | "
                        +(t.parentId == null ? "" : "父任务 "+t.parentId+" / "+t.nodeKey+" | ")+shorten(t.prompt));
            }
            case "/show" -> {
                Task t=tasks.get(id(rest));
                Task.Call pending=t.outstanding();
                String pendingInfo=pending==null?"无":pending.name+" | "+pending.phase+"\n参数: "+pending.arguments+"\n工具日志: "+Objects.toString(pending.logPath,"无");
                out.accept(t.id+" | "+t.status+"\n需求: "+t.prompt+"\n进展: "+t.progress+"\n问题: "+t.question
                        +"\n基线: "+t.baseCommit+"\n目录: "+t.workspace+"\n本次模型调用: "+t.calls+" | Token: "+t.tokens
                        +"\nWorker 单任务预算: "+tasks.callLimit()+" 次模型调用 / "+tasks.tokenLimit()+" Token"
                        +"\n上下文: 已折叠 "+t.contextTrims+" 次 / 最近请求约 "+t.contextTokens+" token"
                        +"\n工具调用: "+t.tools.size()+" 次"+toolSummary(t)
                        +"\n错误: "+t.error+"\n未完成工具: "+pendingInfo+"\n结果:\n"+t.result+"\n验证记录:\n"+String.join("\n",t.validations));
                if (t.planned) {
                    for (TaskPlan.Node node : t.plan) {
                        String childId = t.children.get(node.key());
                        String status = childId == null ? "待批准" : childId + " | " + tasks.get(childId).status;
                        out.accept(node.key() + " | " + status + "\n  目标: " + node.goal()
                                + "\n  依赖: " + node.dependencies() + "\n  验收: " + node.acceptance());
                    }
                }
                if (t.artifact != null) out.accept("补丁 SHA-256: " + t.artifact.sha256());
            }
            case "/follow" -> {
                String[] fields=rest.split("\\s+",2);if(fields.length!=2)throw new IllegalArgumentException("用法: /follow <任务ID> <补充>");
                tasks.follow(fields[0],fields[1]);out.accept("补充已保存；停止中的任务需 /resume 才会继续。");
            }
            case "/cancel" -> {tasks.cancel(id(rest));out.accept("已请求取消，目录和结果已保留。");}
            case "/start" -> {
                Task task = tasks.startPlan(id(rest));
                out.accept(task.id + " | " + task.status + " | " + task.progress);
            }
            case "/resolve" -> {
                Task task = tasks.resolve(id(rest));
                out.accept(task.id + " | " + task.progress);
            }
            case "/archive" -> {
                require(rest, "/archive <保留天数>");
                int days;
                try { days = Integer.parseInt(rest.strip()); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("保留天数必须是整数"); }
                if (days < 0) throw new IllegalArgumentException("保留天数不能为负");
                var summary = tasks.archiveOlderThan(java.time.Duration.ofDays(days));
                if (summary.tasks() == 0) out.accept("没有可归档的已结束任务。");
                else out.accept("已归档 " + summary.tasks() + " 个已结束任务（对话 " + summary.messages()
                        + " 条，工具调用 " + summary.calls() + " 条，事件 " + summary.events()
                        + " 条）到 " + summary.file());
            }
            case "/resume" -> {tasks.resume(id(rest));out.accept("已恢复；本次预算重置，未知结果的旧命令不会自动重放。");}
            case "/accept" -> {tasks.accept(id(rest));out.accept("已验收；源仓库未修改。");}
            case "/approvals" -> {
                int count=0;
                for(Task t:tasks.list())for(Task.Approval a:t.approvals)if(a.decision.equals("PENDING")) {
                    out.accept(a.id+" | "+t.id+"\n目录: "+a.cwd+"\n命令: "+a.command);count++;
                }
                if(count==0)out.accept("没有待处理审批。");
            }
            case "/approve","/deny" -> {tasks.approve(id(rest),verb.equals("/approve"));out.accept("审批决定已保存。");}
            case "/diff" -> {
                Workspaces.Delivery d=tasks.diff(id(rest));out.accept("修改文件:\n"+d.files()+"\n补丁: "+d.patch()+"\n执行记录:\n"+String.join("\n",d.validations()));
            }
            default -> throw new IllegalArgumentException("未知命令，请输入 /help");
        }
    }
    private static String id(String rest){require(rest,"需要一个任务或审批 ID");if(rest.contains(" "))throw new IllegalArgumentException("只允许一个 ID");return rest;}
    private static String toolSummary(Task task) {
        if (task.tools.isEmpty()) return "";
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (Task.Call call : task.tools) counts.merge(call.name, 1, Integer::sum);
        StringBuilder text = new StringBuilder("（");
        boolean first = true;
        for (var entry : counts.entrySet()) {
            if (!first) text.append(", ");
            text.append(entry.getKey()).append('×').append(entry.getValue());
            first = false;
        }
        return text.append('）').toString();
    }
    private static void require(String value,String usage){if(value.isBlank())throw new IllegalArgumentException(usage);}
    private static String shorten(String s){return s.length()>90?s.substring(0,90)+"…":s;}
    private static final String HELP="""
            /new <需求>                 明确创建任务
            /plan <目标>                生成待批准的依赖计划
            /start <父任务ID>           批准并启动计划
            /tasks                      列出任务
            /show <任务ID>              查看结果、目录和执行记录
            /follow <任务ID> <补充>     补充需求或回答问题
            /cancel <任务ID>            取消并保留已有工作
            /resume <任务ID>            显式恢复，重置本次预算
            /approvals                  查看待批准命令
            /approve <审批ID>           批准这一次命令
            /deny <审批ID>              拒绝这一次命令
            /diff <任务ID>              导出含新增文件的补丁
            /resolve <父任务ID>         手工处理合成冲突后重新导出
            /archive <保留天数>          把更早的已结束任务导出到状态目录 archive 后清理
            /accept <任务ID>            验收完成，不自动应用到源仓库
            /help                       查看帮助
            /exit                       保存状态并退出
            普通自然语言由调度模型处理；信息不足时会询问。
            """;
}
