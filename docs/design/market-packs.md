# Market packs: design

**Status: design, 27 November 2026. Nothing in this document is built yet.** The 12-week build is UK only; the US, Latin America, Caribbean and Middle East packs stay "upcoming" in every external document until a pack has run against real scheme connections in a client engagement.

## 1. What this is for

Today the UK's banking rules are written straight into the code: sort codes, Faster Payments, CHAPS, Bacs Direct Debits, Confirmation of Payee, GBP, UK time and England and Wales bank holidays. A bank in another country cannot use the platform without changing that code.

A **market pack** gathers one country's rules behind one interface, so the rest of the platform stops knowing which country it runs in. The work has two stages:

1. **Stage 1, the separation.** Move today's UK rules into a UK pack. UK behaviour does not change: every existing test and the five bank set-ups in CI must still pass.
2. **Stage 2, the packs.** Build a pack for one anchor country per region, running against simulators:

| Region | Anchor country | Why |
| --- | --- | --- |
| US | United States | One market; Increase is US-native |
| Latin America | Colombia | Vanti (embedded lending, migration from SAP) |
| Caribbean | Jamaica | JMMB; MCB's other jurisdictions follow as configuration |
| Middle East | United Arab Emirates | The largest digital-banking market in the region |

## 2. Decisions

| # | Decision | Why |
| --- | --- | --- |
| D1 | **One market per deployment**, chosen at deploy time (`PBP_MARKET=uk`), like the module switches | Data residency puts each country in its own cell anyway (runbooks: one deployment per country). It keeps every request single-market and avoids multi-currency ledgers |
| D2 | **Packs are code plus configuration**, not configuration alone | Identifier checks (US routing checksum, UAE IBAN) and scheme routing are logic; limits, fees, hours and holidays are configuration |
| D3 | **The interface lives in `libs/platform-common`** (`bank.pbp.common.market`); each pack is its own module, `markets/<code>` | Every service already depends on platform-common (CLAUDE.md); the build includes only the chosen pack, as it does for domain modules |
| D4 | **Adapters stay translators** | A pack decides which scheme a payment uses and what is valid; the Essence adapter still only translates to Finastra (CLAUDE.md: adapters translate, the orchestrator decides) |
| D5 | **Account identifiers become a typed value with a scheme**, not fixed `sortCode` and `accountNumber` fields | Every market identifies accounts differently (section 4.1) |
| D6 | **The web app asks the platform for the market** (`GET /v1/market`) and builds its forms from the answer | One web build serves every market; no UK text or field is hard-coded |
| D7 | **Additive database changes**: new columns beside the old, filled in, old ones dropped only after Stage 1 is proven | Never disturb the working system |

## 3. What is UK-specific today

Found by searching the code on 27 November 2026 (Java, TypeScript, SQL and YAML under `domains`, `services`, `libs`, `web` and `simulators`):

| UK rule | Files | Where |
| --- | --- | --- |
| Sort code and 8-digit account number | 30 | Payment and payee requests, onboarding, payment commands and listeners, schedules, the Essence adapter and simulator, the BFF schema, web forms |
| Faster Payments (FPS) | 18 | Payment workflow and activities, schedules, the Essence adapter, the orchestration schema (`V3__uk_payment_types.sql`), web |
| CHAPS (hours, cut-off, fee, limit) | 21 | `ChapsWindow`, payment workflow, recon, orchestrator config, web, staff portal |
| Bacs Direct Debits (SUN, Direct Debit Guarantee) | 13 | Direct Debit workflows and controllers, the `dd_mandate` table, recon, simulator |
| Confirmation of Payee | 3 | Essence gateway, simulator, payment workflow tests |
| GBP | 17 | Payment request validation (`must be GBP in the UK cell`), compliance, credit cards, Thredd adapter, recon, simulators, web |
| UK time (`Europe/London`) and bank holidays | 9 | `ChapsWindow`, schedules, Direct Debits, flow metrics, simulators, web |
| `en-GB` formatting and `£` | 6 and 9 | Web app, staff portal, credit card and payment workflow messages |
| Limits | 1 | Orchestrator config: £25,000 single payment, £1,000,000 CHAPS, £5,000 daily card spend |

Not UK-specific today, so no work: identity document types and company registries (Atfinity holds the KYC/KYB workflow), credit bureau names (behind the credit adapter), sanctions lists (behind compliance).

## 4. The interface

```java
package bank.pbp.common.market;

/** One country's banking rules. Exactly one is active per deployment (PBP_MARKET). */
public interface MarketPack {
    String code();                        // "uk", "us", "co", "jm", "ae"
    Currency currency();                  // GBP, USD, COP, JMD, AED
    ZoneId zone();                        // Europe/London, America/New_York, America/Bogota, America/Jamaica, Asia/Dubai
    Locale locale();                      // en-GB, en-US, es-CO, en-JM, en-AE
    BusinessCalendar calendar();          // weekends and holidays for this market
    AccountIdentifierScheme identifiers();  // how an account is written and checked (4.1)
    List<PaymentScheme> schemes();        // the rails, in the order the router prefers them (4.2)
    PaymentRouter router();               // picks the scheme for a payment (4.2)
    Optional<NameCheck> nameCheck();      // Confirmation of Payee or the local equivalent (4.3)
    Optional<DirectDebitScheme> directDebits();  // Bacs, ACH debits, UAEDDS (4.4)
    IdentityProfile identity();           // what onboarding asks for and which Atfinity workflow runs (4.5)
    ReportingProfile reporting();         // thresholds and reports for compliance (4.6)
    Limits limits();                      // single payment, high-value, daily card spend
}
```

Each pack is a Spring bean in its own module; `PBP_MARKET` picks the module at build time and the bean at start-up. A start-up check refuses to run if the built pack and `PBP_MARKET` disagree, as the module switches do today.

### 4.1 Account identifiers

```java
/** An account as the market writes it: a scheme name and its fields, e.g. {"sortCode": "040004", "accountNumber": "12345678"}. */
public record AccountIdentifier(String scheme, Map<String, String> fields) {}
```

| Market | Scheme | Fields and checks |
| --- | --- | --- |
| UK | `UK_SORT_CODE` | Sort code (6 digits) and account number (8 digits), as today |
| US | `US_ABA` | ABA routing number (9 digits, with its 3-7-1 checksum) and account number (4–17 digits); checking or savings |
| Colombia | `CO_ACCOUNT` | Bank code, account number and account type (savings or current). Bre-B payments can also use an alias (*llave*): ID number, mobile number, e-mail or alphanumeric key |
| Jamaica | `JM_ACCOUNT` | Bank and branch code and account number (format confirmed against the ACH operator's rules) |
| UAE | `IBAN` | IBAN: `AE`, 2 check digits, 3-digit bank code, 16-digit account (23 characters), checked by ISO 13616 mod-97. Aani also accepts a mobile-number proxy |

The API changes from `creditor: {name, sortCode, accountNumber}` to `creditor: {name, account: {scheme, fields}}`. The BFF accepts the old UK shape too during Stage 1, so the web app and any test scripts keep working while they move over.

### 4.2 Payment schemes and routing

```java
public record PaymentScheme(
        String code,                   // FPS, CHAPS, RTP, FEDNOW, ACH, WIRE, BREB, ...
        boolean instant,
        OperatingWindow window,        // always open, or business days between two times
        BigDecimal limit,              // largest single payment, in the market's currency
        BigDecimal fee) {}             // the bank's tariff for it, if any

public interface PaymentRouter {
    /** The scheme for this payment: same-bank, then the market's rules, then the customer's choice where allowed. */
    PaymentScheme route(PaymentIntent intent);
}
```

| Market | Same-bank | Instant | High value | Batch | Routing rules to encode |
| --- | --- | --- | --- | --- | --- |
| UK | Internal | Faster Payments | CHAPS (business days, bank cut-off before 18:00) | Bacs | As today: same sort code → Internal; CHAPS when the customer chooses it, otherwise Faster Payments; each scheme's own limit and fee; CHAPS queued outside its hours. A Faster Payment above its limit is rejected, not moved to CHAPS |
| US | Internal | RTP, FedNow | Fedwire | ACH (same day and next day) | Instant where the receiving bank takes it; ACH otherwise; Fedwire for high value and by choice |
| Colombia | Internal | Bre-B (live since 6 October 2025, run by Banco de la República) | CUD (the central bank's large-value system) | ACH Colombia, CENIT | Bre-B by default; ACH where the receiving institution is not on Bre-B |
| Jamaica | Internal | — (no national instant system) | JamClear-RTGS (Bank of Jamaica) | ACH (operated by JETS) | Transfers of J$1 million and above go to RTGS; below that, ACH |
| UAE | Internal | Aani (Al Etihad Payments, a Central Bank of the UAE subsidiary) | UAEFTS | — | Aani by default; UAEFTS for high value |

Large-value system names (CUD in Colombia) and each market's cut-offs, limits and fees are confirmed with the client bank before a pack is called ready (section 7).

The orchestrator's payment workflow keeps its steps: check, name check, debit, send, wait for the result, release or reverse. Only "which scheme" and "is it open now" move into the pack. `ChapsWindow` becomes the UK pack's `OperatingWindow` for CHAPS, unchanged in behaviour (done in Stage 1 step 5). The workflow still calls its `chapsWindow` activity by that name, so payments already queued replay unchanged; a general `schemeWindow(code)` activity, introduced behind a Temporal version marker, comes with the first pack that has a second scheme with hours.

### 4.3 Name check

| Market | Name check |
| --- | --- |
| UK | Confirmation of Payee (MATCH, CLOSE_MATCH, NO_MATCH, UNAVAILABLE), as today |
| US | No national service; optional account validation from a provider later. The pack returns "not available", and the workflow skips the step |
| Colombia | Bre-B shows the payee's name when an alias is resolved; the pack maps that to the same four results |
| Jamaica | None; skipped |
| UAE | Aani shows the payee's name for proxy payments; mapped the same way |

The `cop_result` column becomes `name_check_result`, with the same values.

### 4.4 Direct Debits

| Market | Scheme | In Stage 2 |
| --- | --- | --- |
| UK | Bacs Direct Debit (service user number, Direct Debit Guarantee) | As today |
| US | ACH debits (customer authorisation, NACHA return codes) | Yes |
| UAE | UAEDDS (Direct Debit System) mandates | Yes |
| Colombia, Jamaica | Not in the first pack | Later |

`dd_mandate.service_user_number` becomes a generic `originator_id`; the UK pack still calls it the SUN.

### 4.5 Identity and onboarding

The pack names what onboarding collects and which Atfinity workflow runs. Atfinity holds the checks themselves.

| Market | Individuals | Businesses |
| --- | --- | --- |
| UK | As today's Atfinity workflow | As today |
| US | Customer Identification Program: SSN or ITIN, date of birth, address | EIN, beneficial owners (FinCEN rule) |
| Colombia | *Cédula de ciudadanía* (or *de extranjería*) | NIT and the chamber of commerce certificate |
| Jamaica | TRN and a government photo ID | TRN and Companies Office of Jamaica registration |
| UAE | Emirates ID, UAE Pass where offered | Trade licence |

### 4.6 Reporting and compliance

The pack supplies the thresholds and report types compliance uses. The exact figures come from the client bank's compliance team and live in the pack's configuration, not in code.

| Market | Regulator and FIU | Reports the pack must support |
| --- | --- | --- |
| UK | FCA, PRA; NCA | As today |
| US | OCC, FDIC or the state; FinCEN | Currency transaction reports above US$10,000; suspicious activity reports; OFAC screening |
| Colombia | Superintendencia Financiera; UIAF | Suspicious operation reports and cash transaction reports |
| Jamaica | Bank of Jamaica; Financial Investigations Division | Threshold and suspicious transaction reports |
| UAE | Central Bank of the UAE; UAE FIU (goAML) | Suspicious transaction reports; local sanctions list |

### 4.7 Money, time and display

- **Money** stays `BigDecimal` (CLAUDE.md). The scale comes from the currency (ISO 4217): two decimals for GBP, USD, JMD and AED, and also for COP, which the web app shows without decimals by convention.
- **Time**: schedules, Direct Debit due dates, scheme windows and flow metrics use `pack.zone()` and `pack.calendar()`. The UAE weekend is Saturday and Sunday.
- **Holidays**: each pack ships its calendar as configuration (as `CHAPS_HOLIDAYS` does today), refreshed yearly.
- **Web**: `GET /v1/market` returns the currency, locale, account fields and their checks, the schemes a customer can choose and whether a name check applies. `web/src/shared/format.ts` formats with that locale and currency instead of `en-GB` and `£`. Spanish (Colombia) and Arabic (UAE) text come with those packs.

### 4.8 Cards

The card processor sends amounts with an ISO 4217 numeric currency code (826 for GBP); the Thredd adapter reads it from the pack. Thredd for the UK and Caribbean, and Increase for the US, stay behind the same card adapters. A market whose processor is new needs an adapter, not a pack change.

### 4.9 Vendors per market

To confirm with each partner before a pack is called ready:

| Market | Core (Finastra Essence) | Cards | KYC | Credit bureau (examples) |
| --- | --- | --- | --- | --- |
| UK | Yes | Thredd | Atfinity | Experian, Equifax, TransUnion |
| US | Confirm Essence's US support and its RTP, FedNow and ACH connections | Increase, or Thredd for debit | Atfinity | Experian, Equifax, TransUnion |
| Colombia | Confirm (Vanti is migrating from SAP) | Confirm | Atfinity | DataCrédito Experian, TransUnion Colombia |
| Jamaica | Confirm (JMMB is in core selection) | Thredd | Atfinity | CreditInfo Jamaica, CRIF |
| UAE | Confirm | Confirm | Atfinity | Al Etihad Credit Bureau |

## 5. Stage 1: the separation (UK behaviour unchanged)

| Step | What | Proof it changed nothing |
| --- | --- | --- |
| 1 | Add the `MarketPack` interface and value types to platform-common; add `markets/uk` with today's values (currency, zone, holidays, limits, fees, CHAPS hours) read from the same configuration keys | Unit tests for the UK pack against today's constants |
| 2 | Replace hard-coded GBP, `Europe/London`, holiday lists and limits with the pack, service by service | Existing workflow tests (`PaymentWorkflowTest`, `SchedulesTest` and the rest) pass unchanged |
| 3 | Introduce `AccountIdentifier`; the BFF accepts both the old and the new request shape; commands and events carry the new shape | Contract tests: old-shape requests give byte-identical commands to before |
| 4 | Add `name_check_result`, `originator_id` and `creditor_account` columns beside the old ones; write both, read the new | Flyway migrations on a copy of the demo database; recon totals equal before and after |
| 5 | Move scheme choice and the CHAPS window into the UK pack's router | Payment workflow tests for FPS, CHAPS inside and outside hours, the limit, queued and released |
| 6 | Add `GET /v1/market`; the web app builds the payment and payee forms from it | Web tests; the UK screens look the same |
| 7 | Run the full smoke tests for all five bank set-ups | CI green; then a manual demo run on simulators |
| 8 | Later release: drop the old columns and the old request shape | Only after a release with both has run cleanly |

The `PBP_MARKET` switch exists from step 1 with one value, `uk`. Every step is its own pull request, and each one passes CI before the next starts.

**Estimate**: about 4 weeks for two backend developers and a test automator, with the architect part-time and the frontend developer for step 6. This is an estimate, to be firmed up after step 1.

## 6. Stage 2: the packs

Each pack is a new `markets/<code>` module plus:

- the vendor simulator extended with that market's schemes (instant, high value, batch), name check where one exists, and its identifiers;
- the CI bank set-ups gaining one bank per market (for example "US retail with cards", "UAE retail");
- the market's rules written down by the banking consultant and signed off as the pack's acceptance tests.

**What "ready" means**: built, tested in CI and demonstrable end to end on simulators. It does not mean connected to the live schemes or certified by them; that happens in a client engagement, with the bank's own scheme memberships and the vendors' production connections. External documents keep saying "upcoming" until then.

**Estimate**: about 3–4 weeks per pack after Stage 1, for two backend developers and a test automator, with the banking consultant for the rules and the frontend developer for language and formats. The UAE pack takes longer because of Arabic and right-to-left screens. Estimates, to be firmed up after the first pack.

Suggested order: US (closest to the UK model, and Increase is ready), Jamaica (closest to the UK's RTGS-plus-ACH shape, and JMMB), UAE, Colombia (Bre-B aliases are the largest new concept; Vanti also needs loans first).

## 7. Open questions

1. Finastra Essence's support and scheme connections in each market (section 4.9).
2. Each market's limits, fees, cut-offs and holiday calendars, from the client bank.
3. Card processor for Colombia and the UAE.
4. Arabic for the UAE pack from day one, or English first?
5. Whether MCB's four jurisdictions are one pack with country settings or four small packs (decide after the Jamaica pack).
6. The CLAUDE.md rule "UK only": change it when Stage 1 starts, and record the change under "Corrections to the LLD" in the README.

## 8. Risks

| Risk | Mitigation |
| --- | --- |
| Stage 1 breaks a UK flow | One step per pull request; old and new shapes side by side; existing tests and the five bank set-ups must pass at every step |
| A pack is presented as live before it is | "Ready" is defined in section 6; the deck and proposals say "upcoming" |
| Scheme rules guessed wrong | The banking consultant writes them from the scheme rulebooks; the client bank confirms before a pack goes to a pilot |
| A market needs something the interface lacks | The interface grows with the first non-UK pack (US); Jamaica, UAE and Colombia follow once it is stable |
