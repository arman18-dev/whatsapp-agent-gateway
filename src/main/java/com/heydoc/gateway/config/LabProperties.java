package com.heydoc.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Lab API client settings: timeout 2 s per call, up to 4 attempts, exponential backoff with full jitter. */
@ConfigurationProperties(prefix = "app.lab")
public record LabProperties(
        @DefaultValue("http://localhost:8081") String baseUrl,
        @DefaultValue("2000") int timeoutMs,
        @DefaultValue("4") int maxAttempts,
        @DefaultValue("200") long backoffBaseMs,
        @DefaultValue("3000") long backoffCapMs
) {
}
