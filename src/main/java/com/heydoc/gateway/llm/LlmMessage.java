package com.heydoc.gateway.llm;

import java.util.List;

/** One turn of history. role is "user" or "assistant". */
public record LlmMessage(String role, List<LlmPart> parts) {

    public boolean isUser() {
        return "user".equals(role);
    }

    public boolean isAssistant() {
        return "assistant".equals(role);
    }

    public List<LlmPart.ToolUse> toolUses() {
        return parts.stream()
                .filter(p -> p instanceof LlmPart.ToolUse)
                .map(p -> (LlmPart.ToolUse) p)
                .toList();
    }

    public boolean hasToolResults() {
        return parts.stream().anyMatch(p -> p instanceof LlmPart.ToolResult);
    }

    public boolean isTextOnly() {
        return parts.stream().allMatch(p -> p instanceof LlmPart.Text);
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        for (LlmPart p : parts) {
            if (p instanceof LlmPart.Text t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }
}
