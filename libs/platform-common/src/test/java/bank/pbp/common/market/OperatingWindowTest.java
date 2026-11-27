package bank.pbp.common.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OperatingWindowTest {

    private static final ZoneId UK = ZoneId.of("Europe/London");
    private final BusinessCalendar calendar = BusinessCalendar.saturdaySunday(Set.of(LocalDate.parse("2026-12-25")));
    private final OperatingWindow chaps = OperatingWindow.businessHours(LocalTime.of(6, 0), LocalTime.of(17, 30));

    private static Instant uk(String localDateTime) {
        return java.time.LocalDateTime.parse(localDateTime).atZone(UK).toInstant();
    }

    @Test
    void openOnBusinessDaysFromOpeningUntilJustBeforeCutOff() {
        assertThat(chaps.isOpen(uk("2026-12-24T05:59:59"), UK, calendar)).isFalse();
        assertThat(chaps.isOpen(uk("2026-12-24T06:00:00"), UK, calendar)).isTrue();
        assertThat(chaps.isOpen(uk("2026-12-24T17:29:59"), UK, calendar)).isTrue();
        assertThat(chaps.isOpen(uk("2026-12-24T17:30:00"), UK, calendar)).isFalse();
        assertThat(chaps.isOpen(uk("2026-12-25T10:00:00"), UK, calendar)).isFalse();  // holiday
        assertThat(chaps.isOpen(uk("2026-12-26T10:00:00"), UK, calendar)).isFalse();  // Saturday
    }

    @Test
    void nextOpeningIsNowWhenOpenOtherwiseTheNextBusinessMorning() {
        Instant open = uk("2026-12-24T10:00:00");
        assertThat(chaps.nextOpening(open, UK, calendar)).isEqualTo(open);
        assertThat(chaps.nextOpening(uk("2026-12-24T05:00:00"), UK, calendar)).isEqualTo(uk("2026-12-24T06:00:00"));
        // Christmas Day is this calendar's only holiday, so after Thursday's cut-off the next opening is Monday 28 December.
        assertThat(chaps.nextOpening(uk("2026-12-24T18:00:00"), UK, calendar)).isEqualTo(uk("2026-12-28T06:00:00"));
        assertThat(chaps.waitFrom(uk("2026-12-24T05:00:00"), UK, calendar)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void localTimeFollowsSummerTime() {
        // 1 July 2026, 05:30 UTC is 06:30 in London (BST): open.
        assertThat(chaps.isOpen(Instant.parse("2026-07-01T05:30:00Z"), UK, calendar)).isTrue();
    }

    @Test
    void alwaysOpenSchemesAreOpenEveryDay() {
        assertThat(OperatingWindow.ALWAYS.isOpen(uk("2026-12-25T03:00:00"), UK, calendar)).isTrue();
    }

    @Test
    void openingMustBeBeforeCutOff() {
        assertThatThrownBy(() -> OperatingWindow.businessHours(LocalTime.of(18, 0), LocalTime.of(6, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
