// App icons kept by the car browser (IndexedDB), keyed by package with the
// phone's icon hash: a session only fetches icons that are new or changed,
// instead of ~1 MB of icons at every connect.

const DB = 'carlink';
const STORE = 'icons';

export interface CachedIcon { h: string; icon: string }

/** A 96 px PNG is far smaller; anything bigger is refused (storage abuse). */
export const MAX_ICON_CHARS = 64 * 1024;
/** More launchable apps than any phone has. */
export const MAX_CACHED = 500;
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
