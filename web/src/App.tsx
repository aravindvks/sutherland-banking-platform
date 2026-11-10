import { useCallback, useEffect, useState } from 'react';
import { api, auth, demo, gbp, ApiError, type CreditCards, type Mandate, type Me, type Payment, type Schedule } from './shared/api';
import { fmtDay, fmtSort } from './shared/format';
import { dashboardUrl } from './shared/observability';
import { Pill } from './shared/ui';
import { Welcome } from './modules/onboarding/Onboarding';
import { PaymentDetail, PaymentList, Payments } from './modules/payments/Payments';
import { CardView } from './modules/cards/CardView';
import CreditCardView from './modules/creditcards/CreditCard';
import Staff from './staff/Staff';

const STORAGE_KEY = 'pbp.customerId';

type Tab = 'home' | 'payments' | 'card' | 'credit';

function readCustomer(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

function saveCustomer(id: string | null) {
  try {
    if (id) localStorage.setItem(STORAGE_KEY, id);
    else localStorage.removeItem(STORAGE_KEY);
  } catch {
    /* storage unavailable: the session still works */
  }
}

export default function App() {
  const [customerId, setCustomerId] = useState<string | null>(() => (auth.enabled ? null : readCustomer()));
  // With login on, the bank customer comes from the signed-in user (CUS-LINK-001 = not linked yet → sign up).
  const [checking, setChecking] = useState(auth.enabled);
  useEffect(() => {
    if (!auth.enabled) return;
    api.me()
      .then((m) => setCustomerId(m.customerId))
      .catch(() => setCustomerId(null))
      .finally(() => setChecking(false));
  }, []);
  const [toast, setToast] = useState<string | null>(null);
  const isStaff = () => location.hash === '#staff' || location.pathname.startsWith('/staff');
  const [staffView, setStaffView] = useState(isStaff);

  useEffect(() => {
    const onHash = () => setStaffView(isStaff());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 4500);
    return () => clearTimeout(t);
  }, [toast]);

  const signIn = (id: string) => {
    saveCustomer(id);
    setCustomerId(id);
  };

  return (
    <>
      {staffView ? <Staff notify={setToast} /> : checking ? (
        <main className="shell narrow"><p className="muted">Signing you in…</p></main>
      ) : customerId ? (
        <Bank customerId={customerId} notify={setToast}
          onSignOut={() => { saveCustomer(null); setCustomerId(null); if (auth.enabled) auth.logout(); }} />
      ) : (
        <Welcome onReady={signIn} notify={setToast} />
      )}
      {toast && <div className="toast" role="status">{toast}</div>}
    </>
  );
}


// ---------------------------------------------------------------------------------------------
// Signed-in bank
// ---------------------------------------------------------------------------------------------

function Bank({ customerId, notify, onSignOut }: { customerId: string; notify: (m: string) => void; onSignOut: () => void }) {
  const [tab, setTab] = useState<Tab>('home');
  const [me, setMe] = useState<Me | null>(null);
  const [payments, setPayments] = useState<Payment[]>([]);
  const [schedules, setSchedules] = useState<Schedule[]>([]);
  const [mandates, setMandates] = useState<Mandate[]>([]);
  const [credit, setCredit] = useState<CreditCards | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [m, p, sc, dd, cc] = await Promise.all([api.me(customerId), api.payments(customerId),
        api.schedules(customerId).catch(() => [] as Schedule[]), api.mandates(customerId).catch(() => [] as Mandate[]),
        api.creditCards(customerId).catch(() => null)]);
      setCredit(cc);
      setMe(m);
      setPayments(p);
      setSchedules(sc);
      setMandates(dd);
      setError(null);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Cannot reach the bank');
    }
  }, [customerId]);

  // Live view: the BFF pushes this customer's events (Server-Sent Events); a slow poll is the fallback.
  const [live, setLive] = useState(false);
  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 15000);
    let source: EventSource | null = null;
    let closed = false;
    const wire = (es: EventSource) => {
      es.addEventListener('ready', () => setLive(true));
      es.addEventListener('bank', (ev) => {
        try {
          const e = JSON.parse((ev as MessageEvent).data) as { type: string; data: Record<string, unknown> };
          const text = describe(e.type, e.data);
          if (text) notify(text);
        } catch {
          /* ignore malformed events */
        }
        refresh();
      });
      es.onerror = () => setLive(false);
    };
    (async () => {
      const token = await auth.token();
      if (closed) return;
      source = new EventSource(token
        ? `/v1/events?access_token=${encodeURIComponent(token)}`
        : `/v1/events?customerId=${encodeURIComponent(customerId)}`);
      wire(source);
    })();
    return () => { closed = true; clearInterval(t); source?.close(); };
  }, [refresh, customerId, notify]);

  const frozen = me && me.status !== 'ACTIVE';
  return (
    <>
      <DemoBar me={me} mandates={mandates} credit={credit} notify={notify} after={refresh} />
      <header className="hero">
        <div className="hero-inner">
          <div className="hero-top">
            <span className="wordmark">Sutherland Banking Platform</span>
            <span className="spacer" />
            <span className="hero-who">{me?.account?.name ?? 'Loading…'}</span>
            <span className={`live ${live ? 'on' : ''}`} title={live ? 'Updates arrive as they happen' : 'Reconnecting'}>{live ? 'Live' : 'Offline'}</span>
            <a className="hero-link" href="/staff">Staff portal</a>
            <button className="hero-link" onClick={onSignOut}>Sign out</button>
          </div>
          <div className="hero-balance">
            <Amount value={me?.account?.available} />
            <p className="hero-sub">
              {me?.customerType === 'BUSINESS' ? 'Business current account' : 'Current account'}
              {me?.account?.sortCode ? <> · sort code <strong className="num">{fmtSort(me.account.sortCode)}</strong> · account <strong className="num">{me.account.accountNumber}</strong></> : null}
              {me?.account?.ledger && me.account.ledger !== me.account.available ? ` · ${gbp(me.account.ledger)} including pending card payments` : ''}
            </p>
          </div>
          <nav className="tabs" aria-label="Sections">
            {(['home', 'payments', 'card', 'credit'] as Tab[]).map((t) => (
              <button key={t} aria-current={tab === t ? 'page' : undefined} onClick={() => { setTab(t); setSelected(null); }}>
                {t === 'home' ? 'Overview' : t === 'payments' ? 'Payments' : t === 'card' ? 'Debit card' : 'Credit card'}
              </button>
            ))}
          </nav>
        </div>
      </header>
      <main className="shell">
        {error && <p className="alert bad">{error}</p>}
        {frozen && <p className="alert bad"><strong>Account under review.</strong> Card payments and transfers are paused while the bank completes a compliance check.</p>}
        {tab === 'home' && me && <Home me={me} payments={payments} open={(id) => { setTab('payments'); setSelected(id); }} />}
        {tab === 'payments' && me && (
          selected
            ? <PaymentDetail id={selected} back={() => setSelected(null)} customerId={customerId} notify={notify} />
            : <Payments me={me} customerId={customerId} payments={payments} schedules={schedules} mandates={mandates} open={setSelected} notify={notify} after={refresh} />
        )}
        {tab === 'card' && me && <CardView me={me} customerId={customerId} notify={notify} after={refresh} />}
        {tab === 'credit' && me && <CreditCardView me={me} customerId={customerId} credit={credit} notify={notify} after={refresh} />}
      </main>
    </>
  );
}

/** The balance in the account band: pounds large, pence small. */
function Amount({ value }: { value: string | null | undefined }) {
  if (value == null) return <div className="hero-amount">—</div>;
  const n = Number(value);
  const [pounds, pence] = Math.abs(n).toFixed(2).split('.');
  return (
    <div className="hero-amount" aria-label={gbp(value)}>
      {n < 0 ? '−' : ''}£{Number(pounds).toLocaleString('en-GB')}<span className="pence">.{pence}</span>
    </div>
  );
}

function Home({ me, payments, open }: { me: Me; payments: Payment[]; open: (id: string) => void }) {
  return (
    <>
      <section className="panel stack">
        <h2>Your account</h2>
        {me.account?.balanceError && <p className="alert warn">{me.account.balanceError}</p>}
        <dl className="kv">
          <div><dt>Sort code</dt><dd className="num">{fmtSort(me.account?.sortCode)}</dd></div>
          <div><dt>Account number</dt><dd className="num">{me.account?.accountNumber ?? '—'}</dd></div>
          <div><dt>Status</dt><dd><Pill status={me.status} /></dd></div>
          <div><dt>Identity check</dt><dd>{me.kycStatus ?? '—'}{me.riskRating ? ` · risk ${me.riskRating.toLowerCase()}` : ''}</dd></div>
        </dl>
      </section>
      <section className="panel stack">
        <h2>Recent payments</h2>
        <PaymentList payments={payments.slice(0, 5)} open={open} />
      </section>
    </>
  );
}


// ---------------------------------------------------------------------------------------------
// Demo toolbar (dev and demo environments only, LLD section 16)
// ---------------------------------------------------------------------------------------------

function DemoBar({ me, mandates, credit, notify, after }: { me: Me | null; mandates: Mandate[]; credit: CreditCards | null; notify: (m: string) => void; after: () => void }) {
  const cc = credit?.accounts.find((a) => a.status === 'ACTIVE' || a.status === 'ARREARS');
  const [merchant, setMerchant] = useState('3.50|Bean There Cafe|contactless');
  const run = async (fn: () => Promise<string>) => {
    try {
      notify(await fn());
      setTimeout(after, 800);
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Demo action failed');
    }
  };
  const [amount, name, channel] = merchant.split('|');
  return (
    <div className="band">
      <div className="band-inner">
        <span className="tag">Demo controls</span>
        <select id="merchant" aria-label="Merchant" value={merchant} onChange={(e) => setMerchant(e.target.value)}>
          <option value="3.50|Bean There Cafe|contactless">Cafe · £3.50 · contactless</option>
          <option value="45.00|Northwind Online|online">Online store · £45</option>
          <option value="100.00|ATM Liverpool St|atm">ATM · £100</option>
          <option value="400.00|Volt Electronics|chip">Electronics · £400 · chip</option>
        </select>
        <button className="bbtn primary" disabled={!me?.card} onClick={() => run(async () => {
          const d = await demo.tap(me!.card!.token, amount, name, channel);
          return d.decision === 'APPROVE' ? `Approved ${gbp(amount)} at ${name} (${d.latencyMs} ms)` : `Declined: ${d.reason}`;
        })}>Tap card</button>
        <button className="bbtn" disabled={!cc} onClick={() => run(async () => {
          const d = await demo.tap(cc!.cardToken, amount, name, channel);
          return d.decision === 'APPROVE' ? `Credit card approved ${gbp(amount)} at ${name} (${d.latencyMs} ms)` : `Credit card declined: ${d.reason}`;
        })}>Tap credit card</button>
        <button className="bbtn" disabled={!cc} onClick={() => run(async () => {
          const s = await demo.creditStatement(cc!.accountId, 60);
          return `Thredd statement: ${gbp(s.balance)}, minimum ${gbp(s.minimumPayment)} due in 60 seconds (demo)`;
        })}>Credit statement</button>
        <button className="bbtn" disabled={!me?.account} onClick={() => run(async () => {
          await demo.receive(me!.account!.accountId, '20.00');
          return 'Arjun sent you £20 by Faster Payment';
        })}>Receive £20</button>
        <button className="bbtn" disabled={!me?.account} onClick={() => run(async () => {
          await demo.salary(me!.account!.accountId, '2500.00');
          return 'ACME LTD paid your salary of £2,500 by Bacs';
        })}>Salary (Bacs)</button>
        <button className="bbtn" disabled={!me?.account} onClick={() => run(async () => {
          await demo.newDirectDebit(me!.account!.accountId, 'Brightside Energy', '275431');
          return 'Brightside Energy set up a Direct Debit on your account';
        })}>New Direct Debit</button>
        <button className="bbtn" disabled={!mandates.some((m) => m.status === 'ACTIVE') && !mandates.length} onClick={() => run(async () => {
          const m = mandates.find((x) => x.status === 'ACTIVE') ?? mandates[0];
          await demo.collect(m.mandateRef, '45.00');
          return `${m.originatorName} collected £45 by Direct Debit`;
        })}>Collect Direct Debit</button>
        <button className="bbtn" disabled={!me?.kycCaseId} onClick={() => run(async () => {
          await demo.sanctionsHit(me!.kycCaseId!);
          return 'Atfinity rescreening raised a possible sanctions match';
        })}>Sanctions hit</button>
        {dashboardUrl() && <a className="bbtn" href={dashboardUrl()!} target="_blank" rel="noreferrer"
          title="Grafana: see what the demo actions just did, as metrics, logs and traces">Observability ↗</a>}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------------------------

/** Short customer-facing text for a pushed event, or null to refresh silently. */
function describe(type: string, d: Record<string, unknown>): string | null {
  const amount = (d.amount as { value?: string } | undefined)?.value;
  switch (type) {
    case 'card.authorised': return `Card payment ${gbp(amount)} at ${d.merchant}`;
    case 'card.declined': return `Card declined at ${d.merchant}: ${d.reason}`;
    case 'payment.completed': return d.type === 'DIRECT_DEBIT' ? `Direct Debit of ${gbp(amount)} paid to ${d.originatorName}` : `Payment of ${gbp(amount)} completed`;
    case 'payment.held': return `Payment of ${gbp(amount)} is waiting for approval by our team`;
    case 'payment.queued': return `CHAPS payment of ${gbp(amount)} will go when CHAPS opens`;
    case 'payment.presented': return `${d.originatorName} will collect ${gbp(amount)} by Direct Debit on ${fmtDay(String(d.dueDate))}`;
    case 'payment.returned': return `Direct Debit of ${gbp(amount)} to ${d.originatorName} was not paid: ${d.reason}`;
    case 'payment.refunded': return `Refunded ${gbp(amount)} under the Direct Debit Guarantee`;
    case 'directdebit.mandate_created': return `New Direct Debit set up for ${d.originatorName}`;
    case 'directdebit.mandate_cancelled': return `Direct Debit to ${d.originatorName} cancelled`;
    case 'directdebit.claim_refused': return `Refund not possible: ${d.reason}`;
    case 'schedule.created': return d.kind === 'ONE_OFF' ? `Payment to ${d.creditorName} scheduled` : `Standing order to ${d.creditorName} set up`;
    case 'schedule.cancelled': return `${d.kind === 'ONE_OFF' ? 'Scheduled payment' : 'Standing order'} to ${d.creditorName} cancelled`;
    case 'payment.rejected': return `Payment of ${gbp(amount)} rejected: ${d.reason}`;
    case 'creditcard.offered': return `Credit card offer: ${gbp(String(d.creditLimit))} at ${d.purchaseApr}% APR`;
    case 'creditcard.declined': return 'Your credit card application was declined';
    case 'creditcard.referred': return 'Your credit card application is with an underwriter';
    case 'creditcard.opened': return `Your credit card ending ${d.last4} is ready`;
    case 'creditcard.repayment_completed': return 'Credit card repayment received';
    case 'creditcard.statement_ready': return `New credit card statement: minimum ${gbp(String(d.minimumPayment))} due ${d.dueDate}`;
    case 'creditcard.payment_missed': return `Credit card minimum payment missed: £${d.lateFee} fee charged`;
    case 'payment.received': return d.status === 'FLAGGED' ? `${gbp(amount)} received and held for review` : `You received ${gbp(amount)} from ${d.debtorName}${d.scheme === 'BACS' ? ' by Bacs' : ''}`;
    case 'payment.released': return `Held payment of ${gbp(amount)} released`;
    case 'customer.status_changed': return d.to === 'FROZEN' ? 'Your account is under review' : d.to === 'ACTIVE' ? 'Your account is fully available' : null;
    case 'card.controls_changed': return 'Card settings updated';
    default: return null;
  }
}
