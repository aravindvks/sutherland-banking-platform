package bank.pbp.market.uk;

import static org.assertj.core.api.Assertions.assertThat;

import bank.pbp.common.market.AccountIdentifier;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The same checks as PaymentRequest.Creditor today: 6-digit sort code, 8-digit account number. */
class UkSortCodeSchemeTest {

    private final UkSortCodeScheme scheme = new UkSortCodeScheme();

    @Test
    void validSortCodeAndAccountNumber() {
        assertThat(scheme.isValid(UkSortCodeScheme.of("040004", "12345678"))).isTrue();
    }

    @Test
    void sameMessagesAsThePaymentApi() {
        assertThat(scheme.problems(UkSortCodeScheme.of("04-00-04", "1234567")))
                .containsEntry("sortCode", "must be 6 digits")
                .containsEntry("accountNumber", "must be 8 digits");
    }

    @Test
    void missingFieldIsAProblem() {
        assertThat(scheme.problems(new AccountIdentifier("UK_SORT_CODE", Map.of("sortCode", "040004"))))
                .containsOnlyKeys("accountNumber");
    }

    @Test
    void otherSchemesAreRefused() {
        assertThat(scheme.problems(new AccountIdentifier("IBAN", Map.of("iban", "AE070331234567890123456"))))
                .containsOnlyKeys("scheme");
    }

    @Test
    void formFieldsInOrder() {
        assertThat(scheme.fields()).extracting(f -> f.label()).containsExactly("Sort code", "Account number");
    }
}
