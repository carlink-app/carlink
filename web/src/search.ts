// Launcher search: text filter and A-Z letter index; no DOM here.
import type { AppInfo } from './protocol';

/** Lowercase, accents stripped ("Élan" -> "elan"). */
export const fold = (s: string): string => s.normalize('NFD').replace(/\p{M}/gu, '').toLocaleLowerCase();

const name = (a: AppInfo): string => (typeof a.label === 'string' && a.label) || a.pkg;

/**
 * Index letter of an app: its first letter, upper case, accents stripped
 * ("élan" -> "E", "Яндекс" -> "Я"); '#' for digits, symbols and empty labels.
 */
export function letterOf(a: AppInfo): string {
  const c = Array.from(fold(name(a)).trim())[0] ?? '';
  return /\p{L}/u.test(c) ? c.toLocaleUpperCase() : '#';
}

/** Letters that have apps: A-Z first, then other scripts, then '#'. */
export function letters(list: readonly AppInfo[]): string[] {
  const set = new Set(list.map(letterOf));
  const rank = (l: string) => (l === '#' ? 2 : /^[A-Z]$/.test(l) ? 0 : 1);
  return [...set].sort((a, b) => rank(a) - rank(b) || a.localeCompare(b));
}

const byLabel = (a: AppInfo, b: AppInfo) => name(a).localeCompare(name(b));

/** Apps whose label contains [query] (case and accents ignored), sorted by label. Blank query: none. */
export function filterApps(list: readonly AppInfo[], query: string): AppInfo[] {
  const q = fold(query.trim());
  if (!q) return [];
  return list.filter((a) => fold(name(a)).includes(q)).sort(byLabel);
}

/** Apps under [letter] in the index, sorted by label. */
export const appsByLetter = (list: readonly AppInfo[], letter: string): AppInfo[] =>
  list.filter((a) => letterOf(a) === letter).sort(byLabel);
