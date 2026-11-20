package com.heydoc.gateway.usage;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Per-conversation tokens and cost. Kept off /metrics on purpose: a phone label would explode cardinality. */
@RestController
@Profile({"gateway", "worker"})
public class UsageController {

    private final JdbcTemplate jdbc;

    public UsageController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/conversations/{phone}/usage")
    public Map<String, Object> usage(@PathVariable("phone") String phone) {
        return jdbc.queryForMap("""
                SELECT COUNT(*) AS llm_calls,
                       COALESCE(SUM(input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens,
                       COALESCE(SUM(cache_read_tokens), 0) AS cache_read_tokens,
                       COALESCE(SUM(cache_write_tokens), 0) AS cache_write_tokens,
                       COALESCE(SUM(cost_usd), 0) AS cost_usd
                FROM llm_calls WHERE phone_e164 = ?
                """, phone);
    }
}
