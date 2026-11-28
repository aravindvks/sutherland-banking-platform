package bank.pbp.market.ae;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AeIbanSchemeTest {

    private final AeIbanScheme scheme = new AeIbanScheme();

    @Test
    void mod97CheckDigits() {
        assertThat(AeIbanScheme.checksumValid("AE070331234567890123456")).isTrue();
        assertThat(AeIbanScheme.checksumValid("AE080331234567890123456")).isFalse();
        assertThat(AeIbanScheme.checksumValid("GB82WEST12345698765432")).isTrue();   // the ISO 13616 example
        assertThat(AeIbanScheme.checksumValid(null)).isFalse();
    }

    @Test
    void formatThenCheckDigits() {
        assertThat(scheme.problems(AeIbanScheme.of("AE070331234567890123456"))).isEmpty();
        assertThat(scheme.problems(AeIbanScheme.of("AE07033123456789012345"))).containsEntry("iban",
                "must be AE followed by 21 digits");
        assertThat(scheme.problems(AeIbanScheme.of("GB82WEST12345698765432"))).containsEntry("iban",
                "must be AE followed by 21 digits");
        assertThat(scheme.problems(AeIbanScheme.of("AE080331234567890123456"))).containsEntry("iban",
                "is not a valid IBAN (check digits)");
    }

    @Test
    void bankCodeIsCharactersFiveToSeven() {
        assertThat(AeIbanScheme.bankCode("AE070331234567890123456")).isEqualTo("033");
        assertThat(AeIbanScheme.bankCode("AE07")).isNull();
    }
}
