package com.heydoc.gateway.booking;

import com.heydoc.gateway.lab.BookOutcome;
import com.heydoc.gateway.lab.LabClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Booking state machine (PLAN.md section 5):
 * HELD is written before the lab is called; CONFIRMED, RELEASED, or UNKNOWN follow from the outcome.
 * The partial unique index on bookings(slot_id) is the final guard against double booking.
 */
@Service
@Profile("worker")
public class BookingService {

    public record BookingResult(String status, String bookingRef) {
        public static BookingResult confirmed(String ref) {
            return new BookingResult("CONFIRMED", ref);
        }

        public static BookingResult of(String status) {
            return new BookingResult(status, null);
        }
    }

    private final JdbcTemplate jdbc;
    private final LabClient lab;
    private final MeterRegistry metrics;

    public BookingService(JdbcTemplate jdbc, LabClient lab, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.lab = lab;
        this.metrics = metrics;
    }

    /**
     * Books a slot for one tool call. The booking id is derived from the tool_use id, so a
     * re-run of the same tool call reuses the same lab key and cannot create a second booking.
     */
    public BookingResult book(String phone, UUID bookingId, String slotId, String testCode, String patientName) {
        BookingResult result = doBook(phone, bookingId, slotId, testCode, patientName);
        metrics.counter("booking_results_total", "status", result.status()).increment();
        return result;
    }

    private BookingResult doBook(String phone, UUID bookingId, String slotId, String testCode, String patientName) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT status, lab_booking_ref FROM bookings WHERE id = ?", bookingId);
        if (!existing.isEmpty()) {
            String status = (String) existing.get(0).get("status");
            if ("CONFIRMED".equals(status)) {
                return BookingResult.confirmed((String) existing.get(0).get("lab_booking_ref"));
            }
            if ("RELEASED".equals(status)) {
                return BookingResult.of("LAB_UNAVAILABLE");
            }
            // HELD or UNKNOWN: a resumed attempt. Fall through and reuse the same key.
        } else {
            try {
                jdbc.update("""
                        INSERT INTO bookings (id, phone_e164, slot_id, test_code, patient_name, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 'HELD', now(), now())
                        """, bookingId, phone, slotId, testCode, patientName);
            } catch (DuplicateKeyException e) {
                // The partial unique index: another active booking holds this slot.
                return BookingResult.of("SLOT_TAKEN");
            }
        }

        BookOutcome outcome = lab.book(bookingId, slotId, testCode, patientName);
        switch (outcome.kind()) {
            case CONFIRMED:
                setStatus(bookingId, "CONFIRMED", outcome.bookingRef());
                return BookingResult.confirmed(outcome.bookingRef());
            case SLOT_TAKEN:
                setStatus(bookingId, "RELEASED", null);
                return BookingResult.of("SLOT_TAKEN");
            case REFUSED:
                setStatus(bookingId, "RELEASED", null);
                return BookingResult.of("LAB_UNAVAILABLE");
            default:
                // Outcome unknown: the lab may have committed. Keep the slot held; the reconciler resolves it.
                setStatus(bookingId, "UNKNOWN", null);
                return BookingResult.of("PENDING_CONFIRMATION");
        }
    }

    /** Only moves rows that are still HELD or UNKNOWN; a CONFIRMED booking is never overwritten. */
    public void setStatus(UUID bookingId, String status, String labRef) {
        jdbc.update("""
                UPDATE bookings SET status = ?, lab_booking_ref = COALESCE(?, lab_booking_ref), updated_at = now()
                WHERE id = ? AND status IN ('HELD', 'UNKNOWN')
                """, status, labRef, bookingId);
    }
}
