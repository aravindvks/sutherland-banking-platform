// The deployment's market (docs/design/market-packs.md). The BFF describes it at GET /v1/market; screens format
// money and dates and label their forms from it instead of assuming the UK. Until it loads (or with an older BFF)
// the UK applies, today's only market, so nothing changes on screen.

export type PaymentScheme = {
  code: string; name: string; instant: boolean; alwaysOpen: boolean;
  opens?: string; cutOff?: string; limit?: string; fee: string;
};

export type AccountField = { name: string; label: string; pattern: string; hint: string; example?: string };

export type Market = {
  code: string;
  currency: string;
  locale: string;
  zone: string;
  accountIdentifier: { scheme: string; fields: AccountField[] };
  paymentSchemes: PaymentScheme[];
  limits: { singlePayment: string; reviewThreshold: string; dailyCardSpend: string };
  nameCheck?: { name: string };
  directDebits?: { name: string; originatorIdName: string; guarantee: string };
};

export const UK: Market = {
  code: 'uk',
  currency: 'GBP',
  locale: 'en-GB',
  zone: 'Europe/London',
  accountIdentifier: {
    scheme: 'UK_SORT_CODE',
    fields: [
      { name: 'sortCode', label: 'Sort code', pattern: '\\d{6}', hint: 'must be 6 digits', example: '040004' },
      { name: 'accountNumber', label: 'Account number', pattern: '\\d{8}', hint: 'must be 8 digits', example: '12345678' },
    ],
  },
  paymentSchemes: [
    { code: 'INTERNAL', name: 'Transfer within the bank', instant: true, alwaysOpen: true, limit: '25000.00', fee: '0.00' },
    { code: 'FPS', name: 'Faster Payments', instant: true, alwaysOpen: true, limit: '25000.00', fee: '0.00' },
    { code: 'CHAPS', name: 'CHAPS', instant: false, alwaysOpen: false, opens: '06:00', cutOff: '17:30', limit: '1000000.00', fee: '20.00' },
  ],
  limits: { singlePayment: '25000.00', reviewThreshold: '10000.00', dailyCardSpend: '5000.00' },
  nameCheck: { name: 'Confirmation of Payee' },
  directDebits: { name: 'Bacs Direct Debit', originatorIdName: 'Service user number', guarantee: 'Direct Debit Guarantee' },
};

let current: Market = UK;

export const market = () => current;

/** Takes the BFF's answer; anything incomplete keeps the UK. Returns the market now in force. */
export const setMarket = (body: Partial<Market> | null | undefined): Market => {
  if (body && body.currency && body.locale && body.zone && Array.isArray(body.paymentSchemes) && body.limits) {
    const ids = body.accountIdentifier;
    // A BFF from before account fields were published keeps the UK fields.
    const accountIdentifier = ids && ids.scheme && Array.isArray(ids.fields) && ids.fields.length ? ids : UK.accountIdentifier;
    current = { ...UK, ...body, accountIdentifier } as Market;
  }
  return current;
};

export const scheme = (code: string) => current.paymentSchemes.find((s) => s.code === code);

/** "£", "$", "AED"…: the currency's symbol in the market's language. */
export const currencySymbol = () =>
  new Intl.NumberFormat(current.locale, { style: 'currency', currency: current.currency })
    .formatToParts(0).find((p) => p.type === 'currency')?.value ?? current.currency;

/** Money in the market's currency and language: "£1,234.50" in the UK. */
export const money = (v: string | number | null | undefined, digits?: number) =>
  v == null ? '—' : new Intl.NumberFormat(current.locale, {
    style: 'currency', currency: current.currency,
    ...(digits === undefined ? {} : { minimumFractionDigits: digits, maximumFractionDigits: digits }),
  }).format(Number(v));

/** Whole amounts without pence ("£20", "£10,000"), others with them: for copy such as fees and thresholds. */
export const moneyShort = (v: string | number) => money(v, Number(v) % 1 === 0 ? 0 : 2);

/** Today's date (YYYY-MM-DD) in the market's local time. */
export const todayLocal = () => new Intl.DateTimeFormat('en-CA', { timeZone: current.zone }).format(new Date());
