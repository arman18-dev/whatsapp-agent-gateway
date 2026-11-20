package com.heydoc.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("gateway")
@Testcontainers
class WebhookTest {

    private static final String SECRET = "test-app-secret";
    private static final String VERIFY_TOKEN = "test-verify-token";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.whatsapp-app-secret", () -> SECRET);
        registry.add("app.whatsapp-verify-token", () -> VERIFY_TOKEN);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void verificationHandshakeEchoesChallengeOnlyWithCorrectToken() throws Exception {
        mvc.perform(get("/webhook")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", VERIFY_TOKEN)
                        .param("hub.challenge", "12345"))
                .andExpect(status().isOk())
                .andExpect(content().string("12345"));

        mvc.perform(get("/webhook")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "wrong")
                        .param("hub.challenge", "12345"))
                .andExpect(status().isForbidden());
    }

    @Test
    void badSignatureIsRejectedAndStoresNothing() throws Exception {
        String phone = uniquePhone();
        String waId = "wamid." + UUID.randomUUID();
        String body = textPayload(waId, phone, "hello");

        mvc.perform(post("/webhook")
                        .contentType("application/json")
                        .content(body)
                        .header("X-Hub-Signature-256", "sha256=00"))
                .andExpect(status().isUnauthorized());

        assertThat(countInbound(waId)).isZero();
    }

    @Test
    void missingSignatureIsRejected() throws Exception {
        String body = textPayload("wamid." + UUID.randomUUID(), uniquePhone(), "hello");
        mvc.perform(post("/webhook").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validMessageIsAcceptedOnceAndDuplicateGetsNoSecondRow() throws Exception {
        String phone = uniquePhone();
        String waId = "wamid." + UUID.randomUUID();
        String body = textPayload(waId, phone, "book a thyroid test");

        send(body).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        send(body).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));

        assertThat(countInbound(waId)).isEqualTo(1);
        assertThat(statusOf(waId)).isEqualTo("RECEIVED");
    }

    @Test
    void eleventhMessageInWindowIsStoredAsRateLimitedAndSilent() throws Exception {
        String phone = uniquePhone();
        for (int i = 1; i <= 10; i++) {
            send(textPayload("wamid." + UUID.randomUUID(), phone, "msg " + i))
                    .andExpect(jsonPath("$.status").value("accepted"));
        }
        String overLimitId = "wamid." + UUID.randomUUID();
        send(textPayload(overLimitId, phone, "msg 11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rate_limited"));

        assertThat(statusOf(overLimitId)).isEqualTo("RATE_LIMITED");
    }

    @Test
    void statusEventsAreAcknowledgedAndIgnored() throws Exception {
        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"field":"messages",
                "value":{"statuses":[{"id":"wamid.x","status":"delivered"}]}}]}]}
                """;
        send(body).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));
    }

    @Test
    void malformedJsonWithValidSignatureIsBadRequest() throws Exception {
        String body = "{not json";
        mvc.perform(post("/webhook")
                        .contentType("application/json")
                        .content(body)
                        .header("X-Hub-Signature-256", sign(body)))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions send(String body) throws Exception {
        return mvc.perform(post("/webhook")
                .contentType("application/json")
                .content(body)
                .header("X-Hub-Signature-256", sign(body)));
    }

    private int countInbound(String waId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbound_messages WHERE wa_message_id = ?", Integer.class, waId);
        return count == null ? 0 : count;
    }

    private String statusOf(String waId) {
        return jdbc.queryForObject(
                "SELECT status FROM inbound_messages WHERE wa_message_id = ?", String.class, waId);
    }

    private static String uniquePhone() {
        return "9" + String.format("%09d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
    }

    private static String textPayload(String waId, String from, String text) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"field":"messages",
                "value":{"messaging_product":"whatsapp","messages":[{"from":"%s","id":"%s",
                "timestamp":"1700000000","type":"text","text":{"body":"%s"}}]}}]}]}
                """.formatted(from, waId, text);
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
