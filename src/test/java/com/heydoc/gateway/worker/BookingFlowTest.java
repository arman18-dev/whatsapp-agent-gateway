package com.heydoc.gateway.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
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

/**
 * End to end: webhook -> Postgres queue -> worker -> scripted model -> booking -> mock lab (with 20%
 * failures and dropped responses) -> reconciler -> outbox. Asserts that nothing is double booked.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18080",
                "app.lab.base-url=http://localhost:18080",
                "app.lab.backoff-base-ms=5",
                "app.lab.backoff-cap-ms=50",
                "app.mock-lab.min-latency-ms=1",
                "app.mock-lab.max-latency-ms=20",
                "app.worker.reconcile-after-seconds=1",
                "app.worker.reconcile-interval-ms=300",
                "app.worker.outbox-interval-ms=100",
                "app.worker.threads=6"
        })
@AutoConfigureMockMvc
@ActiveProfiles({"gateway", "worker", "mock-lab"})
@Testcontainers
class BookingFlowTest {

    private static final String SECRET = "test-app-secret";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.whatsapp-app-secret", () -> SECRET);
        registry.add("app.whatsapp-verify-token", () -> "unused");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void raceForOneSlotBooksExactlyOnce() throws Exception {
        List<String> phones = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String phone = uniquePhone();
            phones.add(phone);
            send(textPayload("wamid." + UUID.randomUUID(), phone, "I want to book a thyroid test"));
        }
        awaitQuiet();

        // Count only this race's patients: other tests in this class also book, in the same database.
        int confirmed = 0;
        for (String phone : phones) {
            confirmed += jdbc.queryForObject(
                    "SELECT COUNT(*) FROM bookings WHERE status = 'CONFIRMED' AND phone_e164 = ?",
                    Integer.class, phone);
        }
        assertThat(confirmed).isEqualTo(1);
        assertThat(doubleBookedSlots()).isZero();
        for (String phone : phones) {
            assertThat(outboundCount(phone)).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void duplicateDeliveryProducesOneTurnAndOneReply() throws Exception {
        String phone = uniquePhone();
        String waId = "wamid." + UUID.randomUUID();
        String body = textPayload(waId, phone, "book a thyroid test");
        send(body).andExpect(jsonPath("$.status").value("accepted"));
        send(body).andExpect(jsonPath("$.status").value("duplicate"));
        send(body).andExpect(jsonPath("$.status").value("duplicate"));
        awaitQuiet();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE wa_message_id = ?", Integer.class, waId)).isEqualTo(1);
        assertThat(outboundCount(phone)).isEqualTo(1);
    }

    @Test
    void handedOffConversationGetsFixedReplyWithoutAnyModelOrLabCall() throws Exception {
        String phone = uniquePhone();
        send(textPayload("wamid." + UUID.randomUUID(), phone, "I want to speak to a human"));
        awaitQuiet();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM conversations WHERE phone_e164 = ?", String.class, phone)).isEqualTo("HANDED_OFF");

        send(textPayload("wamid." + UUID.randomUUID(), phone, "book a thyroid test"));
        awaitQuiet();

        assertThat(outboundCount(phone)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM offered_slots WHERE phone_e164 = ?", Integer.class, phone)).isZero();
    }

    @Test
    void reviewerEndpointShowsTheConversation() throws Exception {
        String phone = uniquePhone();
        send(textPayload("wamid." + UUID.randomUUID(), phone,
                "I want a thyroid test on 2026-11-30 at 09:00. My name is Review Patient."));
        awaitQuiet();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/conversations/" + phone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.messages_received.length()").value(1))
                .andExpect(jsonPath("$.replies_sent.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/conversations/9000000000"))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions send(String body) throws Exception {
        return mvc.perform(post("/webhook")
                .contentType("application/json")
                .content(body)
                .header("X-Hub-Signature-256", sign(body)));
    }

    private void awaitQuiet() throws InterruptedException {
        await(() -> count("SELECT COUNT(*) FROM inbound_messages WHERE status IN ('RECEIVED','PROCESSING')") == 0
                && count("SELECT COUNT(*) FROM bookings WHERE status IN ('HELD','UNKNOWN')") == 0
                && count("SELECT COUNT(*) FROM outbound_messages WHERE status = 'PENDING'") == 0);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for the pipeline to go quiet");
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private int outboundCount(String phone) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbound_messages WHERE phone_e164 = ?", Integer.class, phone);
        return value == null ? 0 : value;
    }

    private int doubleBookedSlots() {
        Integer value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                  SELECT slot_id FROM bookings
                  WHERE status IN ('HELD', 'CONFIRMED', 'UNKNOWN')
                  GROUP BY slot_id HAVING COUNT(*) > 1) d
                """, Integer.class);
        return value == null ? 0 : value;
    }

    private static String uniquePhone() {
        return "8" + String.format("%09d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
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
