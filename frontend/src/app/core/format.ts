export function formatQty(value: number | string | null | undefined, scale: number, signed = false): string {
  if (value === null || value === undefined || value === '') {
    return '';
  }
  const n = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(n)) {
    return String(value);
  }
  const s = n.toLocaleString('en-US', { minimumFractionDigits: scale, maximumFractionDigits: scale });
  return signed && n > 0 ? '+' + s : s;
}

export function quantityError(raw: unknown, scale: number, allowZero = false): string | null {
  if (raw === null || raw === undefined || String(raw).trim() === '') {
    return 'Quantity is required';
  }
  const s = String(raw).trim();
  if (!/^\d+(\.\d+)?$/.test(s)) {
    return 'Enter a number without signs or separators';
  }
  const decimals = s.includes('.') ? s.split('.')[1].length : 0;
  if (decimals > scale) {
    return scale === 0 ? 'This UOM allows whole numbers only' : `Maximum ${scale} decimal places for this UOM`;
  }
  if (!allowZero && Number(s) <= 0) {
    return 'Quantity must be greater than 0';
  }
  return null;
}

export function formatDateTime(v: string | null | undefined): string {
  if (!v) {
    return '';
  }
  const d = new Date(v);
  return d.toLocaleString('en-GB', { year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

export const STATUS_TONE: Record<string, 'pos' | 'neg' | 'warn' | 'info' | 'muted'> = {
  POSTED: 'pos', COMPLETED: 'pos', APPROVED: 'pos', OPEN: 'info', DRAFT: 'muted', PARTIALLY_RECEIVED: 'warn',
  PENDING: 'warn', REVERSED: 'neg', REJECTED: 'neg', CANCELLED: 'muted', ACTIVE: 'pos', INACTIVE: 'muted',
};

export function humanize(code: string | null | undefined): string {
  if (!code) {
    return '';
  }
  return code.charAt(0) + code.slice(1).toLowerCase().replaceAll('_', ' ');
}
