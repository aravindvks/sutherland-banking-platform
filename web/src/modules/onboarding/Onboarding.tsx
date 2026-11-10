// Onboarding module: sign-up and the onboarding saga's progress (Atfinity identity check, Essence account, Thredd card).
import { useEffect, useState } from 'react';
import { api, auth, ApiError, type Onboarding } from '../../shared/api';
import { fmtSort } from '../../shared/format';

const ONBOARDING_STEPS = [
  ['CUSTOMER_CREATED', 'Customer record created'],
  ['WAITING_FOR_KYC', 'Identity check with Atfinity'],
  ['KYC_APPROVED', 'Identity check approved'],
  ['ACCOUNT_OPENED', 'Current account opened in Essence'],
  ['CARD_ISSUED', 'Debit card issued by Thredd'],
  ['ACTIVE', 'Account ready'],
] as const;
// ---------------------------------------------------------------------------------------------
// Sign-up and onboarding progress
// ---------------------------------------------------------------------------------------------

export function Welcome({ onReady, notify }: { onReady: (id: string) => void; notify: (m: string) => void }) {
  const [form, setForm] = useState({
    name: auth.enabled ? '' : 'Priya Shah',
    email: auth.enabled ? '' : 'priya@example.com',
    customerType: 'INDIVIDUAL',
  });
  const [onboardingId, setOnboardingId] = useState<string | null>(null);
  const [existing, setExisting] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try {
      const r = await api.signUp(form);
      setOnboardingId(r.onboardingId);
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Sign-up failed');
    } finally {
      setBusy(false);
    }
  };

  if (onboardingId) return <OnboardingProgress id={onboardingId} onReady={onReady} onRestart={() => setOnboardingId(null)} />;

  return (
    <div className="welcome">
    <main className="shell narrow">
      <header className="stack">
        <span className="wordmark">Sutherland Banking Platform</span>
        <h1 className="intro">A UK current account, opened in a minute.</h1>
        <p className="intro-sub">Identity checked by Atfinity, account by Finastra, card by Thredd: all running on simulators. Staff work in the <a href="/staff">Control Room</a>.</p>
      </header>
      <form className="panel stack" onSubmit={submit}>
        <h2>Open an account</h2>
        <p className="muted">Names containing “Reject” or “Refer” trigger those KYC outcomes in the Atfinity simulator.</p>
        <label className="field">Full name
          <input id="name" required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
        </label>
        <label className="field">Email
          <input id="email" type="email" required value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
        </label>
        <label className="field">Account type
          <select id="type" value={form.customerType} onChange={(e) => setForm({ ...form, customerType: e.target.value })}>
            <option value="INDIVIDUAL">Personal</option>
            <option value="BUSINESS">Business</option>
          </select>
        </label>
        <button className="btn primary" disabled={busy}>{busy ? 'Starting…' : 'Open account'}</button>
      </form>
      {auth.enabled ? (
        <p className="muted small">Signed in as <strong>{auth.username}</strong>. This login is not linked to a bank account yet. <button className="linklike" onClick={auth.logout}>Sign out</button></p>
      ) : (
      <form className="panel stack" onSubmit={(e) => { e.preventDefault(); if (existing.trim()) onReady(existing.trim()); }}>
        <h2>Already a customer?</h2>
        <label className="field">Customer ID
          <input id="existing" placeholder="0192f1c4-…" value={existing} onChange={(e) => setExisting(e.target.value)} />
        </label>
        <button className="btn">Continue</button>
      </form>
      )}
    </main>
    </div>
  );
}

export function OnboardingProgress({ id, onReady, onRestart }: { id: string; onReady: (c: string) => void; onRestart: () => void }) {
  const [state, setState] = useState<Onboarding | null>(null);

  useEffect(() => {
    let stop = false;
    const tick = async () => {
      try {
        const s = await api.onboarding(id);
        if (!stop) setState(s);
        if (['COMPLETED', 'REJECTED', 'FAILED'].includes(s.status)) return;
      } catch {
        /* the saga may not have started yet */
      }
      if (!stop) setTimeout(tick, 1200);
    };
    tick();
    return () => { stop = true; };
  }, [id]);

  const reached = ONBOARDING_STEPS.findIndex(([s]) => s === state?.step);
  const done = state?.status === 'COMPLETED';
  return (
    <main className="shell narrow">
      <section className="panel stack">
        <p className="eyebrow">Opening your account</p>
        <h2>{done ? 'Your account is ready' : state?.status === 'REJECTED' ? 'We could not open your account' : 'Setting things up…'}</h2>
        <ol className="steps">
          {ONBOARDING_STEPS.map(([step, label], i) => (
            <li key={step} className={i <= reached ? 'done' : i === reached + 1 && !state?.reason ? 'now' : ''}>{label}</li>
          ))}
        </ol>
        {state?.reason && <p className="alert bad">{state.reason}</p>}
        {done && state?.customerId && (
          <>
            <dl className="kv">
              <div><dt>Sort code</dt><dd className="num">{fmtSort(state.sortCode)}</dd></div>
              <div><dt>Account number</dt><dd className="num">{state.accountNumber}</dd></div>
              <div><dt>Card</dt><dd>•••• {state.cardLast4}</dd></div>
            </dl>
            <button className="btn primary" onClick={() => onReady(state.customerId!)}>Go to my account</button>
          </>
        )}
        {state && state.status !== 'COMPLETED' && state.reason && <button className="btn" onClick={onRestart}>Start again</button>}
        <p className="muted mono small">Onboarding {id}</p>
      </section>
    </main>
  );
}
