package dev.superagent.model;

import com.fasterxml.jackson.databind.node.*;
import java.util.List;

@FunctionalInterface
public interface ModelClient {
    Reply complete(List<ObjectNode> messages, ArrayNode tools) throws Exception;
    record Reply(ObjectNode message, long tokens, String finishReason) {}
}
