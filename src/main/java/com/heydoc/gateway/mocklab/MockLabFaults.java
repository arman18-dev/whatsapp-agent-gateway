package com.heydoc.gateway.mocklab;

import com.heydoc.gateway.config.MockLabProperties;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Injects latency, hangs and failures so the gateway's retry and reconcile paths get exercised. */
@Component
@Profile("mock-lab")
public class MockLabFaults {

    private final MockLabProperties properties;

    public MockLabFaults(MockLabProperties properties) {
        this.properties = properties;
    }

    /** Called before each request is handled. May sleep, hang, or ask the caller to fail with 503. */
    public boolean shouldFail() throws InterruptedException {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long latency = properties.minLatencyMs()
                + random.nextLong(Math.max(1, properties.maxLatencyMs() - properties.minLatencyMs() + 1));
        Thread.sleep(latency);
        if (random.nextDouble() < properties.hangRate()) {
            Thread.sleep(properties.hangMs());
        }
        return random.nextDouble() < properties.failureRate();
    }

    /** After a booking commits, drop the response with some probability (the caller sees a 503). */
    public boolean shouldDropAfterCommit() {
        return ThreadLocalRandom.current().nextDouble() < properties.dropResponseRate();
    }
}
