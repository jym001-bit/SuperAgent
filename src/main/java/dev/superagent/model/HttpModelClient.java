package dev.superagent.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;

/**
 * 真实的 Chat Completions 客户端。
 *
 * <p>重试策略是有意写窄的：只重试「请求确定没有被服务端处理」的情况（连接失败、连接超时、
 * HTTP 429/503），因为这些重试不会造成重复计费。请求超时、响应中断、5xx 一律不重试，
 * 任务转为 FAILED 并保留现场，由用户检查后用 /resume 恢复。
 *
 * <p>取消语义保持：中断线程会立刻打断 send() 与重试等待，不会被重试吞掉。
 */
public final class HttpModelClient implements ModelClient {
    private static final String RETRY_BASE_PROPERTY = "superagent.retryBaseMillis";
    private static final long RETRY_BASE_DEFAULT = 600;
    private static final long RETRY_CAP = 8_000;
    private static final long RETRY_AFTER_CAP = 30_000;
    private static final int BODY_HINT = 300;

    private final Config config;
    private final HttpClient client;
    private final String endpoint;

    public HttpModelClient(Config config) {
        this.config = config;
        this.client = HttpClient.newBuilder().connectTimeout(config.modelConnectTimeout()).build();
        this.endpoint = endpoint(config.baseUrl());
    }

    @Override public Reply complete(List<ObjectNode> messages, ArrayNode tools) throws Exception {
        if (config.apiKey().isBlank()) throw new IllegalStateException("请设置 SUPERAGENT_API_KEY 或 DEEPSEEK_API_KEY");
        ObjectNode request = Json.object().put("model", config.model()).put("stream", false).put("max_tokens", 4096);
        request.set("messages", Json.MAPPER.valueToTree(messages));
        if (tools != null && !tools.isEmpty()) request.set("tools", tools);
        if (config.provider().equals("deepseek")) {
            request.set("thinking", Json.object().put("type", config.thinking() ? "enabled" : "disabled"));
            if (config.thinking()) request.put("reasoning_effort", "high");
        }
        URI uri = URI.create(chatEndpoint(config.baseUrl()));
        String payload = Json.text(request);
        HttpRequest http = HttpRequest.newBuilder(uri).timeout(config.httpTimeout())
                .header("Authorization", "Bearer " + config.apiKey()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();

        int allowed = 1 + config.modelRetries();
        for (int attempt = 1; ; attempt++) {
            try {
                return read(http, payload);
            } catch (ModelUnavailable failure) {
                if (!failure.retryable() || attempt >= allowed)
                    throw new ModelUnavailable(failure.endpoint(), failure.reason(),
                            false, failure.retryAfterMillis(), attempt, failure.getCause());
                Thread.sleep(backoffMillis(attempt, failure.retryAfterMillis()));
            }
        }
    }

    private Reply read(HttpRequest http, String payload) throws InterruptedException {
        HttpResponse<String> response;
        try {
            response = client.send(http, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (HttpConnectTimeoutException error) {
            // 连接都没建立，请求一定没送达：可以安全重试。
            throw new ModelUnavailable(endpoint, "建立连接超时，请求未送达", true, 0, 1, error);
        } catch (HttpTimeoutException error) {
            // 请求已经发出，结果不明；重试可能重复计费。
            throw new ModelUnavailable(endpoint, "请求超时，结果不明；为避免重复计费不自动重试", false, 0, 1, error);
        } catch (java.io.IOException error) {
            boolean connect = isConnectFailure(error);
            throw new ModelUnavailable(endpoint,
                    connect ? "连接失败，请求未送达" : "传输中断，结果不明；不自动重试",
                    connect, 0, 1, error);
        }

        int status = response.statusCode();
        if (status == 429 || status == 503) {
            throw new ModelUnavailable(endpoint, "服务端暂时不可用（HTTP " + status + "）" + hint(response.body()),
                    true, retryAfterMillis(response), 1, null);
        }
        if (status / 100 != 2) {
            throw new ModelUnavailable(endpoint,
                    "HTTP " + status + "（检查接口地址、模型名、Key 与额度）" + hint(response.body()),
                    false, 0, 1, null);
        }
        JsonNode body = Json.parse(response.body());
        JsonNode choice = body.path("choices").path(0), message = choice.path("message");
        if (!message.isObject() || !message.path("role").asText().equals("assistant"))
            throw new ModelUnavailable(endpoint, "模型响应缺少 assistant message", false, 0, 1, null);
        ObjectNode normalized = Json.object().put("role", "assistant");
        for (String field : List.of("content", "reasoning_content", "tool_calls"))
            if (message.has(field)) normalized.set(field, message.get(field));
        long tokens = body.path("usage").path("total_tokens").asLong(0);
        if (tokens <= 0) tokens = estimate(payload) + estimate(Json.text(normalized));
        return new Reply(normalized, tokens, choice.path("finish_reason").asText());
    }

    private static boolean isConnectFailure(Throwable error) {
        java.util.Set<Throwable> seen = new java.util.HashSet<>();
        for (Throwable cursor = error; cursor != null && seen.add(cursor); cursor = cursor.getCause())
            if (cursor instanceof java.net.ConnectException || cursor instanceof java.net.NoRouteToHostException)
                return true;
        return false;
    }

    private static long retryAfterMillis(HttpResponse<String> response) {
        String value = response.headers().firstValue("Retry-After").orElse("");
        try { return value.isBlank() ? 0 : Math.max(0, Long.parseLong(value.strip()) * 1000); }
        catch (NumberFormatException ignored) { return 0; } // HTTP 日期形式不解析，退回指数退避
    }

    private static long backoffMillis(int attempt, long retryAfterMillis) {
        long base;
        try { base = Math.max(0, Long.parseLong(System.getProperty(RETRY_BASE_PROPERTY, String.valueOf(RETRY_BASE_DEFAULT)).strip())); }
        catch (NumberFormatException ignored) { base = RETRY_BASE_DEFAULT; }
        long exponential = Math.min(RETRY_CAP, base << Math.min(6, Math.max(0, attempt - 1)));
        long honored = Math.min(RETRY_AFTER_CAP, retryAfterMillis);
        return Math.max(exponential, honored);
    }

    private static String hint(String body) {
        if (body == null) return "";
        String flat = body.replace('\n', ' ').strip();
        if (flat.isEmpty()) return "";
        return "：" + (flat.length() <= BODY_HINT ? flat : flat.substring(0, BODY_HINT) + "…");
    }

    private static String chatEndpoint(String baseUrl) {
        String base = baseUrl.replaceAll("/+$", "");
        return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
    }

    private static String endpoint(String baseUrl) {
        try {
            URI uri = URI.create(chatEndpoint(baseUrl));
            String host = uri.getHost();
            return (host == null ? "" : host) + uri.getPath();
        } catch (RuntimeException ignored) {
            return baseUrl;
        }
    }

    /** Conservative accounting fallback for endpoints that omit usage. */
    private static long estimate(String value) { return Math.max(1, value.length()); }
}
