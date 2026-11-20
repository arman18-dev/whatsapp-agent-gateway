package com.heydoc.gateway;

import com.heydoc.gateway.config.AppProperties;
import com.heydoc.gateway.config.LabProperties;
import com.heydoc.gateway.config.LlmProperties;
import com.heydoc.gateway.config.MockLabProperties;
import com.heydoc.gateway.config.WorkerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * One jar, three roles, chosen by Spring profile:
 * <ul>
 *   <li>{@code gateway}: WhatsApp webhook, dedupe, rate limit (no LLM or lab calls)</li>
 *   <li>{@code worker}: claims inbound messages, runs the Claude tool loop and the lab client</li>
 *   <li>{@code mock-lab}: fake booking API with injected failures</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        AppProperties.class,
        LabProperties.class,
        LlmProperties.class,
        WorkerProperties.class,
        MockLabProperties.class
})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
