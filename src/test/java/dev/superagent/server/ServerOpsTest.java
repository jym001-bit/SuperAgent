package dev.superagent.server;

import dev.superagent.TestSupport;
import dev.superagent.model.DemoModel;
import dev.superagent.router.Router;
import dev.superagent.storage.Store;
import dev.superagent.task.Task;
import dev.superagent.task.TaskManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 可观测性契约：指标数值必须与真实数据库一致，审计必须可过滤且要求身份，成本视图必须来自真实计数。
 * 这些断言在指标写错或审计接口漏掉鉴权时会失败。
 */
class ServerOpsTest {
    @TempDir Path temporary;
    private static final String TOKEN = "t".repeat(32);

    @Test void metricsAuditAndCostsReflectRealState() throws Exception {
        Path state = temporary.resolve("state");
        var config = TestSupport.config(state);
        Path repo = TestSupport.repo(temporary);
        HttpClient client = HttpClient.newHttpClient();
        try (Store store = new Store(state)) {
            try (TaskManager tasks = new TaskManager(config, store, new DemoModel(), repo, s -> {})) {
                tasks.create("第一个任务");
                tasks.create("第二个任务");
                try (AgentServer server = new AgentServer(0, TOKEN, store, tasks,
                        new Router(store, tasks, new DemoModel()))) {
                    server.start();
                    String base = "http://127.0.0.1:" + server.port();

                    HttpResponse<String> metrics = get(client, base + "/metrics");
                    assertEquals(200, metrics.statusCode());
                    String body = metrics.body();
                    assertTrue(body.contains("superagent_tasks{status=\"QUEUED\"} 2"), body);
                    assertTrue(body.contains("superagent_queue_depth 2"), body);
                    assertTrue(body.contains("superagent_schema_version 2"), body);
                    assertTrue(body.contains("superagent_events_total{kind=\"created\"} 2"), body);
                    assertTrue(body.contains("superagent_tasks_total 2"), body);

                    assertEquals(401, get(client, base + "/v1/audit").statusCode(), "审计接口必须要求运维身份");

                    List<Task> all = tasks.list();
                    String taskId = all.get(0).id;
                    HttpResponse<String> audit = authorized(client,
                            base + "/v1/audit?taskId=" + taskId + "&limit=10");
                    assertEquals(200, audit.statusCode(), audit.body());
                    assertTrue(audit.body().contains("\"kind\":\"created\""), audit.body());
                    assertFalse(audit.body().contains(all.get(1).id), "按任务过滤必须生效: " + audit.body());

                    HttpResponse<String> badCursor = authorized(client, base + "/v1/audit?after=abc");
                    assertEquals(400, badCursor.statusCode(), "非法游标必须被拒绝，而不是静默忽略");

                    HttpResponse<String> costs = authorized(client, base + "/v1/costs");
                    assertEquals(200, costs.statusCode(), costs.body());
                    assertTrue(costs.body().contains("\"modelCalls\":0"), costs.body());
                    assertTrue(costs.body().contains("\"tokens\":0"), costs.body());

                    assertEquals(401, get(client, base + "/v1/verify").statusCode(), "自检接口必须要求运维身份");
                    HttpResponse<String> verify = authorized(client, base + "/v1/verify");
                    assertEquals(200, verify.statusCode(), verify.body());
                    assertTrue(verify.body().contains("\"healthy\":true"), verify.body());
                    assertTrue(verify.body().contains("\"name\":\"schema_version\""), verify.body());
                }
            }
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> authorized(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer " + TOKEN).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
