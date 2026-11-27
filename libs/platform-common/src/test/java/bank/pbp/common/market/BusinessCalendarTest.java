package bank.pbp.common.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BusinessCalendarTest {

    private final BusinessCalendar uk = BusinessCalendar.saturdaySunday(Set.of(LocalDate.parse("2026-12-25"),
            LocalDate.parse("2026-12-28")));

    @Test
    void weekdaysAreBusinessDaysWeekendsAndHolidaysAreNot() {
        assertThat(uk.isBusinessDay(LocalDate.parse("2026-12-24"))).isTrue();   // Thursday
        assertThat(uk.isBusinessDay(LocalDate.parse("2026-12-25"))).isFalse();  // Christmas Day
        assertThat(uk.isBusinessDay(LocalDate.parse("2026-12-26"))).isFalse();  // Saturday
        assertThat(uk.isBusinessDay(LocalDate.parse("2026-12-27"))).isFalse();  // Sunday
        assertThat(uk.isBusinessDay(LocalDate.parse("2026-12-28"))).isFalse();  // Boxing Day substitute
    }

    @Test
    void onOrAfterSkipsToTheNextBusinessDay() {
        assertThat(uk.onOrAfter(LocalDate.parse("2026-12-24"))).isEqualTo("2026-12-24");
        assertThat(uk.onOrAfter(LocalDate.parse("2026-12-25"))).isEqualTo("2026-12-29");
    }

    @Test
    void weekendIsConfigurable() {
        BusinessCalendar fridayOff = new BusinessCalendar(Set.of(DayOfWeek.FRIDAY), Set.of());
        assertThat(fridayOff.isBusinessDay(LocalDate.parse("2026-11-27"))).isFalse();   // Friday
        assertThat(fridayOff.isBusinessDay(LocalDate.parse("2026-11-28"))).isTrue();    // Saturday
    }
}
