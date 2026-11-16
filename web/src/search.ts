// Launcher search: label filter; no DOM here.
import type { AppInfo } from './protocol';

/** Lowercase, accents stripped ("Élan" -> "elan"). */
export const fold = (s: string): string => s.normalize('NFD').replace(/\p{M}/gu, '').toLocaleLowerCase();

const name = (a: AppInfo): string => (typeof a.label === 'string' && a.label) || a.pkg;

const byLabel = (a: AppInfo, b: AppInfo) => name(a).localeCompare(name(b));

/** Apps whose label contains [query] (case and accents ignored), sorted by label. Blank query: none. */
export function filterApps(list: readonly AppInfo[], query: string): AppInfo[] {
  const q = fold(query.trim());
  if (!q) return [];
  return list.filter((a) => fold(name(a)).includes(q)).sort(byLabel);
}
