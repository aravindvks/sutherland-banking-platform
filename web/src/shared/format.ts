// Formatting shared by the screens of every module.
import { market } from './market';

export const fmtSort = (s?: string | null) => (s ? `${s.slice(0, 2)}-${s.slice(2, 4)}-${s.slice(4, 6)}` : '—');
export const fmtDay = (iso?: string | null) => (iso ? new Date(`${iso}T12:00:00Z`).toLocaleDateString(market().locale, { day: 'numeric', month: 'short', year: 'numeric' }) : '—');
