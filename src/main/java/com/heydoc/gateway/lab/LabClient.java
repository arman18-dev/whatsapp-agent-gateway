package com.heydoc.gateway.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heydoc.gateway.config.LabProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Client for the lab booking API. Timeouts and 5xx responses are retried with backoff
 * (exponential, full jitter). The booking key is the same for every retry of one booking attempt.
 */
@Component
@Profile("worker")
public class LabClient {

    private final RestClient rest;
    private final ObjectMapper json;
    private final LabProperties properties;
    private final MeterRegistry metrics;

    public LabClient(LabProperties properties, ObjectMapper json, MeterRegistry metrics) {
        this.properties = properties;
        this.json = json;
        this.metrics = metrics;
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.timeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(properties.timeoutMs()));
        this.rest = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /** GET /slots. Safe to retry. Throws LabUnavailableException after the retry budget. */
    public List<Slot> checkSlots(String testCode, String dateFrom, String dateTo) {
        return withRetries("check_slots", () -> {
            Raw raw = send(rest.get().uri("/slots?test_code={t}&from={f}&to={o}", testCode, dateFrom, dateTo));
            requireSuccess(raw);
            List<Slot> slots = new ArrayList<>();
            for (JsonNode node : parse(raw)) {
                slots.add(new Slot(node.path("slot_id").asText(), node.path("starts_at").asText(),
                        node.path("lab_site").asText()));
            }
            return slots;
        });
    }

    /**
     * POST /bookings with Idempotency-Key = booking id. Never returns a new key for the same intent.
     * Retry exhaustion yields UNKNOWN: the outcome is unknown, so the caller must reconcile.
     */
    public BookOutcome book(UUID bookingId, String slotId, String testCode, String patientName) {
        try {
            return withRetries("book", () -> {
                String body = json.createObjectNode()
                        .put("slot_id", slotId)
                        .put("test_code", testCode)
                        .put("patient_name", patientName)
                        .toString();
                Raw raw = send(rest.post()
                        .uri("/bookings")
                        .header("Idempotency-Key", bookingId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body));
                if (raw.status() == 201 || raw.status() == 200) {
                    return BookOutcome.confirmed(parse(raw).path("booking_ref").asText());
                }
                if (raw.status() == 409) {
                    return BookOutcome.slotTaken();
                }
                if (raw.status() >= 500) {
                    throw new RetryableLabException("book HTTP " + raw.status());
                }
                return BookOutcome.refused();  // 422 KEY_FENCED and other 4xx
            });
        } catch (LabUnavailableException e) {
            return BookOutcome.unknown();
        }
    }

    /**
     * GET /bookings?idempotency_key=. Returns the booking ref, or empty when the lab has no
     * booking for this key (the lab then fences the key, so it cannot book later).
     */
    public Optional<String> lookup(UUID bookingId) {
        return withRetries("lookup", () -> {
            Raw raw = send(rest.get().uri("/bookings?idempotency_key={k}", bookingId.toString()));
            if (raw.status() == 404) {
                return Optional.empty();
            }
            requireSuccess(raw);
            return Optional.of(parse(raw).path("booking_ref").asText());
        });
    }

    private <T> T withRetries(String operation, Supplier<T> call) {
        int maxAttempts = Math.max(1, properties.maxAttempts());
        for (int attempt = 1; ; attempt++) {
            try {
                T result = call.get();
                metrics.counter("lab_calls_total", "op", operation, "outcome", "ok").increment();
                return result;
            } catch (RetryableLabException e) {
                metrics.counter("lab_calls_total", "op", operation, "outcome", "retryable_failure").increment();
                if (attempt >= maxAttempts) {
                    throw new LabUnavailableException(operation + " failed after " + attempt + " attempts", e);
                }
                sleepQuietly(backoffMs(attempt));
            }
        }
    }

    private long backoffMs(int attempt) {
        long ceiling = Math.min(properties.backoffCapMs(), properties.backoffBaseMs() * (1L << (attempt - 1)));
        return ThreadLocalRandom.current().nextLong(ceiling + 1);  // full jitter
    }

    private Raw send(RestClient.RequestHeadersSpec<?> spec) {
        try {
            return spec.exchange((request, response) -> new Raw(
                    response.getStatusCode().value(),
                    StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8)));
        } catch (ResourceAccessException e) {
            // Timeouts and connection resets: the outcome at the lab is unknown, so retry with the same key.
            throw new RetryableLabException("network: " + e.getMessage(), e);
        }
    }

    private void requireSuccess(Raw raw) {
        if (raw.status() >= 500) {
            throw new RetryableLabException("HTTP " + raw.status());
        }
        if (raw.status() >= 400) {
            throw new LabUnavailableException("HTTP " + raw.status() + " (not retryable)", null);
        }
    }

    private JsonNode parse(Raw raw) {
        try {
            return json.readTree(raw.body());
        } catch (IOException e) {
            throw new RetryableLabException("unparseable lab response", e);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LabUnavailableException("interrupted during backoff", e);
        }
    }

    private record Raw(int status, String body) {
    }
}
