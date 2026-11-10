// Debit cards module: the card and its controls (freeze, online, contactless, ATM, daily limit).
import { useState } from 'react';
import { api, gbp, ApiError, type CardControls, type Me } from '../../shared/api';

export function CardView({ me, customerId, notify, after }: { me: Me; customerId: string; notify: (m: string) => void; after: () => void }) {
  const card = me.card;
  const [draft, setDraft] = useState<CardControls | null>(null);
  if (!card) return <section className="panel"><p className="muted">No card yet.</p></section>;
  const current: CardControls = draft ?? {
    frozen: card.status === 'BLOCKED', dailyLimit: card.dailyLimit, online: card.online, contactless: card.contactless, atm: card.atm,
  };
  const apply = async (next: CardControls) => {
    setDraft(next);
    try {
      const r = await api.cardControls(customerId, next);
      notify(`Card controls updated · ${r.correlationId}`);
      setTimeout(() => { setDraft(null); after(); }, 2500);
    } catch (err) {
      setDraft(null);
      notify(err instanceof ApiError ? err.message : 'Could not update the card');
    }
  };
  const blocked = me.status !== 'ACTIVE' || current.frozen;
  const toggle = (key: 'frozen' | 'online' | 'contactless' | 'atm', label: string) => (
    <div className="ctl">
      <span>{label}</span>
      <button className="sw" role="switch" aria-checked={current[key]} aria-label={label}
        onClick={() => apply({ ...current, [key]: !current[key] })} />
    </div>
  );
  return (
    <section className="panel stack">
      <div className={`plastic ${blocked ? 'frozen' : ''}`}>
        <div className="row between"><strong>Debit</strong><span className="mono">{blocked ? 'FROZEN' : 'VISA'}</span></div>
        <div className="pan">•••• •••• •••• {card.last4}</div>
        <div className="row between small"><span>{me.account?.name}</span><span className="mono">{card.token}</span></div>
      </div>
      <div>
        {toggle('frozen', 'Freeze card')}
        {toggle('online', 'Online payments')}
        {toggle('contactless', 'Contactless')}
        {toggle('atm', 'ATM withdrawals')}
        <div className="ctl">
          <span>Daily limit</span>
          <span className="seg">
            {['100.00', '300.00', '1000.00'].map((v) => (
              <button key={v} aria-pressed={current.dailyLimit === v} onClick={() => apply({ ...current, dailyLimit: v })}>
                {gbp(v).replace('.00', '')}
              </button>
            ))}
          </span>
        </div>
      </div>
      <p className="muted">Changes go to Thredd and the bank's card cache; the next tap uses them within about 5 seconds.
        The decision comes from the EHI responder: card and customer status, channel, daily limit, then an Essence hold.</p>
    </section>
  );
}
