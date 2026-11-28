package bank.pbp.market.us;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class UsAbaSchemeTest {

    private final UsAbaScheme scheme = new UsAbaScheme();

    @Test
    void routingNumberCheckDigit() {
        assertThat(UsAbaScheme.checksumValid("011000015")).isTrue();   // Federal Reserve Bank of Boston
        assertThat(UsAbaScheme.checksumValid("021000021")).isTrue();
        assertThat(UsAbaScheme.checksumValid("011000016")).isFalse();
        assertThat(UsAbaScheme.checksumValid("01100001")).isFalse();
        assertThat(UsAbaScheme.checksumValid(null)).isFalse();
    }

    @Test
    void validAccount() {
        assertThat(scheme.problems(UsAbaScheme.of("011000015", "000123456789", "CHECKING"))).isEmpty();
        assertThat(scheme.problems(UsAbaScheme.of("021000021", "1234", "SAVINGS"))).isEmpty();
    }

    @Test
    void eachFieldIsChecked() {
        assertThat(scheme.problems(UsAbaScheme.of("011000016", "123", "BROKERAGE"))).containsOnly(
                Map.entry("routingNumber", "is not a valid routing number (check digit)"),
                Map.entry("accountNumber", "must be 4 to 17 digits"),
                Map.entry("accountType", "must be CHECKING or SAVINGS"));
        assertThat(scheme.problems(UsAbaScheme.of("01100001", "123456789012345678", "CHECKING")))
                .containsEntry("routingNumber", "must be 9 digits")
                .containsEntry("accountNumber", "must be 4 to 17 digits");
    }

    @Test
    void ukAccountIsNotAUsAccount() {
        assertThat(scheme.problems(new bank.pbp.common.market.AccountIdentifier("UK_SORT_CODE",
                Map.of("sortCode", "040004", "accountNumber", "12345678"))))
                .containsOnlyKeys("scheme");
    }
}
