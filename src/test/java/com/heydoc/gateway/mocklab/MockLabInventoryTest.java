package com.heydoc.gateway.mocklab;

import static org.assertj.core.api.Assertions.assertThat;

import com.heydoc.gateway.config.MockLabProperties;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The mock lab's calendar: weekdays 08:00-18:00, Saturday until 13:00, closed Sunday, every 30 minutes. */
class MockLabInventoryTest {

    private final MockLabController controller = new MockLabController(
            new MockLabState(),
            new MockLabFaults(new MockLabProperties(0, 0, 0, 0, 0, 0)));

    @Test
    void sundayHasNoSlots() throws InterruptedException {
        // 2026-11-22 is a Sunday.
        List<Map<String, Object>> slots = controller.slots("THYROID", "2026-11-22", "2026-11-22");
        assertThat(slots).isEmpty();
    }

    @Test
    void weekdaysRunEightToSixEverythirtyMinutes() throws InterruptedException {
        // 2026-11-23 is a Monday: 08:00 through 17:30 is 20 slots.
        List<Map<String, Object>> slots = controller.slots("THYROID", "2026-11-23", "2026-11-23");
        assertThat(slots).hasSize(20);
        assertThat(slots.get(0).get("starts_at")).isEqualTo("2026-11-23T08:00:00+05:30");
        assertThat(slots.get(19).get("starts_at")).isEqualTo("2026-11-23T17:30:00+05:30");
    }

    @Test
    void saturdayEndsAtOneOClock() throws InterruptedException {
        // 2026-11-28 is a Saturday: 08:00 through 12:30 is 10 slots.
        List<Map<String, Object>> slots = controller.slots("THYROID", "2026-11-28", "2026-11-28");
        assertThat(slots).hasSize(10);
        assertThat(slots.get(9).get("starts_at")).isEqualTo("2026-11-28T12:30:00+05:30");
    }

    @Test
    void slotIdsAreUniqueAcrossDays() throws InterruptedException {
        List<Map<String, Object>> slots = controller.slots("THYROID", "2026-11-23", "2026-11-28");
        long distinct = slots.stream().map(s -> s.get("slot_id")).distinct().count();
        assertThat(distinct).isEqualTo(slots.size());
    }

    @Test
    void requestsAreCappedAtFourteenDays() throws InterruptedException {
        List<Map<String, Object>> slots = controller.slots("THYROID", "2026-11-23", "2026-12-31");
        LocalDate lastDay = LocalDate.of(2026, 12, 6);  // 2026-11-23 plus 13 days
        for (Map<String, Object> slot : slots) {
            String day = ((String) slot.get("starts_at")).substring(0, 10);
            assertThat(LocalDate.parse(day)).isBeforeOrEqualTo(lastDay);
            LocalTime time = LocalTime.parse(((String) slot.get("starts_at")).substring(11, 16));
            assertThat(time).isBefore(LocalTime.of(18, 0));
        }
    }
}
