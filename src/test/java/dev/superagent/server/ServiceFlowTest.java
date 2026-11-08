package dev.superagent.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.superagent.model.*;
import dev.superagent.storage.Store;
import dev.superagent.task.*;
import dev.superagent.tool.Processes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static dev.superagent.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ServiceFlowTest {
    @TempDir Path temporary;
    private static final String SECRET = "local-test-webhook-secret-value-1234567890";
    private static final String OPERATOR = "local-test-operator-secret-value-1234567890";
    private static final long REPO_ID = 8001;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test void signedIssueFlowsThroughRealHttpAdapterAndReviewedGitCommitToDraft() throws Exception {
        Path repo = repo(temporary);
        Path state = temporary.resolve("service-state");
        var modelServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        modelServer.createContext("/chat/completions", request -> {
            try (request) {
                JsonNode history = Json.parse(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).path("messages");
                boolean wrote = false;
                for (JsonNode message : history) if ("tool".equals(message.path("role").asText())) wrote = true;
                ModelClient.Reply reply = wrote ? answer("已修改")
                        : call("write_file", Json.object().put("path", "generated.txt").put("content", "reviewed content\n"));
                var choice = Json.object().put("finish_reason", reply.finishReason());
                choice.set("message", reply.message());
                var response = Json.object();
                response.set("choices", Json.array().add(choice));
                byte[] bytes = Json.text(response).getBytes(StandardCharsets.UTF_8);
                request.sendResponseHeaders(200, bytes.length);
                request.getResponseBody().write(bytes);
            }
        });
        modelServer.start();
        Config config = new Config(state, "http://127.0.0.1:" + modelServer.getAddress().getPort(),
                "local-test", "local-model-placeholder", "standard", false, 2, 12, 100_000,
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        Remote remote = new Remote();
        try (Store store = new Store(state);
             TaskManager tasks = new TaskManager(config, store, new HttpModelClient(config), repo, s -> {},
                     (command, cwd, log, timeout, started) -> { throw new AssertionError("No shell command expected"); }, true);
             Publisher publisher = new Publisher(store, remote);
             AgentServer server = new AgentServer(settings(), store, tasks, publisher)) {
            server.start();
            assertEquals(401, get(server, "/v1/tasks", false).statusCode());
            assertEquals(401, postComment(server, event(101, 20, REPO_ID, "@superagent 修复异常"), false).statusCode());
            assertEquals(403, postComment(server, event(101, 21, REPO_ID, "@superagent 修复异常"), true).statusCode());
            assertEquals(403, postComment(server, event(101, 20, 777, "@superagent 修复异常"), true).statusCode());
            assertEquals(200, postComment(server, event(101, 20, REPO_ID, "普通讨论"), true).statusCode());
            String initial = event(101, 20, REPO_ID, "@superagent 修复异常");
            var first = postComment(server, initial, true);
            assertEquals(202, first.statusCode(), first.body());
            String id = Json.parse(first.body()).path("taskId").asText();
            var replay = postComment(server, initial, true); // different unsigned delivery header
            assertTrue(Json.parse(replay.body()).path("duplicate").asBoolean());
            assertEquals(id, Json.parse(replay.body()).path("taskId").asText());
            var follow = postComment(server, event(102, 20, REPO_ID, "@superagent 只修改目标文件"), true);
            assertEquals(id, Json.parse(follow.body()).path("taskId").asText());
            assertEquals(2, tasks.get(id).inputs.size());
            assertEquals(1, tasks.list().size());

            tasks.start();
            status(tasks, id, Task.Status.READY);
            String taskPath = "/v1/tasks/" + id;
            var patch = get(server, taskPath + "/patch", true);
            assertEquals(200, patch.statusCode(), patch.body());
            assertTrue(patch.body().contains("reviewed content"));
            assertEquals(400, operator(server, taskPath + "/publish", "0".repeat(64)).statusCode());
            assertNull(tasks.get(id).publication);
            String reviewed = patch.headers().firstValue("X-Patch-SHA256").orElseThrow();
            var approved = operator(server, taskPath + "/publish", reviewed);
            assertEquals(202, approved.statusCode(), approved.body());
            assertEquals(202, operator(server, taskPath + "/publish", reviewed).statusCode());
            Task task = tasks.get(id);
            assertEquals(Task.Publication.State.QUEUED, task.publication.state);
            assertEquals(0, remote.pushes);
            var content = Processes.run(List.of("git", "show", task.publication.commit + ":generated.txt"),
                    Path.of(task.workspace), temporary.resolve("read-reviewed.log"), Duration.ofSeconds(5), p -> {});
            assertEquals("reviewed content", content.output().strip());
            assertFalse(Files.exists(repo.resolve("generated.txt")));

            remote.base = task.baseCommit;
            remote.dropCreateResponse = true;
            publisher.tick();
            assertEquals(Task.Publication.State.UNCERTAIN, tasks.get(id).publication.state);
            assertEquals(1, remote.pushes);
            assertEquals(1, remote.drafts);
            publisher.tick();
            assertEquals(1, remote.drafts, "Unknown external action must wait for an explicit retry");
            assertEquals(202, operator(server, taskPath + "/retry-publication", reviewed).statusCode());
            publisher.tick();
            assertEquals(Task.Publication.State.PUBLISHED, tasks.get(id).publication.state);
            assertEquals(remote.url, tasks.get(id).publication.url);
            assertEquals(1, remote.pushes);
            assertEquals(1, remote.drafts);
        } finally {
            modelServer.stop(0);
        }
    }

    @Test void restartedServiceRequeuesSafeStepsAndKeepsUnknownExternalActionsInterrupted() throws Exception {
        Path repo = repo(temporary);
        Config config = config(temporary.resolve("state"));
        try (Store store = new Store(config.stateDir())) {
            store.bindService("repo:main");
            assertThrows(IllegalArgumentException.class, store::requireLocal);
            assertThrows(IllegalArgumentException.class, () -> store.bindService("another:main"));
            try (TaskManager initial = new TaskManager(config, store, new DemoModel(), repo, s -> {})) {
                initial.create("safe");
                initial.create("uncertain");
            }
            List<Task> saved = store.list();
            store.update(saved.get(0).id, "simulate_model_crash", t -> t.status = Task.Status.RUNNING);
            store.update(saved.get(1).id, "simulate_shell_crash", t -> {
                t.status = Task.Status.RUNNING;
                Task.Call call = new Task.Call("call_crash", "execute_command", "{}");
                call.phase = Task.Phase.EXECUTING;
                t.tools.add(call);
                t.publication = new Task.Publication();
                t.publication.state = Task.Publication.State.SENDING;
            });
        }
        try (Store store = new Store(config.stateDir());
             TaskManager resumed = new TaskManager(config, store, new DemoModel(), repo, s -> {},
                     (command, cwd, log, timeout, started) -> { throw new AssertionError("Unknown command was replayed"); }, true);
             Publisher publisher = new Publisher(store, new Remote())) {
            List<Task> saved = resumed.list();
            assertEquals(Task.Status.QUEUED, saved.get(0).status);
            assertEquals(Task.Status.INTERRUPTED, saved.get(1).status);
            assertEquals(Task.Phase.UNKNOWN, saved.get(1).outstanding().phase);
            assertEquals(Task.Publication.State.UNCERTAIN, saved.get(1).publication.state);
            resumed.start();
            status(resumed, saved.get(0).id, Task.Status.READY);
        }
    }

    private static ServiceConfig settings() {
        return new ServiceConfig(0, "example/service", REPO_ID, "main", Set.of(20L), SECRET, OPERATOR,
                "local-github-placeholder", "local-sandbox");
    }

    private static String event(long comment, long actor, long repo, String body) {
        var user = Json.object().put("id", actor).put("type", "User");
        var payload = Json.object().put("action", "created");
        payload.set("sender", user);
        payload.set("repository", Json.object().put("id", repo).put("full_name", "example/service"));
        payload.set("issue", Json.object().put("number", 17).put("title", "问题").put("body", "复现步骤"));
        var c = Json.object().put("id", comment).put("body", body);
        c.set("user", user);
        payload.set("comment", c);
        return Json.text(payload);
    }

    private HttpResponse<String> postComment(AgentServer server, String body, boolean valid) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = valid ? HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8))) : "bad";
        return client.send(request(server, "/webhooks/github")
                        .header("X-GitHub-Event", "issue_comment").header("X-GitHub-Delivery", UUID.randomUUID().toString())
                        .header("X-Hub-Signature-256", "sha256=" + signature)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(AgentServer server, String path, boolean authenticated) throws Exception {
        var request = request(server, path);
        if (authenticated) request.header("Authorization", "Bearer " + OPERATOR);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> operator(AgentServer server, String path, String hash) throws Exception {
        return client.send(request(server, path).header("Authorization", "Bearer " + OPERATOR)
                        .POST(HttpRequest.BodyPublishers.ofString(Json.text(Map.of("patchSha256", hash)))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(AgentServer server, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).timeout(Duration.ofSeconds(8));
    }

    private static final class Remote implements Publisher.Remote {
        String base, branch, url;
        boolean dropCreateResponse;
        int pushes, drafts;
        @Override public void verifyRepository() {}
        @Override public String branchCommit(String name) { return name.equals("main") ? base : branch; }
        @Override public boolean baseContains(String ancestor, String head) { return false; }
        @Override public void push(Task task) { pushes++; branch = task.publication.commit; }
        @Override public String existingPull(Task task) { return url; }
        @Override public String openDraft(Task task) throws Exception {
            drafts++;
            url = "https://github.com/example/service/pull/18";
            if (dropCreateResponse) throw new IOException("connection lost after creation");
            return url;
        }
    }
}
