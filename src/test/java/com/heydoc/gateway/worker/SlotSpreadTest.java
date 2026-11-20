package com.heydoc.gateway.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.heydoc.gateway.lab.Slot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** check_slots offers at most 5 slots per day and 15 in total, so patients see several days. */
class SlotSpreadTest {

    @Test
    void capsEachDayAtFiveSlots() {
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            slots.add(new Slot("A" + i, "2026-11-23T" + String.format("%02d", 8 + i / 2) + ":"
                    + (i % 2 == 0 ? "00" : "30") + ":00+05:30", "MAIN"));
        }
        List<Slot> chosen = ToolExecutor.spreadAcrossDays(slots);
        assertThat(chosen).hasSize(5);
    }

    @Test
    void spreadsAcrossDaysUpToFifteenSlots() {
        List<Slot> slots = new ArrayList<>();
        for (int day = 23; day <= 28; day++) {
            for (int i = 0; i < 10; i++) {
                slots.add(new Slot("S" + day + "-" + i, "2026-11-" + day + "T09:00:00+05:30", "MAIN"));
            }
        }
        List<Slot> chosen = ToolExecutor.spreadAcrossDays(slots);
        assertThat(chosen).hasSize(15);
        long days = chosen.stream().map(s -> s.startsAt().substring(0, 10)).distinct().count();
        assertThat(days).isEqualTo(3);
    }

    @Test
    void keepsTheEarliestSlotsOfEachDay() {
        List<Slot> slots = List.of(
                new Slot("first", "2026-11-23T08:00:00+05:30", "MAIN"),
                new Slot("second", "2026-11-23T08:30:00+05:30", "MAIN"),
                new Slot("other", "2026-11-24T08:00:00+05:30", "MAIN"));
        List<Slot> chosen = ToolExecutor.spreadAcrossDays(slots);
        assertThat(chosen).extracting(Slot::slotId).containsExactly("first", "second", "other");
    }
}
