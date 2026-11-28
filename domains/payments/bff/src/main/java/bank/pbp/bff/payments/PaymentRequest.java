package bank.pbp.bff.payments;

import bank.pbp.common.market.MarketCurrency;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /v1/payments body (LLD section 2). Amounts are decimal strings, never floating point.
 *
 * @param copResult the Confirmation of Payee result the customer saw; CLOSE_MATCH means they chose
 *                  to pay the name they typed anyway. The orchestrator checks again itself.
 * @param scheme    FPS (default) or CHAPS for other banks; ignored for same-bank payments
 */
public record PaymentRequest(
        @NotBlank String debtorAccount,
        @NotNull @Valid Creditor creditor,
        @NotNull @Valid Money amount,
        String purpose,
        @Size(max = 18) String remittanceInfo,
        @Pattern(regexp = "MATCH|CLOSE_MATCH|NO_MATCH|UNAVAILABLE") String copResult,
        @bank.pbp.common.market.MarketScheme String scheme) {

    /**
     * The payee. The account comes either as {@code account} (the market's identifier) or as the original
     * {@code sortCode} and {@code accountNumber} ({@link PayeeAccount}); the market pack checks it.
     */
    @ValidAccount
    public record Creditor(
            @NotBlank @Size(max = 140) String name,
            String sortCode,
            String accountNumber,
            @Valid @JsonInclude(JsonInclude.Include.NON_NULL) AccountBody account) implements PayeeAccount {
    }

    public record Money(
            @NotBlank @Pattern(regexp = "\\d{1,15}\\.\\d{2}", message = "must be a decimal string like 500.00") String value,
            @NotBlank @MarketCurrency String currency) {
    }
}
