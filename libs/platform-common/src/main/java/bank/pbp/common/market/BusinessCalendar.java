package bank.pbp.common.market;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * A market's business days: not a weekend day and not a listed public holiday. Holidays are configuration,
 * refreshed yearly (in the UK, the England and Wales bank holidays).
 */
public record BusinessCalendar(Set<DayOfWeek> weekend, Set<LocalDate> holidays) {

    public BusinessCalendar {
        weekend = Set.copyOf(weekend);
        holidays = Set.copyOf(holidays);
    }

    /** Saturday and Sunday off, as in the UK, the US, Colombia, Jamaica and (since 2022) the UAE. */
    public static BusinessCalendar saturdaySunday(Set<LocalDate> holidays) {
        return new BusinessCalendar(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), holidays);
    }

    public boolean isBusinessDay(LocalDate day) {
        return !weekend.contains(day.getDayOfWeek()) && !holidays.contains(day);
    }

    /** {@code day} itself if it is a business day, otherwise the next one. */
    public LocalDate onOrAfter(LocalDate day) {
        LocalDate d = day;
        while (!isBusinessDay(d)) {
            d = d.plusDays(1);
        }
        return d;
    }
}
