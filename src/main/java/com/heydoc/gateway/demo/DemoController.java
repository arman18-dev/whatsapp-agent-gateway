package com.heydoc.gateway.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.heydoc.gateway.ingest.IngestResult;
import com.heydoc.gateway.ingest.IngestService;
import com.heydoc.gateway.webhook.WebhookPayloadParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Demo endpoints for the public page. A browser cannot sign webhook payloads safely, so the server
 * builds the WhatsApp-format message and passes it straight to the same ingest path the webhook uses.
 * Only available when DEMO_ENABLED is on and the model is mock: the page can never spend money.
 */
@RestController
@Profile("gateway")
public class DemoController {

    private static final Pattern PHONE = Pattern.compile("[0-9]{6,15}");
    private static final int MAX_TEXT = 500;

    private final ObjectMapper json;
    private final WebhookPayloadParser parser;
    private final IngestService ingest;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final String llmMode;

    public DemoController(ObjectMapper json, WebhookPayloadParser parser, IngestService ingest, JdbcTemplate jdbc,
                          @Value("${app.demo.enabled:false}") boolean enabled,
                          @Value("${app.llm.mode:mock}") String llmMode) {
        this.json = json;
        this.parser = parser;
        this.ingest = ingest;
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.llmMode = llmMode;
    }

    @PostMapping(value = "/demo/messages", consumes = "application/json")
    public Map<String, String> send(@RequestBody Map<String, String> request) {
        requireDemoEnabled();
        String phone = request.getOrDefault("phone", "");
        String text = request.getOrDefault("text", "");
        if (!PHONE.matcher(phone).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "phone must be 6 to 15 digits");
        }
        if (text.isBlank() || text.length() > MAX_TEXT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text must be 1 to 500 characters");
        }

        ObjectNode message = json.createObjectNode()
                .put("from", phone)
                .put("id", "wamid.demo-" + UUID.randomUUID())
                .put("timestamp", String.valueOf(System.currentTimeMillis() / 1000))
                .put("type", "text");
        message.putObject("text").put("body", text);
        ObjectNode payload = json.createObjectNode().put("object", "whatsapp_business_account");
        payload.putArray("entry").addObject().putArray("changes").addObject().put("field", "messages")
                .putObject("value").put("messaging_product", "whatsapp").putArray("messages").add(message);

        IngestResult result;
        try {
            result = ingest.ingest(parser.parse(payload.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "could not read the message");
        }
        return Map.of("status", result.wireName());
    }

    @GetMapping("/demo/recent-bookings")
    public List<Map<String, Object>> recentBookings() {
        requireDemoEnabled();
        return jdbc.queryForList("""
                SELECT b.lab_booking_ref, b.patient_name, b.test_code, s.starts_at, s.lab_site, b.phone_e164
                FROM bookings b JOIN slots s ON s.slot_id = b.slot_id
                WHERE b.status = 'CONFIRMED'
                ORDER BY b.updated_at DESC
                LIMIT 20
                """);
    }

    private void requireDemoEnabled() {
        if (!enabled || !"mock".equals(llmMode)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "demo is off");
        }
    }
}
