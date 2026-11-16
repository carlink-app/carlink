// Phone battery and signal for the sidebar (the `phone` message).

export interface PhoneStatus {
  /** 0-100, or null if unknown. */
  battery: number | null;
  charging: boolean;
  /** Bars 0-4; -1 no service; null unknown. */
  signal: number | null;
  /** "5G", "LTE", "3G", "2G" or "". */
  network: string;
}

const NETWORKS = new Set(['5G', 'LTE', '3G', '2G']);

export function parsePhoneStatus(m: Record<string, unknown>): PhoneStatus {
  const bat = typeof m.bat === 'number' && m.bat >= 0 && m.bat <= 100 ? Math.round(m.bat) : null;
  const sig = typeof m.sig === 'number' && Number.isInteger(m.sig) && m.sig >= -1 && m.sig <= 4 ? m.sig : null;
  return {
    battery: bat,
    charging: m.chg === true,
    signal: sig,
    network: typeof m.net === 'string' && NETWORKS.has(m.net) ? m.net : '',
  };
}

/** Screen-reader text, e.g. "Phone battery 80%, charging. Signal 3 of 4 bars, LTE". */
export function describe(s: PhoneStatus): string {
  const parts: string[] = [];
  if (s.battery !== null) parts.push(`Phone battery ${s.battery}%${s.charging ? ', charging' : ''}`);
  if (s.signal === -1) parts.push('No mobile signal');
  else if (s.signal !== null) parts.push(`Signal ${s.signal} of 4 bars${s.network ? `, ${s.network}` : ''}`);
  return parts.join('. ');
}
