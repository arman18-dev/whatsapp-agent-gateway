package com.heydoc.gateway.outbox;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Outbox: a reply is written in the same transaction as the state change that produced it,
 * then sent by the dispatcher. Delivery is at-least-once (see README).
 */
@Service
@Profile("worker")
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final JdbcTemplate jdbc;
    private final OutboundSender sender;

    public OutboxService(JdbcTemplate jdbc, OutboundSender sender) {
        this.jdbc = jdbc;
        this.sender = sender;
    }

    public void enqueue(String phone, String body) {
        jdbc.update("""
                INSERT INTO outbound_messages (phone_e164, body, status, created_at)
                VALUES (?, ?, 'PENDING', now())
                """, phone, body);
    }

    @Scheduled(fixedDelayString = "${app.worker.outbox-interval-ms:500}")
    public void dispatch() {
        List<Map<String, Object>> pending = jdbc.queryForList("""
                SELECT id, phone_e164, body FROM outbound_messages
                WHERE status = 'PENDING' ORDER BY id LIMIT 50
                """);
        for (Map<String, Object> row : pending) {
            long id = ((Number) row.get("id")).longValue();
            try {
                sender.send((String) row.get("phone_e164"), (String) row.get("body"));
                jdbc.update("UPDATE outbound_messages SET status = 'SENT', sent_at = now() WHERE id = ?", id);
            } catch (RuntimeException e) {
                log.warn("send failed for outbox row {}, will retry: {}", id, e.getMessage());
            }
        }
    }
}
