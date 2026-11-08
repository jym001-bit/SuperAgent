package dev.superagent.task;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** One durable aggregate: changes and their next action are committed together. */
public class Task {
    public enum Status { QUEUED, RUNNING, WAITING_INPUT, WAITING_APPROVAL, READY, COMPLETED,
                         FAILED, CANCELED, INTERRUPTED, BUDGET_EXCEEDED,
                         PLANNING, PLAN_READY, PLAN_RUNNING, WAITING_DEPENDENCIES, BLOCKED, ASSEMBLING, CONFLICT }
    public enum Phase { PENDING, WAITING_APPROVAL, WAITING_INPUT, EXECUTING, UNKNOWN, DONE }
    public String id, prompt, repo, baseCommit, workspace, createdAt;
    public String sourceThread;
    public boolean planned;
    public String parentId, nodeKey;
    public List<TaskPlan.Node> plan = new ArrayList<>();
    public Map<String, String> children = new LinkedHashMap<>();
    public PatchArtifact artifact;
    public boolean readyToFreeze;
    public Publication publication;
    public Status status = Status.QUEUED;
    public String result = "", error = "", progress = "排队", question = "";
    public int calls;
    public long tokens;
    /** 上下文治理：折叠次数与最近一次请求的 token 估算，供成本视图与诊断使用。 */
    public int contextTrims;
    public long contextTokens;
    public boolean workspaceReady;
    public List<ObjectNode> messages = new ArrayList<>();
    public List<Input> inputs = new ArrayList<>();
    public List<Call> tools = new ArrayList<>();
    public List<Approval> approvals = new ArrayList<>();
    public List<String> validations = new ArrayList<>();
    public record PatchArtifact(String path, String sha256, String files) {}
    public static class Publication {
        public enum State { QUEUED, SENDING, PUBLISHED, UNCERTAIN }
        public State state = State.QUEUED;
        public String commit, branch, base, patchSha256, url, error;
    }
    public static class Input {
        public String id = UUID.randomUUID().toString(), text;
        public boolean consumed;
        public Input() {}
        public Input(String text) { this.text = text; }
    }
    public static class Call {
        public String id, name, arguments, result = "", approvalId, expectedPath, expectedContent, logPath;
        public Phase phase = Phase.PENDING;
        public boolean appended;
        public Call() {}
        public Call(String id, String name, String arguments) {
            this.id = id; this.name = name; this.arguments = arguments;
        }
    }
    public static class Approval {
        public String id = "ap_" + UUID.randomUUID().toString().substring(0,8);
        public String callId, command, cwd, decision = "PENDING";
        public Approval() {}
        public Approval(String callId, String command, String cwd) {
            this.callId = callId; this.command = command; this.cwd = cwd;
        }
    }
    public Call outstanding() { return tools.stream().filter(c -> !c.appended).findFirst().orElse(null); }
}
