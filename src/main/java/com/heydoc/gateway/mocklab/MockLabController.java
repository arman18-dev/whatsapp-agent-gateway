package com.heydoc.gateway.mocklab;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fake lab booking API (synthetic data only). Failures are injected by {@link MockLabFaults}.
 */
@RestController
@Profile("mock-lab")
public class MockLabController {

    private static final int[] HOURS = {9, 10, 11, 14, 15};
    private static final int DAYS = 3;

    private final MockLabState state;
    private final MockLabFaults faults;

    public MockLabController(MockLabState state, MockLabFaults faults) {
        this.state = state;
        this.faults = faults;
    }

    @GetMapping("/slots")
    public List<Map<String, Object>> slots(@RequestParam("test_code") String testCode,
                                           @RequestParam("from") String from,
                                           @RequestParam("to") String to) throws InterruptedException {
        if (faults.shouldFail()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        LocalDate start = parseDate(from);
        List<Map<String, Object>> slots = new ArrayList<>();
        for (int day = 0; day < DAYS; day++) {
            LocalDate date = start.plusDays(day);
            for (int hour : HOURS) {
                String slotId = "SLT-" + testCode + "-" + date.toString().replace("-", "") + "-" + hour;
                slots.add(Map.of(
                        "slot_id", slotId,
                        "test_code", testCode,
                        "starts_at", date + "T" + String.format("%02d", hour) + ":00:00+05:30",
                        "lab_site", "MAIN"));
            }
        }
        return slots;
    }

    @PostMapping("/bookings")
    public ResponseEntity<Map<String, Object>> book(@RequestHeader("Idempotency-Key") String key,
                                                    @RequestBody JsonNode body) throws InterruptedException {
        if (faults.shouldFail()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        MockLabState.Booking booking = state.book(key, body.path("slot_id").asText());
        switch (booking.result()) {
            case FENCED:
                return ResponseEntity.status(422).body(Map.of("error", "KEY_FENCED"));
            case SLOT_TAKEN:
                return ResponseEntity.status(409).body(Map.of("error", "SLOT_TAKEN"));
            default:
                break;
        }
        if (faults.shouldDropAfterCommit()) {
            // Committed, but the response is lost: the caller must retry with the same key.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return ResponseEntity.status(booking.result() == MockLabState.BookResult.CREATED ? 201 : 200)
                .body(Map.of("booking_ref", booking.bookingRef()));
    }

    @GetMapping("/bookings")
    public ResponseEntity<Map<String, Object>> lookup(@RequestParam("idempotency_key") String key)
            throws InterruptedException {
        if (faults.shouldFail()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return state.lookup(key)
                .map(ref -> ResponseEntity.ok(Map.<String, Object>of("booking_ref", ref)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "NOT_FOUND")));
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date must be YYYY-MM-DD");
        }
    }
}
