package bank.pbp.common.market;

import java.math.BigDecimal;

/**
 * Limits in the market's currency.
 *
 * @param singlePayment   largest same-bank or instant payment
 * @param reviewThreshold payments at or above this are held for a member of staff to approve
 * @param dailyCardSpend  spending control for new credit cards (the credit limit itself is the card processor's)
 */
public record Limits(BigDecimal singlePayment, BigDecimal reviewThreshold, BigDecimal dailyCardSpend) {
}
