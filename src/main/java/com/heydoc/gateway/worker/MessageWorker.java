package com.heydoc.gateway.worker;

import com.heydoc.gateway.config.WorkerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Worker threads that claim inbound messages from Postgres. Claim: FOR UPDATE SKIP LOCKED with a lease.
 * Lock: a per-phone session advisory lock, so one conversation runs one message at a time.
 * A failed job is retried with backoff, and goes to DEAD (with a handoff) after maxAttempts.
 */
@Component
@Profile("worker")
public class MessageWorker {

    private static final Logger log = LoggerFactory.getLogger(MessageWorker.class);

    private record Claimed(String waMessageId, String phone, String body, int attempts) {
    }

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final ConversationProcessor processor;
    private final WorkerProperties properties;
    private final MeterRegistry metrics;
    private final TransactionTemplate tx;
    private ExecutorService pool;
    private volatile boolean running;

    public MessageWorker(JdbcTemplate jdbc, DataSource dataSource, ConversationProcessor processor,
                         WorkerProperties properties, MeterRegistry metrics, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.processor = processor;
        this.properties = properties;
        this.metrics = metrics;
        this.tx = tx;
    }

    @PostConstruct
    void start() {
        running = true;
        pool = Executors.newFixedThreadPool(properties.threads());
        for (int i = 0; i < properties.threads(); i++) {
            pool.submit(this::loop);
        }
        log.info("worker started with {} threads", properties.threads());
    }

    @PreDestroy
    void stop() {
        running = false;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private void loop() {
        while (running) {
            try {
                Optional<Claimed> claimed = claim();
                if (claimed.isEmpty()) {
                    Thread.sleep(200);
                    continue;
                }
                handle(claimed.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("worker loop error", e);
                sleepQuietly(1000);
            }
        }
    }

    private Optional<Claimed> claim() {
        List<Claimed> rows = jdbc.query("""
                UPDATE inbound_messages
                SET status = 'PROCESSING', attempts = attempts + 1,
                    locked_until = now() + CAST(? AS int) * interval '1 second'
                WHERE wa_message_id = (
                  SELECT wa_message_id FROM inbound_messages
                  WHERE status IN ('RECEIVED', 'PROCESSING')
                    AND (locked_until IS NULL OR locked_until < now())
                  ORDER BY received_at
                  LIMIT 1
                  FOR UPDATE SKIP LOCKED)
                RETURNING wa_message_id, phone_e164, body, attempts
                """, (rs, n) -> new Claimed(rs.getString("wa_message_id"), rs.getString("phone_e164"),
                rs.getString("body"), rs.getInt("attempts")), properties.leaseSeconds());
        return rows.stream().findFirst();
    }

    private void handle(Claimed claimed) throws InterruptedException {
        try (Connection lockConnection = dataSource.getConnection()) {
            if (!tryLock(lockConnection, claimed.phone())) {
                // Another job is running for this conversation: put this message back for a moment later.
                jdbc.update("""
                        UPDATE inbound_messages
                        SET status = 'RECEIVED', attempts = GREATEST(attempts - 1, 0),
                            locked_until = now() + interval '1 second'
                        WHERE wa_message_id = ?
                        """, claimed.waMessageId());
                metrics.counter("worker_jobs_total", "outcome", "requeued_locked").increment();
                return;
            }
            try {
                processor.process(claimed.waMessageId(), claimed.phone(), claimed.body());
                metrics.counter("worker_jobs_total", "outcome", "done").increment();
            } catch (RuntimeException e) {
                onFailure(claimed, e);
            } finally {
                unlock(lockConnection, claimed.phone());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not get a connection for the conversation lock", e);
        }
    }

    private void onFailure(Claimed claimed, RuntimeException e) {
        log.warn("job failed for {} attempt {}: {}", claimed.waMessageId(), claimed.attempts(), e.toString());
        metrics.counter("worker_jobs_total", "outcome", "error").increment();
        if (claimed.attempts() >= properties.maxAttempts()) {
            tx.executeWithoutResult(status -> {
                jdbc.update("""
                        UPDATE inbound_messages SET status = 'DEAD', processed_at = now(), locked_until = NULL
                        WHERE wa_message_id = ?
                        """, claimed.waMessageId());
                processor.handOffDead(claimed.phone());
            });
            metrics.counter("worker_jobs_total", "outcome", "dead").increment();
            return;
        }
        long backoffSeconds = Math.min(60L, 1L << claimed.attempts());
        jdbc.update("""
                UPDATE inbound_messages SET status = 'RECEIVED',
                    locked_until = now() + CAST(? AS int) * interval '1 second'
                WHERE wa_message_id = ?
                """, backoffSeconds, claimed.waMessageId());
    }

    private static boolean tryLock(Connection connection, String phone) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
            ps.setString(1, phone);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("advisory lock failed", e);
        }
    }

    private static void unlock(Connection connection, String phone) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")) {
            ps.setString(1, phone);
            ps.execute();
        } catch (SQLException e) {
            log.warn("advisory unlock failed for {}: {}", phone, e.getMessage());
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
