package bank.pbp.common.market;

/**
 * The market's check that a payee's name matches the account, before money moves. Results everywhere are MATCH,
 * CLOSE_MATCH, NO_MATCH or UNAVAILABLE.
 *
 * @param name what customers and staff call it: "Confirmation of Payee" in the UK
 */
public record NameCheck(String name) {
}
