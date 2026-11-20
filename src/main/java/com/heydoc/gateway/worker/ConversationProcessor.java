package com.heydoc.gateway.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.heydoc.gateway.config.LlmProperties;
import com.heydoc.gateway.config.WorkerProperties;
import com.heydoc.gateway.llm.LlmClient;
import com.heydoc.gateway.llm.LlmMessage;
import com.heydoc.gateway.llm.LlmPart;
import com.heydoc.gateway.llm.LlmResponse;
import com.heydoc.gateway.outbox.OutboxService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One inbound message through the Claude tool loop. Every step is persisted as it happens, and no
 * transaction is held across a model or lab call. If the job dies midway, a rerun resumes from the
 * stored history: a saved tool_use with no result gets its tools run (replayed by tool_use_id), and
 * the model is not called again for a turn already stored.
 */
@Component
@Profile("worker")
public class ConversationProcessor {

    static final String HANDOFF_REPLY = "A staff member will contact you shortly.";
    static final String NON_TEXT_REPLY = "Please send your request as a text message.";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final LlmClient llm;
    private final ToolExecutor tools;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final LlmProperties llmProperties;
    private final WorkerProperties workerProperties;
    private final MeterRegistry metrics;

    public ConversationProcessor(JdbcTemplate jdbc, ObjectMapper json, LlmClient llm, ToolExecutor tools,
                                 OutboxService outbox, TransactionTemplate tx, LlmProperties llmProperties,
                                 WorkerProperties workerProperties, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.json = json;
        this.llm = llm;
        this.tools = tools;
        this.outbox = outbox;
        this.tx = tx;
        this.llmProperties = llmProperties;
        this.workerProperties = workerProperties;
        this.metrics = metrics;
    }

    public void process(String waMessageId, String phone, String body) {
        String state = jdbc.queryForObject(
                "SELECT state FROM conversations WHERE phone_e164 = ?", String.class, phone);
        if ("HANDED_OFF".equals(state)) {
            finish(waMessageId, phone, HANDOFF_REPLY);
            return;
        }
        if (body == null) {
            finish(waMessageId, phone, NON_TEXT_REPLY);
            return;
        }
        storeTurn(phone, "user", List.of(new LlmPart.Text(body)), waMessageId);

        for (int iteration = 0; iteration < llmProperties.maxToolIterations(); iteration++) {
            renewLease(waMessageId);
            List<LlmMessage> history = loadHistory(phone);
            LlmMessage last = history.get(history.size() - 1);

            if (last.isAssistant() && last.toolUses().isEmpty()) {
                // Final answer stored by an earlier run that died before the reply was queued.
                finish(waMessageId, phone, last.text());
                return;
            }
            if (last.isAssistant()) {
                // Resume: run the tools of the stored turn that have no results yet.
                List<LlmPart> results = new ArrayList<>();
                boolean handoff = false;
                String handoffReason = "";
                for (LlmPart.ToolUse use : last.toolUses()) {
                    results.add(tools.run(phone, use));
                    if ("handoff_to_human".equals(use.name())) {
                        handoff = true;
                        handoffReason = use.input() == null ? "" : use.input().path("reason").asText("");
                    }
                }
                storeTurn(phone, "user", results, null);
                if (handoff) {
                    handOff(waMessageId, phone, handoffReason);
                    return;
                }
                continue;
            }

            LlmResponse response = llm.complete(history);
            recordCost(phone, waMessageId, response.usage());
            storeTurn(phone, "assistant", response.parts(), null);
            if (response.parts().stream().noneMatch(p -> p instanceof LlmPart.ToolUse)) {
                String text = new LlmMessage("assistant", response.parts()).text();
                finish(waMessageId, phone, text.isBlank() ? HANDOFF_REPLY : text);
                return;
            }
        }
        handOff(waMessageId, phone, "tool_iteration_limit");
    }

    /** Queues the reply and marks the message done in one transaction, so the reply is queued once. */
    private void finish(String waMessageId, String phone, String reply) {
        tx.executeWithoutResult(status -> {
            outbox.enqueue(phone, reply);
            markDone(waMessageId);
        });
    }

    private void handOff(String waMessageId, String phone, String reason) {
        tx.executeWithoutResult(status -> {
            int changed = jdbc.update(
                    "UPDATE conversations SET state = 'HANDED_OFF', updated_at = now() "
                            + "WHERE phone_e164 = ? AND state = 'ACTIVE'", phone);
            if (changed == 1) {
                jdbc.update("INSERT INTO handoffs (phone_e164, reason, created_at) VALUES (?, ?, now())",
                        phone, reason);
                metrics.counter("handoffs_total").increment();
            }
            outbox.enqueue(phone, HANDOFF_REPLY);
            markDone(waMessageId);
        });
    }

    /** Called from the worker when a message has failed maxAttempts times. Runs inside the worker's transaction. */
    public void handOffDead(String phone) {
        int changed = jdbc.update(
                "UPDATE conversations SET state = 'HANDED_OFF', updated_at = now() "
                        + "WHERE phone_e164 = ? AND state = 'ACTIVE'", phone);
        if (changed == 1) {
            jdbc.update("INSERT INTO handoffs (phone_e164, reason, created_at) VALUES (?, 'dead_letter', now())",
                    phone);
        }
        outbox.enqueue(phone, HANDOFF_REPLY);
    }

    private void markDone(String waMessageId) {
        jdbc.update("""
                UPDATE inbound_messages SET status = 'DONE', processed_at = now(), locked_until = NULL
                WHERE wa_message_id = ?
                """, waMessageId);
    }

    private void renewLease(String waMessageId) {
        jdbc.update("""
                UPDATE inbound_messages
                SET locked_until = now() + CAST(? AS int) * interval '1 second'
                WHERE wa_message_id = ?
                """, workerProperties.leaseSeconds(), waMessageId);
    }

    private void storeTurn(String phone, String role, List<LlmPart> parts, String waMessageId) {
        jdbc.update("""
                INSERT INTO messages (phone_e164, role, content, wa_message_id, created_at)
                VALUES (?, ?, ?::jsonb, ?, now())
                ON CONFLICT (wa_message_id) DO NOTHING
                """, phone, role, partsToJson(parts).toString(), waMessageId);
    }

    private void recordCost(String phone, String waMessageId, LlmResponse.Usage usage) {
        LlmProperties.Pricing price = llmProperties.pricing();
        double cost = (usage.inputTokens() * price.inputPerMtok()
                + usage.outputTokens() * price.outputPerMtok()
                + usage.cacheReadTokens() * price.cacheReadPerMtok()
                + usage.cacheWriteTokens() * price.cacheWritePerMtok()) / 1_000_000.0;
        jdbc.update("""
                INSERT INTO llm_calls (phone_e164, wa_message_id, model, input_tokens, output_tokens,
                                       cache_read_tokens, cache_write_tokens, cost_usd, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                """, phone, waMessageId, llmProperties.model(), usage.inputTokens(), usage.outputTokens(),
                usage.cacheReadTokens(), usage.cacheWriteTokens(), cost);
        metrics.counter("llm_tokens_total", "type", "input").increment(usage.inputTokens());
        metrics.counter("llm_tokens_total", "type", "output").increment(usage.outputTokens());
        metrics.counter("llm_tokens_total", "type", "cache_read").increment(usage.cacheReadTokens());
        metrics.counter("llm_tokens_total", "type", "cache_write").increment(usage.cacheWriteTokens());
        metrics.counter("llm_cost_usd_total", "model", llmProperties.model()).increment(cost);
    }

    /** Last N rows, oldest first, starting at a plain user message (never an orphaned tool result). */
    private List<LlmMessage> loadHistory(String phone) {
        List<LlmMessage> rows = new ArrayList<>(jdbc.query("""
                SELECT role, content::text AS content FROM messages
                WHERE phone_e164 = ? ORDER BY id DESC LIMIT ?
                """, (rs, n) -> new LlmMessage(rs.getString("role"), partsFromJson(rs.getString("content"))),
                phone, workerProperties.historyRows()));
        Collections.reverse(rows);
        int start = 0;
        while (start < rows.size() && !(rows.get(start).isUser() && rows.get(start).isTextOnly())) {
            start++;
        }
        if (start >= rows.size()) {
            throw new IllegalStateException("no user message in history for " + phone);
        }
        return new ArrayList<>(rows.subList(start, rows.size()));
    }

    private ArrayNode partsToJson(List<LlmPart> parts) {
        ArrayNode array = json.createArrayNode();
        for (LlmPart part : parts) {
            ObjectNode node = json.createObjectNode();
            if (part instanceof LlmPart.Text text) {
                node.put("type", "text").put("text", text.text());
            } else if (part instanceof LlmPart.ToolUse use) {
                node.put("type", "tool_use").put("id", use.id()).put("name", use.name());
                node.set("input", use.input() == null ? json.createObjectNode() : use.input());
            } else if (part instanceof LlmPart.ToolResult result) {
                node.put("type", "tool_result")
                        .put("tool_use_id", result.toolUseId())
                        .put("content", result.content())
                        .put("is_error", result.isError());
            }
            array.add(node);
        }
        return array;
    }

    private List<LlmPart> partsFromJson(String content) {
        List<LlmPart> parts = new ArrayList<>();
        try {
            JsonNode array = json.readTree(content);
            for (JsonNode node : array) {
                switch (node.path("type").asText()) {
                    case "text" -> parts.add(new LlmPart.Text(node.path("text").asText()));
                    case "tool_use" -> parts.add(new LlmPart.ToolUse(
                            node.path("id").asText(), node.path("name").asText(), node.get("input")));
                    case "tool_result" -> parts.add(new LlmPart.ToolResult(
                            node.path("tool_use_id").asText(), node.path("content").asText(),
                            node.path("is_error").asBoolean(false)));
                    default -> throw new IllegalStateException("unknown stored part: " + node);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("corrupt stored message", e);
        }
        return parts;
    }
}
