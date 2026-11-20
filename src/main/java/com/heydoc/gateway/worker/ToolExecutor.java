package com.heydoc.gateway.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.heydoc.gateway.booking.BookingService;
import com.heydoc.gateway.booking.BookingService.BookingResult;
import com.heydoc.gateway.lab.LabClient;
import com.heydoc.gateway.lab.LabUnavailableException;
import com.heydoc.gateway.lab.Slot;
import com.heydoc.gateway.llm.LlmPart;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs tool calls from Claude. Guardrails live here, not in the prompt:
 * book_test only accepts a slot this phone was offered by check_slots, and every result is stored by
 * tool_use_id, so a replayed call returns the stored result instead of acting again.
 */
@Component
@Profile("worker")
public class ToolExecutor {

    private static final int MAX_SLOTS = 15;
    private static final java.time.ZoneId LAB_ZONE = java.time.ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final LabClient lab;
    private final BookingService bookings;
    private final ObjectMapper json;

    public ToolExecutor(JdbcTemplate jdbc, LabClient lab, BookingService bookings, ObjectMapper json) {
        this.jdbc = jdbc;
        this.lab = lab;
        this.bookings = bookings;
        this.json = json;
    }

    public LlmPart.ToolResult run(String phone, LlmPart.ToolUse use) {
        Optional<String> stored = jdbc.queryForList(
                        "SELECT result::text FROM tool_calls WHERE tool_use_id = ? AND result IS NOT NULL",
                        String.class, use.id())
                .stream().findFirst();
        if (stored.isPresent()) {
            return new LlmPart.ToolResult(use.id(), stored.get(), false);  // replay: no second action
        }

        boolean isError = false;
        ObjectNode result;
        try {
            result = switch (use.name()) {
                case "check_slots" -> checkSlots(phone, use.input());
                case "book_test" -> bookTest(phone, use);
                case "handoff_to_human" -> handoff(use.input());
                default -> {
                    isError = true;
                    yield error("UNKNOWN_TOOL");
                }
            };
        } catch (InvalidToolInput e) {
            isError = true;
            result = error("INVALID_INPUT");
        }

        String content = result.toString();
        String inputJson = use.input() == null ? "{}" : use.input().toString();
        jdbc.update("""
                INSERT INTO tool_calls (tool_use_id, phone_e164, name, input, result, created_at)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, now())
                ON CONFLICT (tool_use_id) DO UPDATE SET result = EXCLUDED.result
                """, use.id(), phone, use.name(), inputJson, content);
        return new LlmPart.ToolResult(use.id(), content, isError);
    }

    private ObjectNode checkSlots(String phone, JsonNode input) {
        String testCode = required(input, "test_code");
        LocalDate from = date(required(input, "date_from"));
        LocalDate to = date(required(input, "date_to"));
        LocalDate today = LocalDate.now(LAB_ZONE);
        if (to.isBefore(from) || from.isBefore(today)) {
            // Past or reversed dates: the model gets an error and can ask the patient again.
            throw new InvalidToolInput();
        }

        List<Slot> slots;
        try {
            slots = lab.checkSlots(testCode, from.toString(), to.toString());
        } catch (LabUnavailableException e) {
            return json.createObjectNode().put("status", "LAB_UNAVAILABLE");
        }

        var out = json.createArrayNode();
        for (Slot slot : spreadAcrossDays(slots)) {
            jdbc.update("""
                    INSERT INTO slots (slot_id, test_code, starts_at, lab_site, seen_at)
                    VALUES (?, ?, ?::timestamptz, ?, now())
                    ON CONFLICT (slot_id) DO NOTHING
                    """, slot.slotId(), testCode, slot.startsAt(), slot.labSite());
            jdbc.update("""
                    INSERT INTO offered_slots (phone_e164, slot_id, offered_at)
                    VALUES (?, ?, now())
                    ON CONFLICT (phone_e164, slot_id) DO NOTHING
                    """, phone, slot.slotId());
            out.add(json.createObjectNode()
                    .put("slot_id", slot.slotId())
                    .put("starts_at", slot.startsAt())
                    .put("lab_site", slot.labSite()));
        }
        ObjectNode result = json.createObjectNode();
        result.put("status", "OK");
        result.set("slots", out);
        return result;
    }

    /**
     * For a single day the whole day is offered (up to 40), so any time the patient asks for can be chosen.
     * For several days: up to 5 per day and 15 in total, so the patient sees several days.
     */
    static List<Slot> spreadAcrossDays(List<Slot> slots) {
        java.util.Set<String> days = new java.util.HashSet<>();
        for (Slot slot : slots) {
            days.add(slot.startsAt().substring(0, 10));
        }
        boolean singleDay = days.size() <= 1;
        int perDay = singleDay ? 40 : 5;
        int total = singleDay ? 40 : MAX_SLOTS;
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        List<Slot> chosen = new java.util.ArrayList<>();
        for (Slot slot : slots) {
            String day = slot.startsAt().substring(0, 10);
            int count = counts.merge(day, 1, Integer::sum);
            if (count <= perDay && chosen.size() < total) {
                chosen.add(slot);
            }
        }
        return chosen;
    }

    private ObjectNode bookTest(String phone, LlmPart.ToolUse use) {
        JsonNode input = use.input();
        String slotId = required(input, "slot_id");
        String testCode = required(input, "test_code");
        String patientName = required(input, "patient_name");

        // Guardrail: the slot must have been offered to this phone number.
        Integer offered = jdbc.queryForObject(
                "SELECT COUNT(*) FROM offered_slots WHERE phone_e164 = ? AND slot_id = ?",
                Integer.class, phone, slotId);
        if (offered == null || offered == 0) {
            return error("SLOT_NOT_OFFERED");
        }

        UUID bookingId = UUID.nameUUIDFromBytes(use.id().getBytes(StandardCharsets.UTF_8));
        BookingResult booking = bookings.book(phone, bookingId, slotId, testCode, patientName);
        ObjectNode result = json.createObjectNode().put("status", booking.status());
        if (booking.bookingRef() != null) {
            result.put("booking_ref", booking.bookingRef());
        }
        return result;
    }

    private ObjectNode handoff(JsonNode input) {
        required(input, "reason");
        return json.createObjectNode().put("status", "HANDED_OFF");
    }

    private ObjectNode error(String code) {
        return json.createObjectNode().put("status", code);
    }

    private static String required(JsonNode input, String field) {
        JsonNode node = input == null ? null : input.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new InvalidToolInput();
        }
        return node.asText().trim();
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidToolInput();
        }
    }

    /** Tool input that fails validation. The model gets an error result and can correct itself. */
    private static final class InvalidToolInput extends RuntimeException {
        InvalidToolInput() {
            super(null, null, false, false);
        }
    }
}
