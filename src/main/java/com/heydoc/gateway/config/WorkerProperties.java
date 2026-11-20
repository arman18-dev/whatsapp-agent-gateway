package com.heydoc.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Worker settings. The lease must exceed the longest normal job (LLM rounds plus lab retries). */
@ConfigurationProperties(prefix = "app.worker")
public record WorkerProperties(
        @DefaultValue("4") int threads,
        @DefaultValue("90") int leaseSeconds,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("60") long reconcileAfterSeconds,
        @DefaultValue("10000") long reconcileIntervalMs,
        @DefaultValue("40") int historyRows,
        @DefaultValue("500") long outboxIntervalMs
) {
}
