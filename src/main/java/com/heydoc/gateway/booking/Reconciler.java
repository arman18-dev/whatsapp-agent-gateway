package com.heydoc.gateway.booking;

import com.heydoc.gateway.lab.LabClient;
import com.heydoc.gateway.lab.LabUnavailableException;
import com.heydoc.gateway.outbox.OutboxService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import com.heydoc.gateway.config.WorkerProperties;

/**
 * Resolves HELD and UNKNOWN bookings older than the grace period by asking the lab for the key.
 * Found: CONFIRMED and tell the patient. Not found: the lab fences the key, so RELEASE is safe.
 */
@Component
@Profile("worker")
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final JdbcTemplate jdbc;
    private final LabClient lab;
    private final BookingService bookings;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final WorkerProperties properties;

    public Reconciler(JdbcTemplate jdbc, LabClient lab, BookingService bookings, OutboxService outbox,
                      TransactionTemplate tx, WorkerProperties properties) {
        this.jdbc = jdbc;
        this.lab = lab;
        this.bookings = bookings;
        this.outbox = outbox;
        this.tx = tx;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.worker.reconcile-interval-ms:10000}")
    public void run() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, phone_e164 FROM bookings
                WHERE status IN ('HELD', 'UNKNOWN')
                  AND updated_at < now() - CAST(? AS int) * interval '1 second'
                ORDER BY updated_at
                LIMIT 50
                """, properties.reconcileAfterSeconds());

        for (Map<String, Object> row : rows) {
            UUID bookingId = (UUID) row.get("id");
            String phone = (String) row.get("phone_e164");
            try {
                Optional<String> ref = lab.lookup(bookingId);
                tx.executeWithoutResult(status -> {
                    if (ref.isPresent()) {
                        bookings.setStatus(bookingId, "CONFIRMED", ref.get());
                        outbox.enqueue(phone, "Your test is booked. Reference: " + ref.get());
                    } else {
                        bookings.setStatus(bookingId, "RELEASED", null);
                        outbox.enqueue(phone, "We could not confirm the slot you chose. Reply to choose another time.");
                    }
                });
            } catch (LabUnavailableException e) {
                log.warn("reconcile deferred for booking {}: {}", bookingId, e.getMessage());
            }
        }
    }
}
