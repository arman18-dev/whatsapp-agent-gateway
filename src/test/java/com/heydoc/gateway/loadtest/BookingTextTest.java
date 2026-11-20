package com.heydoc.gateway.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The load test's booking requests must name a day the lab is open and a time it actually offers. */
class BookingTextTest {

    @Test
    void neverAsksForSundayAndKeepsSaturdayBeforeOneOClock() {
        for (int seed = 0; seed < 800; seed++) {
            String text = LoadTest.bookingText(seed, seed % 325);
            LocalDate day = LocalDate.parse(text.substring(text.indexOf("on ") + 3, text.indexOf("on ") + 13));
            assertThat(day.getDayOfWeek()).isNotEqualTo(DayOfWeek.SUNDAY);
            LocalTime time = LocalTime.parse(text.substring(text.indexOf(" at ") + 4, text.indexOf(" at ") + 9));
            assertThat(time).isBetween(LocalTime.of(8, 0), LocalTime.of(17, 30));
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY) {
                assertThat(time).isBeforeOrEqualTo(LocalTime.of(12, 30));
            }
        }
    }

    @Test
    void timesAreSpreadNotAllNineAm() {
        Set<String> times = new HashSet<>();
        for (int seed = 0; seed < 800; seed++) {
            String text = LoadTest.bookingText(seed, seed % 325);
            times.add(text.substring(text.indexOf(" at ") + 4, text.indexOf(" at ") + 9));
        }
        assertThat(times.size()).isGreaterThan(10);
    }

    @Test
    void namesThePatientSoTheBookingIsComplete() {
        assertThat(LoadTest.bookingText(3, 7)).contains("My name is Load Patient 7.");
    }
}
