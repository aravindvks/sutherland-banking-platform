package bank.pbp.orchestrator.schedules;

import static org.assertj.core.api.Assertions.assertThat;

import bank.pbp.orchestrator.payments.ChapsWindow;
import bank.pbp.orchestrator.schedules.ScheduleWorkflow.ScheduleRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SchedulesTest {

    private static ScheduleRequest request(String kind, String frequency, String start, String end) {
        return new ScheduleRequest("0192f1c5-0000-7000-8000-00000000000a", "0192f1c4-0000-7000-8000-000000000001",
                "COR-PAY-7K2M9QXA4B1D", kind, frequency, start, end, "ACC-5501", "Ravi Kumar", "200001", "41550198",
                "10.00", "GBP", "Rent", null, "MATCH");
    }

    @Test
    void monthlyOnThe31stUsesTheLastDayOfShorterMonthsWithoutDrifting() {
        ScheduleRequest r = request("STANDING_ORDER", "MONTHLY", "2027-01-31", null);
        assertThat(Schedules.dueDate(r, 0)).isEqualTo(LocalDate.parse("2027-01-31"));
        assertThat(Schedules.dueDate(r, 1)).isEqualTo(LocalDate.parse("2027-02-28"));
        assertThat(Schedules.dueDate(r, 2)).isEqualTo(LocalDate.parse("2027-03-31"));
    }

    @Test
    void weeklyStopsAfterTheEndDate() {
        ScheduleRequest r = request("STANDING_ORDER", "WEEKLY", "2026-11-09", "2026-11-23");
        assertThat(Schedules.dueDate(r, 2)).isEqualTo(LocalDate.parse("2026-11-23"));
        assertThat(Schedules.dueDate(r, 3)).isNull();
    }

    @Test
    void oneOffHasOnePayment() {
        ScheduleRequest r = request("ONE_OFF", null, "2026-12-01", null);
        assertThat(Schedules.dueDate(r, 0)).isEqualTo(LocalDate.parse("2026-12-01"));
        assertThat(Schedules.dueDate(r, 1)).isNull();
    }

    @Test
    void paymentsGoOutAtSixInTheMorningUkTime() {
        // 1 July is British Summer Time (UTC+1); 1 December is GMT.
        assertThat(Schedules.dueInstant(LocalDate.parse("2027-07-01"))).isEqualTo(Instant.parse("2027-07-01T05:00:00Z"));
        assertThat(Schedules.dueInstant(LocalDate.parse("2026-12-01"))).isEqualTo(Instant.parse("2026-12-01T06:00:00Z"));
    }

    @Test
    void chapsOpensOnTheNextBusinessDayAfterTheCutOff() {
        ChapsWindow w = new ChapsWindow(LocalTime.of(6, 0), LocalTime.of(17, 30), Set.of(LocalDate.parse("2026-12-25")));
        // Thursday 24 December 2026, 18:00 GMT: closed; Friday is Christmas Day, then the weekend.
        Instant evening = Instant.parse("2026-12-24T18:00:00Z");
        assertThat(w.isOpen(evening)).isFalse();
        assertThat(w.nextOpening(evening)).isEqualTo(Instant.parse("2026-12-28T06:00:00Z"));
        // Monday 9 November 2026, 10:00 GMT: open.
        assertThat(w.isOpen(Instant.parse("2026-11-09T10:00:00Z"))).isTrue();
        // Saturday morning waits for Monday.
        assertThat(w.nextOpening(Instant.parse("2026-11-07T09:00:00Z"))).isEqualTo(Instant.parse("2026-11-09T06:00:00Z"));
    }
}
