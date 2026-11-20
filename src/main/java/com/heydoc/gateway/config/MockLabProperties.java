package com.heydoc.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Fault model for the mock lab (PLAN.md section 4). Defaults match the brief: 20% failures,
 * 300-1500 ms latency, about 10% of successful bookings dropped after they commit.
 */
@ConfigurationProperties(prefix = "app.mock-lab")
public record MockLabProperties(
        @DefaultValue("0.2") double failureRate,
        @DefaultValue("0.1") double dropResponseRate,
        @DefaultValue("0.0") double hangRate,
        @DefaultValue("300") long minLatencyMs,
        @DefaultValue("1500") long maxLatencyMs,
        @DefaultValue("2500") long hangMs
) {
}
