package com.heydoc.gateway.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One content part of a conversation turn. Stored as JSON in messages.content, so a turn can be
 * replayed after a crash without calling the model again.
 */
public sealed interface LlmPart permits LlmPart.Text, LlmPart.ToolUse, LlmPart.ToolResult {

    record Text(String text) implements LlmPart {
    }

    record ToolUse(String id, String name, JsonNode input) implements LlmPart {
    }

    record ToolResult(String toolUseId, String content, boolean isError) implements LlmPart {
    }
}
