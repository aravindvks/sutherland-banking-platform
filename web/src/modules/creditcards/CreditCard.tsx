import { useState } from 'react';
import { api, gbp, ApiError, type CreditCards, type Me } from '../../shared/api';

// Credit card on Thredd credit processing: apply, read the offer, accept, then use and repay it.
// The limit and APR come from the decision engine; balance, available credit and statements from Thredd.

const STATUS_TEXT: Record<string, string> = {
  RECEIVED: 'Checking your application (soft search: no mark on your credit file)…',
  REFERRED: 'An underwriter is reviewing your application. We will let you know.',
  DECLINED: 'We cannot offer you a credit card at the moment.',
  EXPIRED: 'This application has expired.',
};

export default function CreditCardView({ me, customerId, credit, notify, after }: {
  me: Me; customerId: string; credit: CreditCards | null; notify: (m: string) => void; after: () => void;
}) {
  const account = credit?.accounts.find((a) => a.status !== 'WITHDRAWN' && a.status !== 'CLOSED');
  const latest = credit?.applications[0];
  if (account) return <Account me={me} customerId={customerId} account={account} notify={notify} after={after} />;
  if (latest && latest.status === 'OFFERED') return <Offer customerId={customerId} app={latest} notify={notify} after={after} />;
  return (
    <>
      {latest && STATUS_TEXT[latest.status] && (
        <p className={`alert ${latest.status === 'DECLINED' ? 'bad' : ''}`}>{STATUS_TEXT[latest.status]}
          {latest.status === 'DECLINED' && latest.reasons ? <span className="muted"> Reason: {latest.reasons.replace(/_/g, ' ').toLowerCase()}.</span> : null}</p>
      )}
      {(!latest || ['DECLINED', 'EXPIRED', 'OPEN'].includes(latest.status)) && (
        <Apply me={me} customerId={customerId} notify={notify} after={after} />
      )}
    </>
  );
}

function Apply({ me, customerId, notify, after }: { me: Me; customerId: string; notify: (m: string) => void; after: () => void }) {
  const [f, setF] = useState({
    name: me.account?.name ?? '', annualIncome: '42000', employmentStatus: 'EMPLOYED',
    monthlyHousing: '1100', monthlyCommitments: '150', requestedLimit: '1500', consentToSoftSearch: false,
  });
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const r = await api.applyCreditCard(customerId, f);
      notify(`Application sent · ${r.correlationId}`);
      setTimeout(after, 2500);
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Could not send the application');
    }
  };
  return (
    <form className="panel stack" onSubmit={submit}>
      <h2>Apply for a credit card</h2>
      <p className="muted">Representative 24.9% APR (variable). Checking your eligibility is a soft search and does not affect your credit score.</p>
      <div className="grid2">
        <label className="field">Name on the card<input value={f.name} onChange={set('name')} required /></label>
        <label className="field">Employment
          <select value={f.employmentStatus} onChange={set('employmentStatus')}>
            {['EMPLOYED', 'SELF_EMPLOYED', 'RETIRED', 'STUDENT', 'UNEMPLOYED'].map((v) => <option key={v} value={v}>{v.replace('_', ' ').toLowerCase()}</option>)}
          </select></label>
        <label className="field">Annual income (£)<input inputMode="decimal" value={f.annualIncome} onChange={set('annualIncome')} required /></label>
        <label className="field">Rent or mortgage per month (£)<input inputMode="decimal" value={f.monthlyHousing} onChange={set('monthlyHousing')} /></label>
        <label className="field">Other credit repayments per month (£)<input inputMode="decimal" value={f.monthlyCommitments} onChange={set('monthlyCommitments')} /></label>
        <label className="field">Credit limit you would like (£)<input inputMode="decimal" value={f.requestedLimit} onChange={set('requestedLimit')} required /></label>
      </div>
      <label className="row"><input type="checkbox" checked={f.consentToSoftSearch}
        onChange={(e) => setF({ ...f, consentToSoftSearch: e.target.checked })} /> I agree to a soft credit search</label>
      <div><button className="btn primary" disabled={!f.consentToSoftSearch}>Check eligibility and apply</button></div>
    </form>
  );
}

function Offer({ customerId, app, notify, after }: {
  customerId: string; app: CreditCards['applications'][number]; notify: (m: string) => void; after: () => void;
}) {
  const [read, setRead] = useState(false);
  const accept = async () => {
    try {
      await api.acceptCreditOffer(customerId, app.applicationId);
      notify('Agreement accepted. Opening your credit card…');
      setTimeout(after, 3000);
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Could not accept the offer');
    }
  };
  return (
    <section className="panel stack">
      <h2>Your credit card offer</h2>
      <dl className="kv">
        <div><dt>Credit limit</dt><dd className="balance">{gbp(app.offeredLimit)}</dd></div>
        <div><dt>Purchase rate</dt><dd>{app.purchaseApr}% p.a. (variable)</dd></div>
        <div><dt>Representative APR</dt><dd>{app.purchaseApr}% APR</dd></div>
        <div><dt>Minimum payment</dt><dd>£5 or 1% of the balance plus interest and fees, whichever is more</dd></div>
        <div><dt>Late payment fee</dt><dd>£12</dd></div>
        <div><dt>Right to withdraw</dt><dd>14 days after the agreement</dd></div>
      </dl>
      <p className="muted small">Pre-contract credit information (SECCI) summary. The full agreement is regulated by the Consumer Credit Act 1974.</p>
      <label className="row"><input type="checkbox" checked={read} onChange={(e) => setRead(e.target.checked)} /> I have read the pre-contract information and the agreement</label>
      <div><button className="btn primary" disabled={!read} onClick={accept}>Accept and open my card</button></div>
    </section>
  );
}

function Account({ me, customerId, account, notify, after }: {
  me: Me; customerId: string; account: CreditCards['accounts'][number]; notify: (m: string) => void; after: () => void;
}) {
  const [amount, setAmount] = useState('');
  const stmt = account.statements[0];
  const run = async (fn: () => Promise<string>) => {
    try {
      notify(await fn());
      setTimeout(after, 2500);
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Request failed');
    }
  };
  const canWithdraw = account.status === 'ACTIVE' && new Date(account.withdrawUntil) > new Date();
  return (
    <div className="grid2">
      <section className="panel stack">
        <div className={`plastic credit ${account.status !== 'ACTIVE' ? 'frozen' : ''}`}>
          <div className="row between"><strong>Credit</strong><span className="mono">{account.status === 'ARREARS' ? 'IN ARREARS' : 'VISA'}</span></div>
          <div className="pan">•••• •••• •••• {account.last4}</div>
          <div className="row between small"><span>{me.account?.name}</span><span className="mono">{account.cardToken}</span></div>
        </div>
        <dl className="kv">
          <div><dt>Balance</dt><dd className="balance num">{account.balance?.startsWith('-')
            ? <>{gbp(account.balance.slice(1))} <span className="muted small">in credit</span></>
            : gbp(account.balance)}</dd></div>
          <div><dt>Available credit</dt><dd className="num">{gbp(account.availableCredit)}</dd></div>
          <div><dt>Credit limit</dt><dd className="num">{gbp(account.creditLimit)}</dd></div>
          <div><dt>Purchase rate</dt><dd>{account.purchaseApr}%</dd></div>
          {account.pending && account.pending !== '0.00' && <div><dt>Pending</dt><dd className="num">{gbp(account.pending)}</dd></div>}
        </dl>
        {account.liveError && <p className="alert bad">{account.liveError}</p>}
        {account.balance?.startsWith('-') && <p className="alert">You have paid more than you owe. The extra is credited against future spending.</p>}
        {account.status === 'ARREARS' && <p className="alert bad">A minimum payment was missed and a £12 fee charged. Paying the minimum on your next statement brings the account back up to date.</p>}
        <form className="row" onSubmit={(e) => {
          e.preventDefault();
          run(async () => {
            const r = await api.repayCreditCard(customerId, account.accountId, me.account!.accountId, amount);
            setAmount('');
            return `Repayment of ${gbp(amount)} sent · ${r.correlationId}`;
          });
        }}>
          <input className="grow" inputMode="decimal" placeholder="Amount (£)" value={amount} onChange={(e) => setAmount(e.target.value)} aria-label="Repayment amount" />
          <button className="btn primary" disabled={!me.account || !amount}>Repay from current account</button>
        </form>
        {stmt && <div className="row">
          <button className="btn small" onClick={() => setAmount(stmt.minimumPayment)}>Minimum {gbp(stmt.minimumPayment)}</button>
          <button className="btn small" onClick={() => setAmount(account.balance ?? stmt.balance)}>Full balance</button>
        </div>}
        {canWithdraw && <button className="btn small" onClick={() => run(async () => {
          await api.withdrawCreditCard(customerId, account.accountId);
          return 'Withdrawal requested';
        })}>Withdraw from the agreement (until {new Date(account.withdrawUntil).toLocaleDateString('en-GB')})</button>}
      </section>
      <section className="panel stack">
        <h2>Statements</h2>
        {account.statements.length === 0 && <p className="muted">No statement yet. Thredd sends one each month.</p>}
        {account.statements.map((s) => (
          <div key={s.statementId} className="ctl">
            <span>{s.statementDate} · balance {gbp(s.balance)} · minimum {gbp(s.minimumPayment)} by {s.dueDate}
              {s.interest !== '0.00' ? ` · interest ${gbp(s.interest)}` : ''}{s.lateFee ? ` · late fee ${gbp(s.lateFee)}` : ''}</span>
            <span className={`pill ${s.status === 'MISSED' ? 'p-bad' : s.status === 'ISSUED' ? 'p-n' : 'p-ok'}`}>{s.status.replace(/_/g, ' ').toLowerCase()}</span>
          </div>
        ))}
        <h2>Recent activity</h2>
        {(account.transactions ?? []).slice().reverse().map((t) => (
          <div key={t.ref} className="ctl"><span>{t.description} <span className="muted small">{t.type.toLowerCase()}</span></span>
            <span className="num">{t.amount.startsWith('-') ? `−${gbp(t.amount.slice(1))}` : gbp(t.amount)}</span></div>
        ))}
      </section>
    </div>
  );
}
