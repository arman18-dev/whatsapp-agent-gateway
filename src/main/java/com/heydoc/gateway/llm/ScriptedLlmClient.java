package com.heydoc.gateway.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.heydoc.gateway.config.LlmProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Deterministic stand-in for Claude, used when {@code app.llm.mode=mock} (tests, load runs without spend).
 * It follows the same tool protocol as the real model, so the worker code path is the same.
 */
@Component
@Profile("worker")
@ConditionalOnProperty(prefix = "app.llm", name = "mode", havingValue = "mock", matchIfMissing = true)
public class ScriptedLlmClient implements LlmClient {

    private final ObjectMapper json;
    private final boolean spread;

    public ScriptedLlmClient(ObjectMapper json, LlmProperties properties) {
        this.json = json;
        this.spread = properties.scriptedSpread();
    }

    @Override
    public LlmResponse complete(List<LlmMessage> history) {
        LlmMessage last = history.get(history.size() - 1);
        if (last.isUser() && last.hasToolResults()) {
            return afterToolResults(history, last);
        }
        String text = last.text().toLowerCase();
        if (text.contains("human") || text.contains("staff") || text.contains("handoff")) {
            return toolUse("handoff_to_human", json.createObjectNode().put("reason", "patient asked for a person"));
        }
        if (text.contains("book") || text.contains("test")) {
            // Spread mode asks for a random day in the next 60, so requests land on many different slots.
            int offset = spread ? java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 61) : 1;
            ObjectNode input = json.createObjectNode()
                    .put("test_code", "THYROID")
                    .put("date_from", LocalDate.now().plusDays(offset).toString())
                    .put("date_to", LocalDate.now().plusDays(offset + 2).toString());
            return toolUse("check_slots", input);
        }
        return text("Hello! Which test would you like to book?");
    }

    private LlmResponse afterToolResults(List<LlmMessage> history, LlmMessage last) {
        LlmPart.ToolResult result = (LlmPart.ToolResult) last.parts().get(0);
        String toolName = findToolName(history, result.toolUseId());
        JsonNode content = parseOrNull(result.content());
        if ("check_slots".equals(toolName)) {
            JsonNode slots = content == null ? null : content.path("slots");
            if (slots != null && slots.isArray() && !slots.isEmpty()) {
                int index = spread ? java.util.concurrent.ThreadLocalRandom.current().nextInt(slots.size()) : 0;
                ObjectNode input = json.createObjectNode()
                        .put("slot_id", slots.get(index).path("slot_id").asText())
                        .put("test_code", "THYROID")
                        .put("patient_name", "Synthetic Patient");
                return toolUse("book_test", input);
            }
            return text("Sorry, there are no free slots in that range.");
        }
        if ("book_test".equals(toolName)) {
            String status = content == null ? "" : content.path("status").asText();
            return switch (status) {
                case "CONFIRMED" -> text("Your test is booked. Reference: " + content.path("booking_ref").asText());
                case "PENDING_CONFIRMATION" -> text("We are confirming your booking; you will hear back shortly.");
                case "SLOT_TAKEN" -> text("Sorry, that slot was just taken. Would you like other times?");
                default -> text("We could not book that. A staff member will help you.");
            };
        }
        return text("A staff member will contact you.");
    }

    private static String findToolName(List<LlmMessage> history, String toolUseId) {
        for (int i = history.size() - 2; i >= 0; i--) {
            for (LlmPart.ToolUse use : history.get(i).toolUses()) {
                if (use.id().equals(toolUseId)) {
                    return use.name();
                }
            }
        }
        return "";
    }

    private JsonNode parseOrNull(String value) {
        try {
            return json.readTree(value);
        } catch (Exception e) {
            return null;
        }
    }

    private LlmResponse toolUse(String name, JsonNode input) {
        LlmPart.ToolUse use = new LlmPart.ToolUse("toolu_" + UUID.randomUUID(), name, input);
        return new LlmResponse(List.of(use), "tool_use", LlmResponse.Usage.NONE);
    }

    private static LlmResponse text(String value) {
        return new LlmResponse(List.of(new LlmPart.Text(value)), "end_turn", LlmResponse.Usage.NONE);
    }
}
