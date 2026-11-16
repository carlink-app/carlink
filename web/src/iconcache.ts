// App icons kept by the car browser (IndexedDB), keyed by package with the
// phone's icon hash: a session only fetches icons that are new or changed,
// instead of ~1 MB of icons at every connect.

const DB = 'tslink';
const STORE = 'icons';

export interface CachedIcon { h: string; icon: string }

/** A 96 px PNG is far smaller; anything bigger is refused (storage abuse). */
export const MAX_ICON_CHARS = 64 * 1024;
/** More launchable apps than any phone has. */
export const MAX_CACHED = 500;
/** Budget per `app-icons` request: under the phone's 16 KB control line limit, with room to spare. */
export const ICON_REQUEST_BYTES = 12_000;
const PNG = 'data:image/png;base64,';

/** What may be stored or shown from the cache. */
export const validIcon = (v: unknown): v is string => typeof v === 'string' && v.startsWith(PNG) && v.length <= MAX_ICON_CHARS;

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

/** Everything cached (empty if storage is unavailable). */
export async function loadIcons(): Promise<Map<string, CachedIcon>> {
  const out = new Map<string, CachedIcon>();
  try {
    const db = await open();
    await new Promise<void>((resolve, reject) => {
      const req = db.transaction(STORE).objectStore(STORE).openCursor();
      req.onsuccess = () => {
        const c = req.result;
        if (!c) return resolve();
        const v = c.value as CachedIcon;
        if (typeof c.key === 'string' && v && typeof v.h === 'string' && validIcon(v.icon)) out.set(c.key, v);
        if (out.size >= MAX_CACHED) return resolve();
        c.continue();
      };
      req.onerror = () => reject(req.error);
    });
    db.close();
  } catch { /* private mode or storage off: no cache */ }
  return out;
}

/** Store icons; packages not in [keep] are dropped (uninstalled apps). */
export async function saveIcons(add: Map<string, CachedIcon>, keep?: Set<string>): Promise<void> {
  try {
    const db = await open();
    const tx = db.transaction(STORE, 'readwrite');
    const store = tx.objectStore(STORE);
    for (const [pkg, v] of add) store.put(v, pkg);
    if (keep) {
      const req = store.openKeyCursor();
      req.onsuccess = () => {
        const c = req.result;
        if (!c) return;
        if (typeof c.key !== 'string' || !keep.has(c.key)) store.delete(c.primaryKey);
        c.continue();
      };
    }
    await new Promise<void>((resolve, reject) => { tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error); });
    db.close();
  } catch { /* best effort */ }
}

/**
 * Which packages need their icon from the phone: those with a hash ("ih")
 * the cache doesn't have. Fills cached icons into the list in place.
 */
export function applyCache<T extends { pkg: string; ih?: string; icon?: string }>(list: T[], cache: Map<string, CachedIcon>): string[] {
  const missing: string[] = [];
  for (const a of list) {
    if (typeof a.ih !== 'string' || a.icon) continue;
    const c = cache.get(a.pkg);
    if (c && c.h === a.ih) a.icon = c.icon;
    else missing.push(a.pkg);
  }
  return missing;
}

/**
 * Splits package names into `app-icons` requests of at most [budget] bytes
 * each, counting a name as its length plus 3 (quotes and comma). A name over
 * the budget goes alone in its own chunk.
 */
export function chunkByBytes(pkgs: readonly string[], budget: number): string[][] {
  const out: string[][] = [];
  let chunk: string[] = [];
  let bytes = 0;
  for (const p of pkgs) {
    if (bytes + p.length + 3 > budget && chunk.length) { out.push(chunk); chunk = []; bytes = 0; }
    chunk.push(p);
    bytes += p.length + 3;
  }
  if (chunk.length) out.push(chunk);
  return out;
}

/**
 * Takes icons from an `app-icons` message: only packages in [requested]
 * (removed from it as they arrive), only valid icons, and only where the
 * app's hash in [apps] matches (the phone's list may have changed since ours).
 * Sets the icons on [apps] in place; returns what to cache.
 */
export function acceptIcons(
  list: readonly { pkg: string; ih: string; icon: string }[],
  apps: readonly { pkg: string; ih?: string; icon?: string }[],
  requested: Set<string>,
): Map<string, CachedIcon> {
  const add = new Map<string, CachedIcon>();
  for (const it of list) {
    if (!it || typeof it.pkg !== 'string' || !requested.delete(it.pkg) || !validIcon(it.icon)) continue;
    for (const a of apps) {
      if (a.pkg !== it.pkg || typeof a.ih !== 'string' || a.ih !== it.ih) continue;
      a.icon = it.icon;
      add.set(a.pkg, { h: a.ih, icon: it.icon });
    }
  }
  return add;
}
