package dev.superagent.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.superagent.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.*;
import java.net.http.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import static dev.superagent.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalFrameworkTest {
    @TempDir Path temp;
    private final String operatorToken = UUID.randomUUID().toString() + UUID.randomUUID();
    private final HttpClient client = HttpClient.newHttpClient();
    private int port;

    @Test void launchesLocalServiceAndDrivesPlanSingleAndAutoThroughHttp() throws Exception {
        Path repo = repo(temp);
        HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/chat/completions", request -> {
            try (request) {
                JsonNode payload = Json.parse(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                JsonNode messages = payload.path("messages");
                String system = messages.path(0).path("content").asText();
                var tools = payload.path("tools");
                ModelClient.Reply response;
                if (system.contains("调度者")) {
                    response = answer(Json.text(Map.of("decision", "plan", "text", "Auto goal")));
                } else if (system.contains("规划器")) {
                    response = answer("""
                            {"nodes":[{"key":"file","goal":"Create hello file","acceptance":"hello.txt exists","dependencies":[]}]}
                            """);
                } else {
                    boolean hostShellAdvertised = false;
                    for (JsonNode tool : tools) {
                        if ("execute_command".equals(tool.path("function").path("name").asText())) hostShellAdvertised = true;
                    }
                    String lastTool = "";
                    for (JsonNode message : messages) {
                        if ("tool".equals(message.path("role").asText())) lastTool = message.path("content").asText();
                    }
                    if (hostShellAdvertised) response = answer("unexpected host shell tool");
                    else if (lastTool.isEmpty() && messages.toString().contains("Try shell"))
                        response = call("execute_command", Json.object().put("command", markerCommand()));
                    else if (lastTool.isEmpty()) response = call("write_file",
                            Json.object().put("path", "hello.txt").put("content", "hello from local service\n"));
                    else response = answer(lastTool);
                }
                var choice = Json.object().put("finish_reason", response.finishReason());
                choice.set("message", response.message());
                var result = Json.object();
                result.set("choices", Json.array().add(choice));
                byte[] bytes = Json.text(result).getBytes(StandardCharsets.UTF_8);
                request.sendResponseHeaders(200, bytes.length);
                request.getResponseBody().write(bytes);
            }
        });
        model.start();
        Process process = null;
        Thread outputReader = null;
        try {
            var builder = new ProcessBuilder(javaExecutable(), "-cp", classpath(), "dev.superagent.cli.Main",
                    "--serve-local", "--repo", repo.toString());
            builder.redirectErrorStream(true);
            builder.environment().putAll(Map.of(
                    "SUPERAGENT_BASE_URL", "http://127.0.0.1:" + model.getAddress().getPort(),
                    "SUPERAGENT_API_KEY", "local-model-placeholder",
                    "SUPERAGENT_PROVIDER", "standard",
                    "SUPERAGENT_MODEL", "fixture",
                    "SUPERAGENT_OPERATOR_TOKEN", operatorToken,
                    "SUPERAGENT_PORT", "0",
                    "SUPERAGENT_STATE_DIR", temp.resolve("state").toString()));
            process = builder.start();
            Process running = process;
            var readyPort = new CompletableFuture<Integer>();
            outputReader = new Thread(() -> readOutput(running, readyPort), "local-service-test-output");
            outputReader.setDaemon(true);
            outputReader.start();
            port = readyPort.get(15, TimeUnit.SECONDS);
            assertEquals(200, request("GET", "/readyz", null, false).statusCode());
            assertEquals(401, request("GET", "/v1/tasks", null, false).statusCode());
            assertEquals(400, request("POST", "/v1/tasks", Map.of("mode", "invalid", "text", "test"), true).statusCode());
            var submission = request("POST", "/v1/tasks", Map.of("mode", "plan", "text", "Add hello"), true);
            assertEquals(202, submission.statusCode(), submission.body());
            String id = Json.parse(submission.body()).path("id").asText();
            String task = "/v1/tasks/" + id;
            JsonNode pending = state(task);
            assertEquals("PLAN_READY", pending.path("status").asText());
            assertEquals(1, pending.path("plan").size());
            assertTrue(pending.path("children").isEmpty());
            assertEquals(202, request("POST", task + "/start", null, true).statusCode());
            await(() -> hasStatus(task, "READY"));
            var patch = request("GET", task + "/patch", null, true);
            assertEquals(200, patch.statusCode(), patch.body());
            assertTrue(patch.body().contains("+hello from local service"));
            assertEquals(state(task).path("patchSha256").asText(), patch.headers().firstValue("X-Patch-SHA256").orElseThrow());
            assertEquals(202, request("POST", task + "/accept", null, true).statusCode());
            assertEquals("COMPLETED", state(task).path("status").asText());
            assertFalse(Files.exists(repo.resolve("hello.txt")));

            var automatic = request("POST", "/v1/tasks", Map.of("mode", "auto", "text", "Split another job"), true);
            assertEquals(202, automatic.statusCode(), automatic.body());
            JsonNode outcome = Json.parse(automatic.body());
            assertEquals("plan", outcome.path("decision").asText());
            assertEquals("PLAN_READY", state("/v1/tasks/" + outcome.path("taskId").asText()).path("status").asText());

            var simple = request("POST", "/v1/tasks", Map.of("mode", "single", "text", "Try shell"), true);
            assertEquals(202, simple.statusCode(), simple.body());
            String single = "/v1/tasks/" + Json.parse(simple.body()).path("id").asText();
            await(() -> hasStatus(single, "READY"));
            JsonNode singleState = state(single);
            assertTrue(singleState.path("result").asText().contains("关闭命令执行"));
            assertFalse(Files.exists(Path.of(singleState.path("workspace").asText(), "marker.txt")));
            assertTrue(Json.parse(request("GET", "/v1/approvals", null, true).body()).isEmpty());
            assertEquals(404, request("POST", "/webhooks/github", Map.of(), false).statusCode());
        } finally {
            if (process != null) {
                process.destroy();
                if (!process.waitFor(6, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(6, TimeUnit.SECONDS);
                }
                process.getInputStream().close();
            }
            if (outputReader != null) outputReader.join(2000);
            model.stop(0);
        }
    }

    private static void readOutput(Process process, CompletableFuture<Integer> readyPort) {
        var address = Pattern.compile("127\\.0\\.0\\.1:(\\d+)");
        StringBuilder startup = new StringBuilder();
        try (var lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = lines.readLine()) != null;) {
                if (readyPort.isDone()) continue;
                startup.append(line).append('\n');
                var matcher = address.matcher(line);
                if (matcher.find()) readyPort.complete(Integer.parseInt(matcher.group(1)));
            }
            readyPort.completeExceptionally(new IllegalStateException("本地服务未启动: " + startup));
        } catch (Exception error) {
            readyPort.completeExceptionally(error);
        }
    }

    private JsonNode state(String path) throws Exception {
        return Json.parse(request("GET", path, null, true).body());
    }

    private boolean hasStatus(String path, String status) {
        try { return status.equals(state(path).path("status").asText()); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private HttpResponse<String> request(String method, String path, Object payload, boolean authorized) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(8));
        if (authorized) builder.header("Authorization", "Bearer " + operatorToken);
        return client.send(builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(Json.text(payload))).build(), HttpResponse.BodyHandlers.ofString());
    }
}
