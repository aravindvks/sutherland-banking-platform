package bank.pbp.common.market;

import java.math.BigDecimal;

/**
 * What the router needs to choose a payment scheme.
 *
 * @param ownBank         this bank's own code in the market's identifier scheme: its sort code in the UK
 *                        (pbp.bank.sort-code)
 * @param creditor        the payee's account; null when the command carried none
 * @param requestedScheme the scheme the customer chose, if any (CHAPS in the UK); null for the default
 * @param amount          the amount in the market's currency; null when not known
 */
public record PaymentIntent(String ownBank, AccountIdentifier creditor, String requestedScheme, BigDecimal amount) {
}
