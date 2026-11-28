package bank.pbp.essence.gateway;

/**
 * Everything the bank needs from Finastra Fusion Essence, in our own terms. Two connectors
 * implement it, chosen by {@code vendor.essence.mode}: {@code simulator} (default) and
 * {@code sandbox}. Nothing outside this adapter knows which one is running.
 */
public interface EssenceGateway {

    enum Lane {
        /** Card authorisations: short timeout inside Thredd's limit. */
        EHI,
        /** Everything else: 10 s timeout. */
        STANDARD
    }

    Party createParty(String name, String partyType, String idempotencyKey);

    OpenedAccount openAccount(String partyId, String name, String productCode, String idempotencyKey);

    Balance balance(String accountId, Lane lane, String scenario);

    Hold placeHold(String accountId, String amount, String currency, String reference, String idempotencyKey,
                   String scenario);

    /** Presentment: converts the hold into a posted debit. Returns the posting reference. */
    String captureHold(String holdId, String amount);

    void releaseHold(String holdId);

    /** Same-bank transfer. Returns the posting reference. */
    String transfer(TransferInstruction instruction, String scenario);

    /** Outbound payment on the scheme the bank chose (FPS or CHAPS in the UK); status arrives by webhook. */
    PaymentSubmission submitPayment(PaymentInstruction instruction, String scenario);

    /** Confirmation of Payee: does the name match the account holder at that sort code and account? */
    CopResult confirmPayee(String name, String sortCode, String accountNumber, String scenario);

    /** A charge from the bank's tariff (e.g. the CHAPS fee). Returns the posting reference. */
    String chargeFee(String accountId, String amount, String currency, String reference, String idempotencyKey);

    /** Direct Debit collection presented by Bacs: pay it (debit the account). Returns the posting reference. */
    String payCollection(String collectionRef);

    /** Direct Debit collection returned unpaid (ARUDD) with a Bacs reason code, e.g. 0 refer to payer. */
    void returnCollection(String collectionRef, String reasonCode);

    /** Direct Debit Guarantee refund to the payer and indemnity claim (DDICA) on the originator. */
    String indemnityClaim(String collectionRef, String reasonCode);

    /** The payer cancelled a Direct Debit Instruction; Essence tells the originator (ADDACS). */
    void cancelMandate(String mandateRef, String reasonCode);

    /** All postings for a business date (reconciliation, 30 s timeout per LLD). */
    java.util.List<Posting> postings(String date);

    record Posting(String postingId, String date, String accountId, String amount, String ref, String type) {
    }

    record Party(String partyId) {
    }

    record OpenedAccount(String accountId, String sortCode, String accountNumber) {
    }

    record Balance(String accountId, String available, String ledger, String currency) {
    }

    record Hold(String holdId, String available) {
    }

    record PaymentSubmission(String finastraRef, String status, String scheme) {
    }

    /** result: MATCH, CLOSE_MATCH (matchedName set), NO_MATCH or UNAVAILABLE; reasonCode is the CoP code. */
    record CopResult(String result, String matchedName, String reasonCode) {
    }

    record TransferInstruction(String debitAccount, String creditorSortCode, String creditorAccountNumber,
                               String amount, String currency, String reference, String idempotencyKey) {
    }

    /**
     * scheme: chosen by the orchestrator from the market's schemes (FPS or CHAPS in the UK). The payee is the sort
     * code and account number in the UK, or creditorAccount ({scheme, fields}) in other markets.
     */
    record PaymentInstruction(String debitAccount, String creditorName, String creditorSortCode,
                              String creditorAccountNumber, String amount, String currency, String reference,
                              String endToEndId, String idempotencyKey, String scheme,
                              java.util.Map<String, Object> creditorAccount) {
    }
}
