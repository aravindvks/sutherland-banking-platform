import { useCallback, useEffect, useState } from 'react';
import { ApiError, auth, authHeaders, gbp, toDate } from '../shared/api';
import { dashboardUrl, journeyUrl } from '../shared/observability';

/** A correlation ID that opens its journey in Grafana when VITE_GRAFANA_URL is set; plain text otherwise. */
function Cid({ id }: { id?: string | null }) {
  const url = journeyUrl(id);
  if (!url) return <>{id}</>;
  return <a className="cid" href={url} target="_blank" rel="noreferrer" title="Open this journey in Grafana (logs and traces)"
    onClick={(e) => e.stopPropagation()}>{id}</a>;
}

type Row = Record<string, string | null>;
type Queue = { reviews: Row[]; flaggedPayments: Row[]; heldPayments?: Row[]; creditReferrals?: Row[]; creditArrears?: Row[]; stuckSagas: Row[] };
type Journey = { correlationId: string; sagas: Row[]; payments: Row[]; paymentHistory: Row[] };

async function staffFetch<T>(path: string, staff: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(await authHeaders({ 'X-Staff-User': staff })) },
  });
  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (res.status === 403) throw new ApiError('Your login does not have the staff role needed for this action', 'GEN-FORBID-001');
  if (!res.ok) throw new ApiError(body?.detail ?? `Request failed (${res.status})`, body?.code);
  return body as T;
}

/** Staff portal (Control Room): review queue, flagged payments, stuck sagas, journey search. */
export default function Staff({ notify }: { notify: (m: string) => void }) {
  const [staff, setStaff] = useState(auth.username || 'aravind');
  const canDecide = !auth.enabled || auth.roles.includes('compliance');
  const [queue, setQueue] = useState<Queue | null>(null);
  const [sagas, setSagas] = useState<Row[]>([]);
  const [search, setSearch] = useState('');
  const [journey, setJourney] = useState<Journey | null>(null);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [runs, setRuns] = useState<Row[]>([]);
  const [exceptions, setExceptions] = useState<Row[]>([]);

  const refresh = useCallback(async () => {
    try {
      const [q, s] = await Promise.all([
        staffFetch<Queue>('/v1/staff/queue', staff),
        staffFetch<Row[]>('/v1/staff/sagas', staff),
      ]);
      setQueue(q);
      setSagas(s);
      const [r, x] = await Promise.all([
        staffFetch<Row[]>('/v1/staff/recon/runs', staff),
        staffFetch<Row[]>('/v1/staff/recon/exceptions', staff),
      ]);
      setRuns(r);
      setExceptions(x);
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Cannot reach the bank');
    }
  }, [staff, notify]);

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 4000);
    return () => clearInterval(t);
  }, [refresh]);

  const decide = async (sagaId: string, decision: 'CLEAR' | 'CONFIRM') => {
    try {
      await staffFetch(`/v1/staff/reviews/${sagaId}`, staff, {
        method: 'POST',
        body: JSON.stringify({ decision, note: notes[sagaId] || (decision === 'CLEAR' ? 'False positive' : 'Confirmed match') }),
      });
      notify(decision === 'CLEAR' ? 'Cleared: customer unfrozen, cards unblocked, held payments released' : 'Confirmed: customer closed');
      setTimeout(refresh, 1500);
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Review failed');
    }
  };

  const decidePayment = async (paymentId: string, decision: 'APPROVE' | 'REJECT') => {
    try {
      await staffFetch(`/v1/staff/payments/${paymentId}/decision`, staff, {
        method: 'POST',
        body: JSON.stringify({ decision, note: notes[paymentId] || (decision === 'APPROVE' ? 'Checked with the customer' : 'Not confirmed by the customer') }),
      });
      notify(decision === 'APPROVE' ? 'Approved: the payment is on its way' : 'Rejected: the customer has been told');
      setTimeout(refresh, 1500);
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Decision failed');
    }
  };

  const [limits, setLimits] = useState<Record<string, string>>({});
  const decideCredit = async (applicationId: string, decision: 'APPROVE' | 'DECLINE', engineLimit?: string) => {
    try {
      await staffFetch(`/v1/staff/credit-applications/${applicationId}/decision`, staff, {
        method: 'POST',
        body: JSON.stringify({ decision, creditLimit: decision === 'APPROVE' ? (limits[applicationId] || engineLimit || undefined) : undefined,
          note: notes[applicationId] || (decision === 'APPROVE' ? 'Income and affordability checked' : 'Not affordable on the evidence') }),
      });
      notify(decision === 'APPROVE' ? 'Approved: the customer gets the offer' : 'Declined: the customer has been told');
      setTimeout(refresh, 1500);
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Decision failed');
    }
  };

  const reconcile = async () => {
    try {
      await staffFetch('/v1/staff/recon/runs', staff, { method: 'POST' });
      notify('Reconciliation run for today completed');
      refresh();
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Reconciliation failed');
    }
  };

  const find = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      setJourney(await staffFetch<Journey>(`/v1/staff/journeys/${encodeURIComponent(search.trim())}`, staff));
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Search failed');
    }
  };

  const held = queue?.heldPayments ?? [];
  const referrals = queue?.creditReferrals ?? [];
  const arrears = queue?.creditArrears ?? [];
  const open = (queue?.reviews.length ?? 0) + (queue?.flaggedPayments.length ?? 0) + held.length + referrals.length + (queue?.stuckSagas.length ?? 0);
  return (
    <div className="staff-theme">
      <div className="staff-bar">
        <div className="staff-bar-inner">
          <span className="staff-badge">Staff only</span>
          <strong className="staff-title">Control Room</strong>
          <span className="staff-sub">Sutherland Banking Platform · operations and compliance</span>
          <span className="spacer" />
          <label className="staff-user">Signed in as
            {auth.enabled
              ? <strong>{auth.username}{canDecide ? ' · compliance' : ' · view only'}</strong>
              : <input id="staff" value={staff} onChange={(e) => setStaff(e.target.value)} />}
          </label>
          {auth.enabled && <button className="staff-link linklike" onClick={auth.logout}>Sign out</button>}
          {dashboardUrl() && <a className="staff-link" href={dashboardUrl()!} target="_blank" rel="noreferrer"
            title="Grafana: flow dashboards, alerts, logs and traces">Observability ↗</a>}
          <a className="staff-link" href="/">Customer app</a>
        </div>
      </div>
    <main className="shell wide">
      <div className="tiles">
        <div className="tile"><span className="eyebrow">Open reviews</span><span className="tile-v">{queue?.reviews.length ?? 0}</span></div>
        <div className="tile"><span className="eyebrow">Held payments</span><span className="tile-v">{(queue?.flaggedPayments.length ?? 0) + held.length}</span></div>
        <div className="tile"><span className="eyebrow">Stuck sagas</span><span className="tile-v">{queue?.stuckSagas.length ?? 0}</span></div>
        <div className="tile"><span className="eyebrow">Needs attention</span><span className={`tile-v ${open ? 'warn' : ''}`}>{open}</span></div>
      </div>

      <section className="panel stack">
        <div className="row between"><h2>Compliance reviews</h2><span className="pill p-n">{queue?.reviews.length ?? 0} open</span></div>
        {!queue?.reviews.length ? <p className="muted">No open reviews. Use “Sanctions hit” in the customer app to raise one.</p> : (
          <div className="scroll">
            <table className="tx">
              <tbody>
                {queue.reviews.map((r) => (
                  <tr key={r.sagaId}>
                    <td>
                      <div className="mono small">{r.customerId}</div>
                      <div className="meta">Screening hit · frozen since {fmt(r.startedAt)} · <Cid id={r.correlationId} /></div>
                      <input className="note" id={`note-${r.sagaId}`} placeholder="Review note" value={notes[r.sagaId!] ?? ''}
                        onChange={(e) => setNotes({ ...notes, [r.sagaId!]: e.target.value })} />
                    </td>
                    <td className="actions">
                      <button className="btn small primary" disabled={!canDecide} onClick={() => decide(r.sagaId!, 'CLEAR')}>Clear</button>
                      <button className="btn small danger" disabled={!canDecide} onClick={() => decide(r.sagaId!, 'CONFIRM')}>Confirm match</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="panel stack">
        <div className="row between"><h2>High-value payments to approve</h2><span className="pill p-n">{held.length} waiting</span></div>
        {!held.length ? <p className="muted">None. Payments of £10,000 or more wait here for approval (try a CHAPS payment in the customer app).</p> : (
          <div className="scroll">
            <table className="tx">
              <tbody>
                {held.map((p) => {
                  const c = (() => { try { return JSON.parse(p.creditor ?? '{}'); } catch { return {}; } })();
                  return (
                    <tr key={p.paymentId}>
                      <td>
                        <div><strong>{gbp(p.amount)}</strong> to {c.name ?? '—'} · {p.paymentType === 'CHAPS' ? 'CHAPS' : p.paymentType === 'INTERNAL' ? 'same bank' : 'Faster Payment'}</div>
                        <div className="meta">{c.sortCode ? `${c.sortCode} ${c.accountNumber} · ` : ''}held {fmt(p.createdAt)} · <Cid id={p.correlationId} /></div>
                        <input className="note" id={`note-${p.paymentId}`} placeholder="Decision note" value={notes[p.paymentId!] ?? ''}
                          onChange={(e) => setNotes({ ...notes, [p.paymentId!]: e.target.value })} />
                      </td>
                      <td className="actions">
                        <button className="btn small primary" disabled={!canDecide} onClick={() => decidePayment(p.paymentId!, 'APPROVE')}>Approve</button>
                        <button className="btn small danger" disabled={!canDecide} onClick={() => decidePayment(p.paymentId!, 'REJECT')}>Reject</button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="panel stack">
        <div className="row between"><h2>Credit card applications to underwrite</h2><span className="pill p-n">{referrals.length} waiting</span></div>
        {!referrals.length ? <p className="muted">None. The decision engine refers applications over £5,000 (apply in the customer app's Credit card tab).</p> : (
          <div className="scroll">
            <table className="tx">
              <tbody>
                {referrals.map((a) => (
                  <tr key={a.applicationId}>
                    <td>
                      <div><strong>{a.name ?? '—'}</strong> asks for {gbp(a.requestedLimit)} · income {gbp(a.annualIncome)} · engine limit {gbp(a.engineLimit)}</div>
                      <div className="meta">{a.reasons ?? ''} · received {fmt(a.createdAt)} · <Cid id={a.correlationId} /></div>
                      <div className="row">
                        <input className="note" placeholder={`Limit (default ${a.engineLimit ?? '—'})`} value={limits[a.applicationId!] ?? ''}
                          onChange={(e) => setLimits({ ...limits, [a.applicationId!]: e.target.value })} aria-label="Approved limit" />
                        <input className="note grow" placeholder="Decision note" value={notes[a.applicationId!] ?? ''}
                          onChange={(e) => setNotes({ ...notes, [a.applicationId!]: e.target.value })} />
                      </div>
                    </td>
                    <td className="actions">
                      <button className="btn small primary" disabled={!canDecide} onClick={() => decideCredit(a.applicationId!, 'APPROVE', a.engineLimit ?? undefined)}>Approve</button>
                      <button className="btn small danger" disabled={!canDecide} onClick={() => decideCredit(a.applicationId!, 'DECLINE')}>Decline</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {arrears.length > 0 && <>
          <h2>Credit cards in arrears</h2>
          {arrears.map((a) => (
            <div key={a.accountId} className="row between line">
              <div><div className="mono small">{a.accountId} · •••• {a.last4}</div><div className="meta">{a.customerId} · limit {gbp(a.creditLimit)}</div></div>
              <span className="pill p-bad">{a.arrearsCount} missed</span>
            </div>
          ))}
        </>}
      </section>

      <div className="grid2">
        <section className="panel stack">
          <h2>Money in held for review</h2>
          {!queue?.flaggedPayments.length ? <p className="muted">None.</p> : queue.flaggedPayments.map((p) => (
            <div key={p.paymentId} className="row between line">
              <div><div className="mono small">{p.paymentId}</div><div className="meta">{p.customerId}</div></div>
              <strong className="num">{gbp(p.amount)}</strong>
            </div>
          ))}
          <p className="muted small">Released automatically when the customer's review is cleared.</p>
        </section>
        <section className="panel stack">
          <h2>Stuck sagas</h2>
          {!queue?.stuckSagas.length ? <p className="muted">None waiting more than 10 minutes.</p> : queue.stuckSagas.map((s) => (
            <div key={s.sagaId} className="row between line">
              <div><strong>{s.type}</strong> · {s.step}<div className="meta">{s.customerId}</div></div>
              <span className="pill p-n">{s.status}</span>
            </div>
          ))}
        </section>
      </div>

      <section className="panel stack">
        <div className="row between">
          <h2>Reconciliation</h2>
          <button className="btn small primary" disabled={!canDecide} onClick={reconcile}>Run for today</button>
        </div>
        <p className="muted small">Every payment and card settlement the bank recorded, checked against Essence postings. Runs nightly at 02:00 UTC for the previous day.</p>
        {!runs.length ? <p className="muted">No runs yet.</p> : (
          <div className="scroll">
            <table className="tx">
              <tbody>
                {runs.slice(0, 6).map((r) => (
                  <tr key={r.runId}>
                    <td><strong>{r.source}</strong><div className="meta">{r.runDate} · {fmt(r.endedAt)}</div></td>
                    <td className="num">{r.matched} matched</td>
                    <td><span className={`pill ${r.status === 'MATCHED' ? 'p-ok' : 'p-bad'}`}>{r.exceptions} exceptions</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {exceptions.length > 0 && (
          <>
            <h3>Open exceptions</h3>
            {exceptions.map((x) => (
              <div key={x.exceptionId} className="row between line">
                <div><strong>{x.type?.replace(/_/g, ' ').toLowerCase()}</strong><div className="meta mono">{x.sourceRef} · {x.ownerQueue}</div></div>
                <strong className="num">{gbp(x.amount)}</strong>
              </div>
            ))}
          </>
        )}
      </section>

      <form className="panel stack" onSubmit={find}>
        <h2>Find a journey</h2>
        <div className="row">
          <input className="grow" id="search" placeholder="COR-PAY-… or COR-ONB-…" value={search} onChange={(e) => setSearch(e.target.value)} />
          <button className="btn primary">Search</button>
        </div>
        {journey && (
          <div className="stack">
            <p className="muted small">{journey.sagas.length} saga(s), {journey.payments.length} payment(s) for <Cid id={journey.correlationId} />
              {journeyUrl(journey.correlationId) && <> · <a href={journeyUrl(journey.correlationId)!} target="_blank" rel="noreferrer">Logs and trace in Grafana ↗</a></>}</p>
            {journey.sagas.map((s) => <div key={s.sagaId} className="line"><strong>{s.type}</strong> · {s.step} · <span className="pill p-n">{s.status}</span></div>)}
            <ol className="timeline">
              {journey.paymentHistory.map((h, i) => (
                <li key={i} className={['REJECTED', 'FAILED', 'FLAGGED'].includes(h.to ?? '') ? 'bad' : ''}>
                  <span className="meta">{fmt(h.at)} · {h.actor}</span>
                  <strong>{h.to}</strong>{h.reason ? ` — ${h.reason}` : ''}
                </li>
              ))}
            </ol>
          </div>
        )}
      </form>

      <section className="panel stack">
        <h2>Recent sagas</h2>
        <div className="scroll">
          <table className="tx">
            <tbody>
              {sagas.map((s) => (
                <tr key={s.sagaId} className="click" onClick={() => { setSearch(s.correlationId ?? ''); }}>
                  <td><strong>{s.type}</strong><div className="meta"><Cid id={s.correlationId} /></div></td>
                  <td>{s.step}</td>
                  <td><span className={`pill ${s.status === 'COMPLETED' ? 'p-ok' : s.status === 'FAILED' ? 'p-bad' : 'p-n'}`}>{s.status}</span></td>
                  <td className="meta">{fmt(s.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </main>
    </div>
  );
}

const fmt = (t?: string | null) => (t ? toDate(t).toLocaleString('en-GB') : '—');
