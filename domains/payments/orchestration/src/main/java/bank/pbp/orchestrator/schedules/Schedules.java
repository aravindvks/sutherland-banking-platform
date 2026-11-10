package bank.pbp.orchestrator.schedules;

import bank.pbp.orchestrator.schedules.ScheduleWorkflow.ScheduleRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Due dates for standing orders. Monthly payments keep the day of the first payment and move to
 * the last day of shorter months (31 January → 28 February → 31 March), counting from the start
 * date each time so they never drift. Payments go out at 06:00 UK time on the due date; Faster
 * Payments run every day, so weekends are not skipped.
 */
public final class Schedules {

    public static final ZoneId UK = ZoneId.of("Europe/London");
    public static final LocalTime RUN_TIME = LocalTime.of(6, 0);

    private Schedules() {
    }

    /** Due date of run {@code index} (0 = first), or null when the schedule has no run left. */
    public static LocalDate dueDate(ScheduleRequest r, int index) {
        LocalDate start = LocalDate.parse(r.startDate());
        LocalDate due;
        if ("ONE_OFF".equals(r.kind())) {
            due = index == 0 ? start : null;
        } else if ("WEEKLY".equals(r.frequency())) {
            due = start.plusWeeks(index);
        } else {
            due = start.plusMonths(index);
        }
        if (due != null && r.endDate() != null && !r.endDate().isBlank() && due.isAfter(LocalDate.parse(r.endDate()))) {
            return null;
        }
        return due;
    }

    public static Instant dueInstant(LocalDate due) {
        return due.atTime(RUN_TIME).atZone(UK).toInstant();
    }
}
