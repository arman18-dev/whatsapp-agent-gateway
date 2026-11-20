package com.heydoc.gateway.usage;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only view of one patient's conversation: what they sent, what we replied, their bookings,
 * and any handoff. Gives reviewers a way to see results without database access or a UI.
 */
@RestController
public class ConversationController {

    private final JdbcTemplate jdbc;

    public ConversationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/conversations/{phone}")
    public Map<String, Object> conversation(@PathVariable("phone") String phone) {
        Integer known = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE phone_e164 = ?", Integer.class, phone);
        if (known == null || known == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no conversation for this number");
        }
        String state = jdbc.queryForObject(
                "SELECT state FROM conversations WHERE phone_e164 = ?", String.class, phone);

        List<Map<String, Object>> incoming = jdbc.queryForList("""
                SELECT body, status, received_at FROM inbound_messages
                WHERE phone_e164 = ? ORDER BY received_at
                """, phone);
        List<Map<String, Object>> replies = jdbc.queryForList("""
                SELECT body, status, created_at FROM outbound_messages
                WHERE phone_e164 = ? ORDER BY id
                """, phone);
        List<Map<String, Object>> bookings = jdbc.queryForList("""
                SELECT b.status, b.lab_booking_ref, b.patient_name, b.test_code, s.starts_at, s.lab_site
                FROM bookings b JOIN slots s ON s.slot_id = b.slot_id
                WHERE b.phone_e164 = ? ORDER BY s.starts_at
                """, phone);
        List<String> handoffs = jdbc.queryForList(
                "SELECT reason FROM handoffs WHERE phone_e164 = ? ORDER BY id", String.class, phone);

        return Map.of(
                "phone", phone,
                "state", state,
                "messages_received", incoming,
                "replies_sent", replies,
                "bookings", bookings,
                "handoffs", handoffs);
    }
}
