// Payment and payee forms built from the market (GET /v1/market, Stage 1 step 6 of docs/design/market-packs.md):
// which account fields to ask for, how to check them, which schemes the customer can choose and what to send.
// In the UK this gives exactly the form the app had before: sort code and account number, Faster Payment or CHAPS,
// and the original request shape.
import { market, type AccountField, type Market, type PaymentScheme } from './market';

/** The UK's identifier scheme; requests for it keep the original sortCode/accountNumber shape. */
export const ORIGINAL_SHAPE_SCHEME = 'UK_SORT_CODE';

/** Element ids the UK form has always used, so tests and saved selectors keep working. */
const IDS: Record<string, string> = { sortCode: 'sort', accountNumber: 'acc' };

export const fieldId = (name: string) => IDS[name] ?? `acct-${name}`;

export const accountFields = (m: Market = market()): AccountField[] => m.accountIdentifier.fields;

/** Starting values for the form: each field's example, as the UK form started with 040004 / 12345678. */
export const exampleAccount = (m: Market = market()): Record<string, string> =>
  Object.fromEntries(accountFields(m).map((f) => [f.name, f.example ?? '']));

/** What the customer typed, without the spaces and dashes people put in sort codes, routing numbers and IBANs,
 *  and in capitals (IBANs start "AE"). */
export const clean = (value: string | undefined) => (value ?? '').replace(/[\s-]/g, '').toUpperCase();

/** The field's hint ("must be 6 digits") when the value does not match, otherwise null. */
export const fieldProblem = (field: AccountField, value: string | undefined): string | null => {
  try {
    return new RegExp(`^(?:${field.pattern})$`).test(clean(value)) ? null : field.hint;
  } catch {
    return null; // A pattern the browser cannot read is left to the BFF, which checks every account anyway.
  }
};

export const accountProblems = (values: Record<string, string>, m: Market = market()) =>
  accountFields(m).map((f) => [f.name, fieldProblem(f, values[f.name])] as const).filter(([, p]) => p !== null);

/** The cleaned values, in the market's field order. */
export const cleanAccount = (values: Record<string, string>, m: Market = market()): Record<string, string> =>
  Object.fromEntries(accountFields(m).map((f) => [f.name, clean(values[f.name])]));

export type Creditor =
  | { name: string; sortCode: string; accountNumber: string }
  | { name: string; account: { scheme: string; fields: Record<string, string> } };

/** The payee for a payment, schedule or name check: the original shape in the UK, {scheme, fields} elsewhere. */
export const creditorBody = (name: string, values: Record<string, string>, m: Market = market()): Creditor => {
  const fields = cleanAccount(values, m);
  if (m.accountIdentifier.scheme === ORIGINAL_SHAPE_SCHEME) {
    return { name, sortCode: fields.sortCode ?? '', accountNumber: fields.accountNumber ?? '' };
  }
  return { name, account: { scheme: m.accountIdentifier.scheme, fields } };
};

/** Same-bank payees go as an internal transfer and offer no scheme choice. Only known for the UK so far. */
export const isSameBank = (values: Record<string, string>, ownSortCode: string, m: Market = market()) =>
  m.accountIdentifier.scheme === ORIGINAL_SHAPE_SCHEME && clean(values.sortCode) === ownSortCode;

/** Schemes the customer can pick for another bank, as the market lists them (the UK: Faster Payment, CHAPS). */
export const schemeChoices = (m: Market = market()): PaymentScheme[] =>
  m.customerChoices?.length ? m.customerChoices : m.paymentSchemes.filter((s) => s.code !== 'INTERNAL');

/** The scheme a payment uses unless the customer picks another: the first instant one (Faster Payments). */
export const defaultScheme = (m: Market = market()): string =>
  (schemeChoices(m).find((s) => s.instant) ?? schemeChoices(m)[0])?.code ?? 'FPS';

/** A scheme with opening hours (CHAPS), which queues payments outside them. */
export const hasHours = (s: PaymentScheme | undefined) => !!s && !s.alwaysOpen && !!s.opens && !!s.cutOff;

/** A field whose rule is a list of words ("CHECKING|SAVINGS") is a choice, shown as a drop-down. */
export const fieldOptions = (field: AccountField): string[] | null =>
  /^[A-Z_]+(\|[A-Z_]+)+$/.test(field.pattern) ? field.pattern.split('|') : null;

/** The button text for a scheme: "Faster Payment · free", "CHAPS · £20, same day". */
export const choiceLabel = (s: PaymentScheme, name: string, fee: (v: string) => string) =>
  `${name} · ${Number(s.fee) > 0 ? fee(s.fee) : 'free'}${hasHours(s) ? ', same day' : ''}`;
