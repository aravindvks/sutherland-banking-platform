// Accounts and payments module: send money (same bank, Faster Payments, CHAPS), Confirmation of Payee, standing orders
// and scheduled payments, Direct Debits, and the payment list and timeline.
import { useEffect, useState } from 'react';
import { api, gbp, toDate, ApiError, type Cop, type Mandate, type Me, type Payment, type Schedule } from '../../shared/api';
import { fmtDay } from '../../shared/format';
import { journeyUrl } from '../../shared/observability';
import { Pill, label, parseCreditor } from '../../shared/ui';

const BANK_SORT = '200001';
const todayUk = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London' }).format(new Date());
const plusDays = (iso: string, n: number) => {
  const d = new Date(`${iso}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};

type When = 'NOW' | 'LATER' | 'REPEAT';

export function Payments({ me, customerId, payments, schedules, mandates, open, notify, after }: {
  me: Me; customerId: string; payments: Payment[]; schedules: Schedule[]; mandates: Mandate[];
  open: (id: string) => void; notify: (m: string) => void; after: () => void;
}) {
  const [form, setForm] = useState({
    name: 'Arjun Mehta', sortCode: '040004', accountNumber: '12345678', amount: '15.00', reference: 'Dinner',
    scheme: 'FPS' as 'FPS' | 'CHAPS', when: 'NOW' as When, date: plusDays(todayUk(), 1), frequency: 'MONTHLY', endDate: '',
  });
  const [cop, setCop] = useState<{ key: string; result: Cop; accepted: boolean } | null>(null);
  const [busy, setBusy] = useState(false);

  const sort = form.sortCode.replace(/\D/g, '');
  const sameBank = sort === BANK_SORT;
  const key = `${form.name.trim()}|${sort}|${form.accountNumber}`;
  const checked = cop && cop.key === key ? cop : null;
  const canSend = !!checked && (checked.result.result === 'MATCH' || checked.result.result === 'UNAVAILABLE'
    || (checked.result.result === 'CLOSE_MATCH' && checked.accepted));
  const set = (patch: Partial<typeof form>) => setForm({ ...form, ...patch });

  const check = async () => {
    setBusy(true);
    try {
      const r = await api.confirmPayee(customerId, { name: form.name.trim(), sortCode: sort, accountNumber: form.accountNumber });
      setCop({ key, result: r, accepted: false });
    } catch (err) {
      setCop({ key, result: { result: 'UNAVAILABLE' }, accepted: false });
      notify(err instanceof ApiError ? `Name check unavailable: ${err.message}` : 'Name check unavailable');
    } finally {
      setBusy(false);
    }
  };

  const useSuggested = () => {
    const name = checked?.result.matchedName;
    if (!name) return;
    setForm({ ...form, name });
    setCop({ key: `${name}|${sort}|${form.accountNumber}`, result: { result: 'MATCH' }, accepted: false });
  };

  const send = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!me.account) return;
    if (!checked) { await check(); return; }
    if (!canSend) return;
    setBusy(true);
    const creditor = { name: form.name.trim(), sortCode: sort, accountNumber: form.accountNumber };
    const amount = { value: Number(form.amount).toFixed(2), currency: 'GBP' };
    const copResult = checked.result.result === 'CLOSE_MATCH' ? 'CLOSE_MATCH' : checked.result.result;
    const scheme = sameBank ? undefined : form.scheme;
    try {
      if (form.when === 'NOW') {
        const r = await api.pay(customerId, {
          debtorAccount: me.account.accountId, creditor, amount, remittanceInfo: form.reference, copResult, scheme,
        });
        notify(`Payment accepted · ${r.correlationId}`);
        after();
        open(r.paymentId);
      } else {
        await api.createSchedule(customerId, {
          kind: form.when === 'LATER' ? 'ONE_OFF' : 'STANDING_ORDER',
          frequency: form.when === 'REPEAT' ? form.frequency : undefined,
          startDate: form.date, endDate: form.when === 'REPEAT' && form.endDate ? form.endDate : undefined,
          debtorAccount: me.account.accountId, creditor, amount, reference: form.reference, scheme, copResult,
        });
        notify(form.when === 'LATER' ? `Payment scheduled for ${fmtDay(form.date)}` : 'Standing order set up');
        setTimeout(after, 1200);
      }
    } catch (err) {
      notify(err instanceof ApiError ? `${err.code ?? 'Error'}: ${err.message}` : 'Payment failed');
    } finally {
      setBusy(false);
    }
  };

  const r = checked?.result;
  return (
    <>
      <form className="panel stack" onSubmit={send}>
        <h2>Send money</h2>
        <p className="muted">Sort code 20-00-01 is this bank (instant transfer). Others go by Faster Payments, or CHAPS for large same-day payments. We check the name with the payee's bank first (Confirmation of Payee). 99-xx-xx is rejected by the scheme; 98-xx-xx is a bank outside the name check.</p>
        <div className="form">
          <label className="field">Payee name<input id="payee" required value={form.name} onChange={(e) => set({ name: e.target.value })} /></label>
          <label className="field">Sort code<input id="sort" required pattern="\d{2}-?\d{2}-?\d{2}" value={form.sortCode} onChange={(e) => set({ sortCode: e.target.value })} /></label>
          <label className="field">Account number<input id="acc" required pattern="\d{8}" value={form.accountNumber} onChange={(e) => set({ accountNumber: e.target.value })} /></label>
          <label className="field">Amount (£)<input id="amount" required inputMode="decimal" pattern="\d+(\.\d{1,2})?" value={form.amount} onChange={(e) => set({ amount: e.target.value })} /></label>
          <label className="field">Reference<input id="ref" maxLength={18} value={form.reference} onChange={(e) => set({ reference: e.target.value })} /></label>
        </div>
        <div className="row wrap">
          <span className="seg" role="group" aria-label="When">
            {(['NOW', 'LATER', 'REPEAT'] as When[]).map((w) => (
              <button type="button" key={w} aria-pressed={form.when === w} onClick={() => set({ when: w })}>
                {w === 'NOW' ? 'Pay now' : w === 'LATER' ? 'On a date' : 'Standing order'}
              </button>
            ))}
          </span>
          {!sameBank && (
            <span className="seg" role="group" aria-label="Scheme">
              <button type="button" aria-pressed={form.scheme === 'FPS'} onClick={() => set({ scheme: 'FPS' })}>Faster Payment · free</button>
              <button type="button" aria-pressed={form.scheme === 'CHAPS'} onClick={() => set({ scheme: 'CHAPS' })}>CHAPS · £20, same day</button>
            </span>
          )}
        </div>
        {form.when !== 'NOW' && (
          <div className="form">
            <label className="field">{form.when === 'LATER' ? 'Pay on' : 'First payment'}<input id="date" type="date" required min={todayUk()} value={form.date} onChange={(e) => set({ date: e.target.value })} /></label>
            {form.when === 'REPEAT' && (
              <>
                <label className="field">How often
                  <select id="freq" value={form.frequency} onChange={(e) => set({ frequency: e.target.value })}>
                    <option value="MONTHLY">Monthly</option>
                    <option value="WEEKLY">Weekly</option>
                  </select>
                </label>
                <label className="field">Last payment (optional)<input id="end" type="date" min={form.date} value={form.endDate} onChange={(e) => set({ endDate: e.target.value })} /></label>
              </>
            )}
          </div>
        )}
        {!sameBank && form.scheme === 'CHAPS' && <p className="muted small">CHAPS runs 06:00–17:30 UK time on working days; outside those hours the payment waits for the next opening. Payments of £10,000 or more are checked by our team first.</p>}
        {r && (
          <div className={`alert ${r.result === 'MATCH' ? 'ok' : r.result === 'NO_MATCH' ? 'bad' : 'warn'}`} role="status">
            {r.result === 'MATCH' && <><strong>Name matches.</strong> The account is held by {form.name.trim()}.</>}
            {r.result === 'CLOSE_MATCH' && (
              <div className="stack tight">
                <span><strong>Close match.</strong> The account is held by <strong>{r.matchedName}</strong>, not {form.name.trim()}.</span>
                <div className="row">
                  <button type="button" className="btn small primary" onClick={useSuggested}>Pay {r.matchedName}</button>
                  {!checked?.accepted && <button type="button" className="btn small" onClick={() => setCop({ ...checked!, accepted: true })}>Pay {form.name.trim()} anyway</button>}
                  {checked?.accepted && <span className="muted small">You chose to keep the name you typed.</span>}
                </div>
              </div>
            )}
            {r.result === 'NO_MATCH' && <><strong>Name does not match</strong> the account. Check the details with the person you are paying; we will not send this payment.</>}
            {r.result === 'UNAVAILABLE' && <><strong>We could not check the name</strong> with that bank. Only continue if you are sure the details are right.</>}
          </div>
        )}
        <div className="row">
          <button className="btn primary" disabled={busy || me.status !== 'ACTIVE' || (!!checked && !canSend)}>
            {busy ? 'Working…' : !checked ? 'Check name' : form.when === 'NOW' ? 'Send' : form.when === 'LATER' ? 'Schedule payment' : 'Set up standing order'}
          </button>
          {checked && <button type="button" className="btn small" onClick={() => setCop(null)}>Check again</button>}
        </div>
      </form>
      <Schedules customerId={customerId} schedules={schedules} notify={notify} after={after} />
      <DirectDebits customerId={customerId} mandates={mandates} notify={notify} after={after} />
      <section className="panel stack">
        <h2>All payments</h2>
        <PaymentList payments={payments} open={open} />
      </section>
    </>
  );
}

function Schedules({ customerId, schedules, notify, after }: {
  customerId: string; schedules: Schedule[]; notify: (m: string) => void; after: () => void;
}) {
  const act = async (fn: () => Promise<unknown>, text: string) => {
    try {
      await fn();
      notify(text);
      setTimeout(after, 1500);
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Request failed');
    }
  };
  return (
    <section className="panel stack">
      <h2>Standing orders and scheduled payments</h2>
      {!schedules.length ? <p className="muted">None yet. Choose “On a date” or “Standing order” above.</p> : (
        <div className="scroll">
          <table className="tx">
            <tbody>
              {schedules.map((s) => {
                const c = parseCreditor(s.creditor);
                const active = s.status === 'ACTIVE';
                return (
                  <tr key={s.scheduleId}>
                    <td>
                      <div>To {c.name} · <span className="num">{gbp(s.amount)}</span></div>
                      <div className="meta">
                        {s.kind === 'ONE_OFF' ? `Once on ${fmtDay(s.startDate)}` : `${s.frequency === 'WEEKLY' ? 'Weekly' : 'Monthly'} from ${fmtDay(s.startDate)}${s.endDate ? ` to ${fmtDay(s.endDate)}` : ''}`}
                        {active && s.nextDate ? ` · next ${fmtDay(s.nextDate)}` : ''}
                        {s.paymentsMade ? ` · ${s.paymentsMade} paid${s.lastStatus && s.lastStatus !== 'COMPLETED' ? ` (last ${s.lastStatus.toLowerCase()})` : ''}` : ''}
                        {s.scheme === 'CHAPS' ? ' · CHAPS' : ''}
                      </div>
                    </td>
                    <td><Pill status={s.status} /></td>
                    <td className="actions">
                      {active && <button className="btn small linklike" title="Demo: make the next payment now" onClick={() => act(() => api.runScheduleNow(customerId, s.scheduleId), 'Next payment sent now (demo)')}>Pay next now</button>}
                      {active && <button className="btn small danger" onClick={() => act(() => api.cancelSchedule(customerId, s.scheduleId), 'Cancelled')}>Cancel</button>}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function DirectDebits({ customerId, mandates, notify, after }: {
  customerId: string; mandates: Mandate[]; notify: (m: string) => void; after: () => void;
}) {
  const cancel = async (m: Mandate) => {
    try {
      await api.cancelMandate(customerId, m.mandateId);
      notify(`Cancelling your Direct Debit to ${m.originatorName}`);
      setTimeout(after, 1500);
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Request failed');
    }
  };
  return (
    <section className="panel stack">
      <h2>Direct Debits</h2>
      {!mandates.length ? <p className="muted">None yet. Companies set up Direct Debits with your account details; use “New Direct Debit” in the demo bar.</p> : (
        <div className="scroll">
          <table className="tx">
            <tbody>
              {mandates.map((m) => (
                <tr key={m.mandateId}>
                  <td>
                    <div>{m.originatorName}</div>
                    <div className="meta">Service user {m.serviceUserNumber} · reference {m.payerReference ?? '—'} · since {fmtDay(m.createdAt.slice(0, 10))}
                      {m.lastAmount ? ` · last ${gbp(m.lastAmount)} ${m.lastStatus?.toLowerCase()}` : ''}</div>
                  </td>
                  <td><Pill status={m.status} /></td>
                  <td className="actions">{m.status === 'ACTIVE' && <button className="btn small danger" onClick={() => cancel(m)}>Cancel</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="muted small">Protected by the Direct Debit Guarantee: if a Direct Debit is taken in error, open it under All payments and claim a full, immediate refund.</p>
    </section>
  );
}

export function PaymentList({ payments, open }: { payments: Payment[]; open: (id: string) => void }) {
  if (!payments.length) return <p className="muted">No payments yet. Send money, or use “Receive £20” in the demo bar.</p>;
  return (
    <div className="scroll">
      <table className="tx">
        <tbody>
          {payments.map((p) => {
            const inbound = p.paymentType === 'INBOUND';
            const c = parseCreditor(p.creditor);
            return (
              <tr key={p.paymentId} className="click" tabIndex={0} onClick={() => open(p.paymentId)} onKeyDown={(e) => e.key === 'Enter' && open(p.paymentId)}>
                <td>
                  <div>{inbound ? `From ${c.debtorName ?? 'another bank'}` : `To ${c.name ?? '—'}`}</div>
                  <div className="meta">{label(p.paymentType, c.scheme)}{p.scheduleId ? ' · standing order' : ''} · {toDate(p.createdAt).toLocaleString('en-GB')}</div>
                </td>
                <td><Pill status={p.status} /></td>
                <td className={`amt num ${inbound ? 'in' : ''}`}>{inbound ? '+' : '−'}{gbp(p.amount)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export function PaymentDetail({ id, back, customerId, notify }: { id: string; back: () => void; customerId: string; notify: (m: string) => void }) {
  const [p, setP] = useState<Payment | null>(null);
  useEffect(() => {
    let stop = false;
    const tick = async () => {
      try {
        const r = await api.payment(id);
        if (!stop) setP(r);
        if (['COMPLETED', 'REJECTED', 'FAILED', 'FLAGGED', 'RETURNED', 'REFUNDED'].includes(r.status)) return;
      } catch {
        /* not recorded yet */
      }
      if (!stop) setTimeout(tick, 1000);
    };
    tick();
    return () => { stop = true; };
  }, [id]);

  const c = parseCreditor(p?.creditor);
  return (
    <section className="panel stack">
      <div><button className="btn small" onClick={back}>← All payments</button></div>
      {!p ? <p className="muted">Waiting for the payment to be recorded…</p> : (
        <>
          <div className="row between">
            <div>
              <p className="eyebrow">{label(p.paymentType, c.scheme)}</p>
              <h2>{p.paymentType === 'INBOUND' ? `From ${c.debtorName}` : `To ${c.name}`}</h2>
            </div>
            <div className="row"><span className="balance small num">{gbp(p.amount)}</span><Pill status={p.status} /></div>
          </div>
          <dl className="kv">
            <div><dt>Correlation ID</dt><dd className="mono small">{p.correlationId}
              {journeyUrl(p.correlationId) && <> · <a href={journeyUrl(p.correlationId)!} target="_blank" rel="noreferrer">trace ↗</a></>}</dd></div>
            <div><dt>Payment ID</dt><dd className="mono small">{p.paymentId}</dd></div>
            {p.finastraRef && <div><dt>{p.paymentType === 'DIRECT_DEBIT' ? 'Bacs reference' : 'Finastra reference'}</dt><dd className="mono small">{p.finastraRef}</dd></div>}
            {p.copResult && <div><dt>Name check</dt><dd>{p.copResult.replace('_', ' ').toLowerCase()}</dd></div>}
            {p.fee && Number(p.fee) > 0 && <div><dt>Fee</dt><dd className="num">{gbp(p.fee)}</dd></div>}
            {p.paymentType === 'DIRECT_DEBIT' && <div><dt>Service user</dt><dd>{c.serviceUserNumber} · {c.payerReference}</dd></div>}
          </dl>
          {p.paymentType === 'DIRECT_DEBIT' && p.status === 'COMPLETED' && (
            <div className="row">
              <button className="btn small" onClick={async () => {
                try {
                  await api.claimRefund(customerId, p.paymentId);
                  notify('Refund requested under the Direct Debit Guarantee');
                  setTimeout(() => api.payment(id).then(setP).catch(() => undefined), 2500);
                } catch (err) {
                  notify(err instanceof ApiError ? err.message : 'Claim failed');
                }
              }}>Claim a refund (Direct Debit Guarantee)</button>
            </div>
          )}
          <h3>Timeline</h3>
          <ol className="timeline">
            {p.history?.map((h, i) => (
              <li key={i} className={['REJECTED', 'FAILED', 'FLAGGED'].includes(h.to) ? 'bad' : ''}>
                <span className="meta">{toDate(h.at).toLocaleTimeString('en-GB')} · {h.actor}</span>
                <strong>{h.to}</strong>{h.reason ? ` — ${h.reason}` : ''}
              </li>
            ))}
          </ol>
        </>
      )}
    </section>
  );
}
