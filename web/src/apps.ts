// Launcher grouping and sidebar (recents) bookkeeping; no DOM here.
import type { AppInfo, AppCategory } from './protocol';

/** Launcher order: what you need while driving first. */
export const CATEGORIES: readonly { cat: AppCategory; title: string }[] = [
  { cat: 'nav', title: 'Navigation' },
  { cat: 'energy', title: 'Charging & fuel' },
  { cat: 'music', title: 'Music' },
  { cat: 'video', title: 'Video' },
  { cat: 'social', title: 'Social' },
  { cat: 'work', title: 'Work' },
  { cat: 'other', title: 'Other' },
];

const KNOWN = new Set<string>(CATEGORIES.map((c) => c.cat));

/** Non-empty groups in CATEGORIES order, apps sorted by label. Unknown or missing categories go to Other. */
export function groupApps(list: readonly AppInfo[]): { cat: AppCategory; title: string; apps: AppInfo[] }[] {
  const sorted = [...list].sort((a, b) => (a.label || '').localeCompare(b.label || ''));
  return CATEGORIES
    .map(({ cat, title }) => ({
      cat, title,
      apps: sorted.filter((a) => (a.cat && KNOWN.has(a.cat) ? a.cat : 'other') === cat),
    }))
    .filter((g) => g.apps.length > 0);
}

/**
 * Sidebar after closing [pkg], and what to show next: if the closed app was in
 * front, the next one on the sidebar (most recent first), else nothing changes
 * in front. `next` null with `wasCurrent` = no apps left, show the launcher.
 */
export function afterClose(recents: readonly AppInfo[], current: string | null, pkg: string):
  { recents: AppInfo[]; wasCurrent: boolean; next: AppInfo | null } {
  const rest = recents.filter((r) => r.pkg !== pkg);
  const wasCurrent = current === pkg;
  return { recents: rest, wasCurrent, next: wasCurrent ? rest[0] ?? null : null };
}

/**
 * Recents with labels/icons refreshed from the phone's list. A stored icon is
 * kept until the phone's arrives (icons are fetched after the first frame).
 */
export function refreshRecents(recents: readonly AppInfo[], apps: readonly AppInfo[]): AppInfo[] {
  return recents.map((r) => {
    const a = apps.find((x) => x.pkg === r.pkg && x.cls === r.cls);
    if (!a) return r;
    return a.icon || !r.icon ? a : { ...a, icon: r.icon };
  });
}

/**
 * Sidebar with [pkg] moved to the front (the phone says it's in front),
 * taking its entry from the app list, else the sidebar; null if the page
 * doesn't know the app (the sidebar stays as it is).
 */
export function withFront(recents: readonly AppInfo[], apps: readonly AppInfo[], pkg: string): AppInfo[] | null {
  const app = apps.find((a) => a.pkg === pkg) ?? recents.find((a) => a.pkg === pkg);
  return app ? [app, ...recents.filter((r) => r.pkg !== app.pkg)] : null;
}

/**
 * What a new session brings back on the first pane-0 frame: relaunch the app
 * the page had in front (if still on the sidebar), or, for a page that had
 * none (reloaded), show what the phone says it put back ([front]).
 */
export function resumeAction(recents: readonly AppInfo[], current: string | null, front: unknown):
  { launch: AppInfo } | { front: string } | null {
  const a = current ? recents.find((r) => r.pkg === current) : undefined;
  if (a) return { launch: a };
  if (!current && typeof front === 'string' && front) return { front };
  return null;
}
