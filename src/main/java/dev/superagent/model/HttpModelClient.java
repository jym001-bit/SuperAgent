package dev.superagent.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.net.http.*;
import java.util.List;

public final class HttpModelClient implements ModelClient {
    private final Config config;
    private final HttpClient client;
    public HttpModelClient(Config config) {
        this.config=config;
        client=HttpClient.newBuilder().connectTimeout(config.httpTimeout()).build();
    }
    @Override public Reply complete(List<ObjectNode> messages, ArrayNode tools) throws Exception {
        if(config.apiKey().isBlank()) throw new IllegalStateException("请设置 SUPERAGENT_API_KEY 或 DEEPSEEK_API_KEY");
        ObjectNode request=Json.object().put("model",config.model()).put("stream",false).put("max_tokens",4096);
        request.set("messages",Json.MAPPER.valueToTree(messages));
        if(tools != null && !tools.isEmpty()) request.set("tools",tools);
        if(config.provider().equals("deepseek")) {
            request.set("thinking",Json.object().put("type",config.thinking()?"enabled":"disabled"));
            if(config.thinking()) request.put("reasoning_effort","high");
        }
        String base=config.baseUrl().replaceAll("/+$","");
        URI uri=URI.create(base.endsWith("/chat/completions")?base:base+"/chat/completions");
        HttpRequest http=HttpRequest.newBuilder(uri).timeout(config.httpTimeout())
                .header("Authorization","Bearer "+config.apiKey()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.text(request))).build();
        // No automatic retry: cancellation interrupts send(), including an in-flight HTTP request.
        HttpResponse<String> response=client.send(http,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()/100 != 2)
            throw new IllegalStateException("模型 HTTP " + response.statusCode() + "（检查接口配置；任务未自动重试）");
        JsonNode body=Json.parse(response.body());
        JsonNode choice=body.path("choices").path(0), message=choice.path("message");
        if(!message.isObject() || !message.path("role").asText().equals("assistant"))
            throw new IllegalStateException("模型响应缺少 assistant message");
        ObjectNode normalized=Json.object().put("role","assistant");
        for(String field:List.of("content","reasoning_content","tool_calls"))
            if(message.has(field)) normalized.set(field,message.get(field));
        long tokens=body.path("usage").path("total_tokens").asLong(0);
        if(tokens <= 0) tokens=estimate(Json.text(messages))+estimate(Json.text(normalized));
        return new Reply(normalized,tokens,choice.path("finish_reason").asText());
    }
    /** Conservative accounting fallback for endpoints that omit usage. */
    private static long estimate(String value) { return Math.max(1,value.length()); }
}
