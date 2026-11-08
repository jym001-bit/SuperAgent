package dev.superagent.model;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重试策略的行为验收：只重试「请求确定没被处理」的情况，其余一律不重试。
 *
 * <p>这些用例用真实本地 HTTP 服务与真实连接失败，删掉重试逻辑或放宽分类都会失败。
 */
class ModelRetryTest {
    @TempDir Path temporary;
    private HttpServer server;

    @BeforeEach void fastBackoff() {
        System.setProperty("superagent.retryBaseMillis", "5");
        System.clearProperty("superagent.modelRetries");
    }

    @AfterEach void cleanup() {
        System.clearProperty("superagent.retryBaseMillis");
        System.clearProperty("superagent.modelRetries");
        if (server != null) server.stop(0);
    }

    @Test void transientServiceUnavailableIsRetriedUntilSuccess() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        start(exchange -> {
            if (hits.incrementAndGet() <= 2) {
                reply(exchange, 503, "busy");
            } else {
                reply(exchange, 200, completion("ok", 7));
            }
        });
        ModelClient.Reply reply = client().complete(List.of(Json.message("user", "hi")), null);
        assertEquals("ok", reply.message().path("content").asText());
        assertEquals(3, hits.get(), "503 属于「未处理」，必须重试到成功");
    }

    @Test void retryAfterIsHonored() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        start(exchange -> {
            if (hits.incrementAndGet() == 1) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                reply(exchange, 429, "slow down");
            } else {
                reply(exchange, 200, completion("ok", 3));
            }
        });
        assertEquals("ok", client().complete(List.of(Json.message("user", "hi")), null)
                .message().path("content").asText());
        assertEquals(2, hits.get());
    }

    @Test void clientErrorsAreNotRetried() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        start(exchange -> { hits.incrementAndGet(); reply(exchange, 400, "{\"error\":\"bad model\"}"); });
        ModelUnavailable failure = assertThrows(ModelUnavailable.class,
                () -> client().complete(List.of(Json.message("user", "hi")), null));
        assertEquals(1, hits.get(), "4xx 是配置错误，重试只会浪费额度");
        assertFalse(failure.retryable());
        assertEquals(1, failure.attempts());
        assertTrue(failure.getMessage().contains("HTTP 400"), failure.getMessage());
        assertTrue(failure.getMessage().contains("bad model"), "错误体要带出来，否则排查只能猜");
    }

    @Test void requestTimeoutIsNotRetriedBecauseTheResultIsUnknown() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        start(exchange -> {
            hits.incrementAndGet();
            try { Thread.sleep(1_500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            reply(exchange, 200, completion("late", 1));
        });
        Config config = config(baseUrl(), Duration.ofMillis(400));
        ModelUnavailable failure = assertThrows(ModelUnavailable.class,
                () -> new HttpModelClient(config).complete(List.of(Json.message("user", "hi")), null));
        assertEquals(1, hits.get(), "超时可能已经计费，不能自动重发");
        assertFalse(failure.retryable());
        assertTrue(failure.getMessage().contains("请求超时"), failure.getMessage());
    }

    @Test void connectionFailureIsRetriedAndExplained() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Config config = config("http://127.0.0.1:" + port, Duration.ofSeconds(2));
        ModelUnavailable failure = assertThrows(ModelUnavailable.class,
                () -> new HttpModelClient(config).complete(List.of(Json.message("user", "hi")), null));
        assertFalse(failure.retryable(), "这是最终失败，不该再标记为可重试");
        assertEquals(3, failure.attempts(), "默认重试 2 次，共 3 次尝试");
        assertTrue(failure.getMessage().contains("连接失败"), failure.getMessage());
        assertTrue(failure.getMessage().contains("先后尝试 3 次"), failure.getMessage());
        assertTrue(failure.getMessage().contains("127.0.0.1"), "错误信息必须能看出打到哪: " + failure.getMessage());
    }

    @Test void retryCountIsConfigurable() throws Exception {
        System.setProperty("superagent.modelRetries", "0");
        AtomicInteger hits = new AtomicInteger();
        start(exchange -> { hits.incrementAndGet(); reply(exchange, 503, "busy"); });
        assertThrows(ModelUnavailable.class,
                () -> client().complete(List.of(Json.message("user", "hi")), null));
        assertEquals(1, hits.get(), "重试次数配成 0 时只允许一次请求");
    }

    private ModelClient client() throws Exception { return new HttpModelClient(config(baseUrl(), Duration.ofSeconds(5))); }

    private Config config(String baseUrl, Duration httpTimeout) throws Exception {
        return new Config(temporary.resolve("state"), baseUrl, "mock", "test-key", "standard", false,
                1, 30, 100_000, Duration.ofSeconds(5), httpTimeout);
    }

    private String baseUrl() throws IOException {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", handler);
        server.start();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private static String completion(String content, long tokens) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content
                + "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"total_tokens\":" + tokens + "}}";
    }
}
