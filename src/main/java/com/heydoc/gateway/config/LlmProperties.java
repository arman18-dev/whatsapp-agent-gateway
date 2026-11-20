package com.heydoc.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LLM settings. {@code mode=mock} uses a scripted, zero-cost model (tests, most dev runs);
 * {@code mode=real} calls Claude with {@code model}. Prices are USD per million tokens.
 */
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(
        @DefaultValue("mock") String mode,
        @DefaultValue("claude-haiku-5-5") String model,
        @DefaultValue("1024") long maxTokens,
        @DefaultValue("6") int maxToolIterations,
        /** Hard stop for model spend recorded in llm_calls. Past this, conversations hand off to staff. */
        @DefaultValue("1.00") double budgetUsd,
        /** mock mode only: pick a random offered slot instead of the first, so load runs spread across slots. */
        @DefaultValue("false") boolean scriptedSpread,
        Pricing pricing
) {

    public record Pricing(
            @DefaultValue("0.10") double inputPerMtok,
            @DefaultValue("0.50") double outputPerMtok,
            @DefaultValue("0.01") double cacheReadPerMtok,
            @DefaultValue("0.125") double cacheWritePerMtok
    ) {
    }
}
