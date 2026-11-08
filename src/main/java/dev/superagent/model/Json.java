package dev.superagent.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private Json() {}
    public static ObjectNode object() { return MAPPER.createObjectNode(); }
    public static ArrayNode array() { return MAPPER.createArrayNode(); }
    public static JsonNode parse(String value) {
        try { return MAPPER.readTree(value); }
        catch (Exception e) { throw new IllegalArgumentException("无效 JSON: " + e.getMessage(), e); }
    }
    public static String text(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static ObjectNode message(String role, String content) {
        return object().put("role", role).put("content", content);
    }
}
