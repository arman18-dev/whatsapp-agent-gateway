package com.heydoc.gateway.ingest;

import com.heydoc.gateway.webhook.InboundMessage;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order of work for each message (PLAN.md section 4, design review):
 * 1. dedupe on wa_message_id (a redelivery never uses rate-limit quota);
 * 2. only for a new row, count it against the phone's rate limit;
 * 3. over the limit, store the row as RATE_LIMITED: silent, no LLM call.
 */
@Service
@Profile("gateway")
public class IngestService {

    /**
     * One round trip per message: make sure the conversation exists and insert the inbound row.
     * Returns 1 when the row is new, 0 when it is a duplicate.
     */
    private static final String INSERT_INBOUND = """
            WITH conversation AS (
              INSERT INTO conversations (phone_e164, state, created_at, updated_at)
              VALUES (?, 'ACTIVE', now(), now())
              ON CONFLICT (phone_e164) DO NOTHING
            )
            INSERT INTO inbound_messages (wa_message_id, phone_e164, body, status, attempts, received_at)
            VALUES (?, ?, ?, 'RECEIVED', 0, now())
            ON CONFLICT (wa_message_id) DO NOTHING
            """;

    private static final String MARK_RATE_LIMITED = """
            UPDATE inbound_messages SET status = 'RATE_LIMITED', processed_at = now()
            WHERE wa_message_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final RateLimiter rateLimiter;
    private final MeterRegistry metrics;

    public IngestService(JdbcTemplate jdbc, RateLimiter rateLimiter, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    @Transactional
    public IngestResult ingest(List<InboundMessage> messages) {
        IngestResult combined = IngestResult.IGNORED;
        for (InboundMessage message : messages) {
            IngestResult result = ingestOne(message);
            metrics.counter("gateway_inbound_messages_total", "result", result.wireName()).increment();
            combined = combined.combine(result);
        }
        return combined;
    }

    private IngestResult ingestOne(InboundMessage message) {
        int inserted = jdbc.update(INSERT_INBOUND,
                message.from(), message.waMessageId(), message.from(), message.body());
        if (inserted == 0) {
            return IngestResult.DUPLICATE;
        }
        if (!rateLimiter.allow(message.from())) {
            jdbc.update(MARK_RATE_LIMITED, message.waMessageId());
            return IngestResult.RATE_LIMITED;
        }
        return IngestResult.ACCEPTED;
    }
}
