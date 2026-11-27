package bank.pbp.common.market;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * When a payment scheme accepts payments from this bank: always (instant schemes such as Faster Payments), or on
 * business days between an opening time and the bank's cut-off, local time (CHAPS in the UK).
 */
public record OperatingWindow(boolean always, LocalTime opens, LocalTime cutOff) {

    public static final OperatingWindow ALWAYS = new OperatingWindow(true, null, null);

    public static OperatingWindow businessHours(LocalTime opens, LocalTime cutOff) {
        if (!opens.isBefore(cutOff)) {
            throw new IllegalArgumentException("opening time " + opens + " must be before cut-off " + cutOff);
        }
        return new OperatingWindow(false, opens, cutOff);
    }

    public boolean isOpen(Instant now, ZoneId zone, BusinessCalendar calendar) {
        if (always) {
            return true;
        }
        ZonedDateTime t = now.atZone(zone);
        return calendar.isBusinessDay(t.toLocalDate())
                && !t.toLocalTime().isBefore(opens) && t.toLocalTime().isBefore(cutOff);
    }

    /** The next time the window opens, or {@code now} when it is open. */
    public Instant nextOpening(Instant now, ZoneId zone, BusinessCalendar calendar) {
        if (isOpen(now, zone, calendar)) {
            return now;
        }
        ZonedDateTime t = now.atZone(zone);
        LocalDate day = t.toLocalDate();
        if (!calendar.isBusinessDay(day) || !t.toLocalTime().isBefore(opens)) {
            day = day.plusDays(1);
        }
        return calendar.onOrAfter(day).atTime(opens).atZone(zone).toInstant();
    }

    public Duration waitFrom(Instant now, ZoneId zone, BusinessCalendar calendar) {
        return Duration.between(now, nextOpening(now, zone, calendar));
    }
}
