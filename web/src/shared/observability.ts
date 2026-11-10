// Links into Grafana (LLD section 15). Set at build time with VITE_GRAFANA_URL; without it no link is shown,
// so a build for real customers simply leaves it unset. Dashboard uids come from
// infra/observability/grafana/generate_dashboards.py; "cid" is the Overview dashboard's correlation ID variable.
const base = (import.meta.env.VITE_GRAFANA_URL as string | undefined)?.replace(/\/$/, '');

export const observabilityEnabled = Boolean(base);

export type Dashboard = 'pbp-overview' | 'pbp-payments' | 'pbp-cards' | 'pbp-schedules'
  | 'pbp-onboarding' | 'pbp-credit' | 'pbp-recon' | 'pbp-platform';

/** A dashboard, last hour by default. */
export function dashboardUrl(uid: Dashboard = 'pbp-overview', from = 'now-1h'): string | null {
  return base ? `${base}/d/${uid}?from=${from}&to=now` : null;
}

/** One journey: the Overview dashboard filtered to its correlation ID; each log line links to its Tempo trace. */
export function journeyUrl(correlationId?: string | null): string | null {
  if (!base || !correlationId) return null;
  return `${base}/d/pbp-overview?from=now-24h&to=now&var-cid=${encodeURIComponent(correlationId)}`;
}
