package bank.pbp.common.market;

/**
 * The market's Direct Debit scheme.
 *
 * @param name             e.g. "Bacs Direct Debit"
 * @param originatorIdName what the originator's identifier is called: "Service user number" for Bacs
 * @param guarantee        the customer protection, if the scheme has a named one: "Direct Debit Guarantee"
 */
public record DirectDebitScheme(String name, String originatorIdName, String guarantee) {
}
