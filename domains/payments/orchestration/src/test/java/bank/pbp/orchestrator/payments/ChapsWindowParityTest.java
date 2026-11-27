package bank.pbp.orchestrator.payments;

import static org.assertj.core.api.Assertions.assertThat;

import bank.pbp.common.market.BusinessCalendar;
import bank.pbp.common.market.OperatingWindow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Market packs, Stage 1 (docs/design/market-packs.md): the market pack's OperatingWindow must answer exactly as
 * ChapsWindow does before step 5 replaces one with the other. Every 15 minutes across a month with a weekend
 * holiday run, a clock change and both sides of the cut-off.
 */
class ChapsWindowParityTest {

    private static final Set<LocalDate> HOLIDAYS = Set.of(LocalDate.parse("2026-12-25"), LocalDate.parse("2026-12-28"),
            LocalDate.parse("2027-01-01"), LocalDate.parse("2027-03-26"), LocalDate.parse("2027-03-29"));
    private static final LocalTime OPENS = LocalTime.of(6, 0);
    private static final LocalTime CUT_OFF = LocalTime.of(17, 30);

    private final ChapsWindow today = new ChapsWindow(OPENS, CUT_OFF, HOLIDAYS);
    private final OperatingWindow pack = OperatingWindow.businessHours(OPENS, CUT_OFF);
    private final BusinessCalendar calendar = BusinessCalendar.saturdaySunday(HOLIDAYS);

    @Test
    void sameAnswersOverChristmasNewYearAndEaster() {
        check(Instant.parse("2026-12-20T00:00:00Z"), Instant.parse("2027-01-06T00:00:00Z"));
        check(Instant.parse("2027-03-24T00:00:00Z"), Instant.parse("2027-04-02T00:00:00Z"));  // includes 28 March, clocks forward
    }

    private void check(Instant from, Instant to) {
        for (Instant t = from; t.isBefore(to); t = t.plus(Duration.ofMinutes(15))) {
            assertThat(pack.isOpen(t, ChapsWindow.UK, calendar)).as("isOpen at %s", t).isEqualTo(today.isOpen(t));
            assertThat(pack.nextOpening(t, ChapsWindow.UK, calendar)).as("nextOpening at %s", t)
                    .isEqualTo(today.nextOpening(t));
        }
    }
}
