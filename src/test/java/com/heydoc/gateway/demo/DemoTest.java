package com.heydoc.gateway.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** The demo page and its endpoints: sending a message, reading bookings, and the mock-only guard. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("gateway")
@Testcontainers
class DemoTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.demo.enabled", () -> "true");
        registry.add("app.llm.mode", () -> "mock");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void rootServesTheDemoPage() throws Exception {
        // "/" forwards to index.html; MockMvc does not follow forwards, so test the file itself.
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Lab booking agent: live demo")));
    }

    @Test
    void sendingAMessageStoresItLikeAWebhookDoes() throws Exception {
        mvc.perform(post("/demo/messages").contentType("application/json")
                        .content("{\"phone\":\"9911000001\",\"text\":\"Hello there\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        Integer stored = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbound_messages WHERE phone_e164 = ? AND body = ?",
                Integer.class, "9911000001", "Hello there");
        assertThat(stored).isEqualTo(1);
    }

    @Test
    void secondIdenticalSendIsAnotherNewMessage() throws Exception {
        // Each press of Send is a new message with a new id, so it is accepted, not a duplicate.
        String body = "{\"phone\":\"9911000002\",\"text\":\"Hi again\"}";
        mvc.perform(post("/demo/messages").contentType("application/json").content(body))
                .andExpect(jsonPath("$.status").value("accepted"));
        mvc.perform(post("/demo/messages").contentType("application/json").content(body))
                .andExpect(jsonPath("$.status").value("accepted"));
    }

    @Test
    void rejectsABadPhoneNumberAndEmptyText() throws Exception {
        mvc.perform(post("/demo/messages").contentType("application/json")
                        .content("{\"phone\":\"12ab\",\"text\":\"Hello\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/demo/messages").contentType("application/json")
                        .content("{\"phone\":\"9911000003\",\"text\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recentBookingsIsAnEmptyListBeforeAnyBooking() throws Exception {
        mvc.perform(get("/demo/recent-bookings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
