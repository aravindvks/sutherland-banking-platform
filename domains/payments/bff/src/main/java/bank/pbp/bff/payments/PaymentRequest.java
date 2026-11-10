package bank.pbp.bff.payments;

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
        @Pattern(regexp = "FPS|CHAPS") String scheme) {

    public record Creditor(
            @NotBlank @Size(max = 140) String name,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "must be 6 digits") String sortCode,
            @NotBlank @Pattern(regexp = "\\d{8}", message = "must be 8 digits") String accountNumber) {
    }

    public record Money(
            @NotBlank @Pattern(regexp = "\\d{1,15}\\.\\d{2}", message = "must be a decimal string like 500.00") String value,
            @NotBlank @Pattern(regexp = "GBP", message = "must be GBP in the UK cell") String currency) {
    }
}
