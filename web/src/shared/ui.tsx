// Small pieces shared by the screens of every module.

export function Pill({ status }: { status: string }) {
  const tone = ['COMPLETED', 'ACTIVE', 'VALID'].includes(status) ? 'ok'
    : ['REJECTED', 'FAILED', 'FROZEN', 'BLOCKED', 'FLAGGED', 'RETURNED'].includes(status) ? 'bad' : 'n';
  return <span className={`pill p-${tone}`}>{status.replace('_', ' ').toLowerCase()}</span>;
}

export function parseCreditor(json?: string | null): Record<string, string | undefined> {
  try {
    return json ? JSON.parse(json) : {};
  } catch {
    return {};
  }
}

export const label = (type: string, scheme?: string) =>
  type === 'INBOUND' && scheme === 'BACS' ? 'Bacs credit'
    : ({ INTERNAL: 'Same-bank transfer', FPS: 'Faster Payment', CHAPS: 'CHAPS', BACS: 'Bacs', INBOUND: 'Money in', DIRECT_DEBIT: 'Direct Debit' } as Record<string, string>)[type] ?? type;
