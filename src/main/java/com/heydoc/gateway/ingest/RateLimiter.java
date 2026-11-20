package com.heydoc.gateway.ingest;

import com.heydoc.gateway.config.AppProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fixed-window rate limit per phone number, one SQL upsert per call.
 * Must run inside the caller's transaction so it commits together with the inbound row.
 */
@Component
@Profile("gateway")
public class RateLimiter {

    private static final String UPSERT = """
            INSERT INTO rate_limits AS r (phone_e164, window_start, count)
            VALUES (?, now(), 1)
            ON CONFLICT (phone_e164) DO UPDATE SET
              window_start = CASE WHEN r.window_start <= now() - CAST(? AS int) * interval '1 second'
                                  THEN now() ELSE r.window_start END,
              count        = CASE WHEN r.window_start <= now() - CAST(? AS int) * interval '1 second'
                                  THEN 1 ELSE r.count + 1 END
            RETURNING count
            """;

    private final JdbcTemplate jdbc;
    private final int maxPerWindow;
    private final int windowSeconds;

    public RateLimiter(JdbcTemplate jdbc, AppProperties properties) {
        this.jdbc = jdbc;
        this.maxPerWindow = properties.rateLimitMax();
        this.windowSeconds = properties.rateLimitWindowSeconds();
    }

    /** Counts one message for this phone. Returns false when the window is already full. */
    public boolean allow(String phone) {
        Integer count = jdbc.queryForObject(UPSERT, Integer.class, phone, windowSeconds, windowSeconds);
        return count != null && count <= maxPerWindow;
    }
}
