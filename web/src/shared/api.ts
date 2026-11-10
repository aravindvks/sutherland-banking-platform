// Thin client for the BFF (/v1) and, for the demo toolbar only, the vendor simulator (/sim).

export type Me = {
  customerId: string;
  customerType: string;
  status: string;
  kycStatus: string | null;
  riskRating: string | null;
  kycCaseId?: string;
  account?: {
    accountId: string;
    name: string | null;
    sortCode: string | null;
    accountNumber: string | null;
    available: string | null;
    ledger?: string;
    balanceError?: string;
  };
  card?: {
    token: string; last4: string | null; status: string; dailyLimit: string;
    online: boolean; contactless: boolean; atm: boolean;
  };
};

export type CreditApplication = {
  applicationId: string; status: string; requestedLimit: string; offeredLimit: string | null;
  purchaseApr: string | null; outcome: string | null; reasons: string | null; accountId: string | null;
  correlationId: string; createdAt: string;
};
export type CreditStatement = {
  statementId: string; statementDate: string; balance: string; minimumPayment: string; interest: string;
  dueDate: string; status: string; lateFee: string | null;
};
export type CreditAccount = {
  accountId: string; cardToken: string; last4: string; creditLimit: string; purchaseApr: string; status: string;
  arrearsCount: number; withdrawUntil: string; openedAt: string; balance?: string | null; pending?: string | null;
  availableCredit?: string | null; liveError?: string;
  transactions?: { ref: string; type: string; amount: string; description: string; at: string }[];
  statements: CreditStatement[];
  repayments: { repaymentId: string; amount: string; status: string; reason: string | null; createdAt: string }[];
};
export type CreditCards = { applications: CreditApplication[]; accounts: CreditAccount[] };

export type Payment = {
  paymentId: string;
  correlationId: string;
  debitAccount: string;
  creditor: string; // JSON text
  amount: string;
  currency: string;
  paymentType: string;
  status: string;
  finastraRef: string | null;
  createdAt: string;
  copResult?: string | null;
  fee?: string | null;
  scheduleId?: string | null;
  history?: { from: string | null; to: string; reason: string | null; actor: string; at: string }[];
};

export type Onboarding = {
  onboardingId: string;
  status: string;
  step: string;
  customerId: string | null;
  accountId: string | null;
  sortCode: string | null;
  accountNumber: string | null;
  cardLast4: string | null;
  reason: string | null;
};

export type Cop = { result: 'MATCH' | 'CLOSE_MATCH' | 'NO_MATCH' | 'UNAVAILABLE'; matchedName?: string | null; reasonCode?: string | null };

export type Schedule = {
  scheduleId: string; kind: 'ONE_OFF' | 'STANDING_ORDER'; frequency: string | null; creditor: string;
  amount: string; reference: string | null; scheme: string | null; startDate: string; endDate: string | null;
  nextDate: string | null; paymentsMade: number; lastStatus: string | null; status: string;
};

export type Mandate = {
  mandateId: string; mandateRef: string; originatorName: string; serviceUserNumber: string;
  payerReference: string | null; status: string; createdAt: string;
  lastAmount: string | null; lastStatus: string | null; lastCollectedAt: string | null;
};

export type CardControls = { frozen: boolean; dailyLimit: string; online: boolean; contactless: boolean; atm: boolean };

export class ApiError extends Error {
  constructor(message: string, readonly code?: string, readonly correlationId?: string) {
    super(message);
  }
}

/** Set by main.tsx after Keycloak sign-in; empty when login is off (local Vite runs). */
export const auth: {
  enabled: boolean;
  token: () => Promise<string | undefined>;
  username: string;
  roles: string[];
  logout: () => void;
} = { enabled: false, token: async () => undefined, username: '', roles: [], logout: () => {} };

/** Authorization header when signed in; otherwise the development headers. */
export async function authHeaders(dev: Record<string, string | undefined> = {}): Promise<Record<string, string>> {
  const t = await auth.token();
  if (t) return { Authorization: `Bearer ${t}` };
  return Object.fromEntries(Object.entries(dev).filter(([, v]) => v)) as Record<string, string>;
}

async function request<T>(path: string, init: RequestInit = {}, customerId?: string): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (!path.startsWith('/sim/')) Object.assign(headers, await authHeaders({ 'X-Customer-Id': customerId }));
  if (init.method === 'POST') headers['Idempotency-Key'] = crypto.randomUUID();
  const res = await fetch(path, { ...init, headers: { ...headers, ...(init.headers as Record<string, string>) } });
  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (!res.ok) {
    throw new ApiError(body?.detail ?? body?.message ?? `Request failed (${res.status})`, body?.code ?? body?.errorCode, body?.correlationId);
  }
  return body as T;
}

export const api = {
  me: (customerId?: string) => request<Me>('/v1/me', {}, customerId),
  payments: (customerId: string) => request<Payment[]>('/v1/payments', {}, customerId),
  payment: (paymentId: string) => request<Payment>(`/v1/payments/${paymentId}`),
  pay: (customerId: string, body: unknown) =>
    request<{ paymentId: string; correlationId: string }>('/v1/payments', { method: 'POST', body: JSON.stringify(body) }, customerId),
  signUp: (body: unknown) =>
    request<{ onboardingId: string; correlationId: string }>('/v1/onboarding', { method: 'POST', body: JSON.stringify(body) }),
  onboarding: (id: string) => request<Onboarding>(`/v1/onboarding/${id}`),
  confirmPayee: (customerId: string, body: { name: string; sortCode: string; accountNumber: string }) =>
    request<Cop>('/v1/payees/confirm', { method: 'POST', body: JSON.stringify(body) }, customerId),
  schedules: (customerId: string) => request<Schedule[]>('/v1/schedules', {}, customerId),
  createSchedule: (customerId: string, body: unknown) =>
    request<{ scheduleId: string; correlationId: string }>('/v1/schedules', { method: 'POST', body: JSON.stringify(body) }, customerId),
  cancelSchedule: (customerId: string, id: string) => request(`/v1/schedules/${id}`, { method: 'DELETE' }, customerId),
  runScheduleNow: (customerId: string, id: string) => request(`/v1/schedules/${id}/run-now`, { method: 'POST' }, customerId),
  mandates: (customerId: string) => request<Mandate[]>('/v1/direct-debits', {}, customerId),
  cancelMandate: (customerId: string, id: string) => request(`/v1/direct-debits/${id}`, { method: 'DELETE' }, customerId),
  claimRefund: (customerId: string, paymentId: string) =>
    request('/v1/direct-debits/claims', { method: 'POST', body: JSON.stringify({ paymentId }) }, customerId),
  creditCards: (customerId: string) => request<CreditCards>('/v1/credit-cards', {}, customerId),
  applyCreditCard: (customerId: string, body: unknown) =>
    request<{ applicationId: string; correlationId: string }>('/v1/credit-cards/applications', { method: 'POST', body: JSON.stringify(body) }, customerId),
  acceptCreditOffer: (customerId: string, applicationId: string) =>
    request(`/v1/credit-cards/applications/${applicationId}/accept`, { method: 'POST', body: JSON.stringify({ agreementVersion: 'v1' }) }, customerId),
  repayCreditCard: (customerId: string, accountId: string, debitAccount: string, amount: string) =>
    request<{ repaymentId: string; correlationId: string }>(`/v1/credit-cards/${accountId}/repayments`, { method: 'POST', body: JSON.stringify({ debitAccount, amount }) }, customerId),
  withdrawCreditCard: (customerId: string, accountId: string) =>
    request(`/v1/credit-cards/${accountId}/withdraw`, { method: 'POST' }, customerId),
  cardControls: (customerId: string, body: CardControls) =>
    request<{ commandId: string; correlationId: string }>('/v1/cards/controls', { method: 'PUT', body: JSON.stringify(body) }, customerId),
};

// Demo toolbar: plays the card network, other banks and Atfinity through the vendor simulator.
export const demo = {
  // Thredd's statement cycle for a credit card account; dueInSeconds brings the due time forward for demos.
  creditStatement: (accountId: string, dueInSeconds?: number) =>
    request<{ statementId: string; balance: string; minimumPayment: string; dueDate: string }>(
      '/sim/thredd/v1/credit/sim/statement', { method: 'POST', body: JSON.stringify(dueInSeconds ? { accountId, dueInSeconds } : { accountId }) }),
  tap: (token: string, amount: string, merchant: string, channel: string) =>
    request<{ messageId: string; decision: string; reason: string | null; latencyMs: number }>(
      '/sim/thredd/v1/sim/card-tap', { method: 'POST', body: JSON.stringify({ token, amount, merchant, channel }) }),
  receive: (accountId: string, amount: string) =>
    request('/sim/essence/v1/sim/inbound', {
      method: 'POST',
      body: JSON.stringify({ creditAccountId: accountId, amount, debtorName: 'Arjun Mehta', reference: 'Demo transfer' }),
    }),
  newDirectDebit: (accountId: string, originatorName: string, serviceUserNumber: string) =>
    request<{ mandateRef: string }>('/sim/essence/v1/sim/dd/mandates', {
      method: 'POST', body: JSON.stringify({ accountId, originatorName, serviceUserNumber }),
    }),
  collect: (mandateRef: string, amount: string) =>
    request('/sim/essence/v1/sim/dd/collections', { method: 'POST', body: JSON.stringify({ mandateRef, amount }) }),
  salary: (accountId: string, amount: string) =>
    request('/sim/essence/v1/sim/bacs/credits', {
      method: 'POST',
      body: JSON.stringify({ originatorName: 'ACME LTD', credits: [{ creditAccountId: accountId, amount, reference: 'SALARY NOV' }] }),
    }),
  sanctionsHit: (caseId: string) => request(`/sim/atfinity/v1/sim/cases/${caseId}/rescreen-hit`, { method: 'POST' }),
};

export const gbp = (v: string | null | undefined) =>
  v == null ? '—' : new Intl.NumberFormat('en-GB', { style: 'currency', currency: 'GBP' }).format(Number(v));

/** Parses PostgreSQL timestamp text ("2026-11-06 13:55:02.1+00") as well as ISO 8601. */
export const toDate = (t: string) => new Date(t.replace(' ', 'T').replace(/([+-]\d\d)$/, '$1:00'));
