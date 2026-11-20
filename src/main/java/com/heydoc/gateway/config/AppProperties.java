package com.heydoc.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration from environment variables (see application.yml). No secrets live in the repo.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String whatsappAppSecret,
        String whatsappVerifyToken,
        @DefaultValue("10") int rateLimitMax,
        @DefaultValue("60") int rateLimitWindowSeconds
) {
}
