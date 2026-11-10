package bank.pbp.orchestrator.payments;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * When the bank accepts CHAPS payments: business days (Monday to Friday, not a listed bank
 * holiday) between the opening time and the customer cut-off, UK time. CHAPS itself settles
 * 06:00–18:00; the bank's cut-off sits before 18:00 so payments are sent in time.
 */
public final class ChapsWindow {

    public static final ZoneId UK = ZoneId.of("Europe/London");

    private final LocalTime opens;
    private final LocalTime cutOff;
    private final Set<LocalDate> holidays;

    public ChapsWindow(LocalTime opens, LocalTime cutOff, Set<LocalDate> holidays) {
        this.opens = opens;
        this.cutOff = cutOff;
        this.holidays = holidays;
    }

    public boolean isOpen(Instant now) {
        ZonedDateTime t = now.atZone(UK);
        return businessDay(t.toLocalDate()) && !t.toLocalTime().isBefore(opens) && t.toLocalTime().isBefore(cutOff);
    }

    /** The next time the window opens, or {@code now} when it is open. */
    public Instant nextOpening(Instant now) {
        if (isOpen(now)) {
            return now;
        }
        ZonedDateTime t = now.atZone(UK);
        LocalDate day = t.toLocalDate();
        if (!businessDay(day) || !t.toLocalTime().isBefore(opens)) {
            day = day.plusDays(1);
        }
        while (!businessDay(day)) {
            day = day.plusDays(1);
        }
        return day.atTime(opens).atZone(UK).toInstant();
    }

    public Duration waitFrom(Instant now) {
        return Duration.between(now, nextOpening(now));
    }

    private boolean businessDay(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(d);
    }
}
