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
            // A day the patient named is used as asked. Otherwise spread mode picks a random day in the next 60.
            String named = findIn(history, DAY);
            String from;
            String to;
            if (named != null) {
                from = named;
                to = named;
            } else {
                int offset = spread ? java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 61) : 1;
                from = LocalDate.now().plusDays(offset).toString();
                to = LocalDate.now().plusDays(offset + 2).toString();
            }
            ObjectNode input = json.createObjectNode()
                    .put("test_code", "THYROID")
                    .put("date_from", from)
                    .put("date_to", to);
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
                // Prefer the time the patient named; otherwise the first slot (or a random one in spread mode).
                String time = findIn(history, TIME);
                int index = 0;
                if (time != null) {
                    // Book only the time the patient asked for. If it is not offered, say so.
                    int found = -1;
                    for (int i = 0; i < slots.size(); i++) {
                        if (slots.get(i).path("starts_at").asText().contains("T" + time)) {
                            found = i;
                            break;
                        }
                    }
                    if (found < 0) {
                        return text("I'm sorry, there is no slot at " + time
                                + " that day. Would you like one of the other times?");
                    }
                    index = found;
                } else if (spread) {
                    index = java.util.concurrent.ThreadLocalRandom.current().nextInt(slots.size());
                }
                String name = findIn(history, NAME);
                ObjectNode input = json.createObjectNode()
                        .put("slot_id", slots.get(index).path("slot_id").asText())
                        .put("test_code", "THYROID")
                        .put("patient_name", name == null ? "Synthetic Patient" : name);
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

    private static final java.util.regex.Pattern DAY = java.util.regex.Pattern.compile("on (\\d{4}-\\d{2}-\\d{2})");
    private static final java.util.regex.Pattern TIME = java.util.regex.Pattern.compile("at (\\d{2}:\\d{2})");
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("My name is ([A-Za-z][A-Za-z ]*[A-Za-z])\\.");

    /** The most recent patient text that matches the pattern, or null. */
    private static String findIn(List<LlmMessage> history, java.util.regex.Pattern pattern) {
        for (int i = history.size() - 1; i >= 0; i--) {
            LlmMessage message = history.get(i);
            if (message.isUser() && message.isTextOnly()) {
                java.util.regex.Matcher matcher = pattern.matcher(message.text());
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        return null;
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
