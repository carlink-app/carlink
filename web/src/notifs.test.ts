import { describe, expect, it } from 'vitest';
import {
  addBanner, addToTray, AUTO_HIDE_MS, callsFirst, clean, MAX_ICON, MAX_TEXT, MAX_TITLE, MAX_TRAY, MAX_VISIBLE, nextExpiry, pruneExpired,
  removeBanner, removeFromTray, safeIcon, sanitizeNotif, trayBadge, trayOthers, type Banner, type Notif,
} from './notifs';

const PNG = 'data:image/png;base64,iVBORw0KGgo=';
const base = { t: 'notif', id: 'n3f9a1c', pkg: 'com.whatsapp', app: 'WhatsApp', title: 'Alice', text: 'On my way', kind: 'msg' };
const n = (id: string, kind: Notif['kind'] = 'msg'): Notif => ({ id, pkg: 'p', app: 'A', title: id, text: '', kind });
const ids = (l: Banner[]) => l.map((b) => b.n.id);

describe('sanitizeNotif', () => {
  it('accepts the PROTOCOL.md example', () => {
    expect(sanitizeNotif({ ...base, icon: PNG })).toEqual({
      id: 'n3f9a1c', pkg: 'com.whatsapp', app: 'WhatsApp', title: 'Alice', text: 'On my way', kind: 'msg', icon: PNG,
    });
  });
  it('rejects bad ids', () => {
    for (const id of ['', 'a b', 'x'.repeat(65), 'n<1>', 'ü', 7, null, undefined]) {
      expect(sanitizeNotif({ ...base, id })).toBeNull();
    }
    expect(sanitizeNotif({ ...base, id: 'A-z_0'.padEnd(64, '9') })).not.toBeNull();
  });
  it('rejects non-objects and empty banners', () => {
    expect(sanitizeNotif(null)).toBeNull();
    expect(sanitizeNotif('notif')).toBeNull();
    expect(sanitizeNotif({ t: 'notif', id: 'x', pkg: '', app: '', title: '', text: '' })).toBeNull();
  });
  it('falls back: unknown kind -> other, missing app -> pkg, wrong types -> empty', () => {
    const r = sanitizeNotif({ ...base, kind: 'alarm', app: 5, title: { x: 1 }, text: ['a'] })!;
    expect(r).toEqual({ id: 'n3f9a1c', pkg: 'com.whatsapp', app: 'com.whatsapp', title: '', text: '', kind: 'other' });
    expect(sanitizeNotif({ ...base, kind: 'call' })!.kind).toBe('call');
  });
  it('truncates to the protocol limits', () => {
    const r = sanitizeNotif({ ...base, title: 't'.repeat(500), text: 'x'.repeat(5000) })!;
    expect(Array.from(r.title)).toHaveLength(MAX_TITLE);
    expect(Array.from(r.text)).toHaveLength(MAX_TEXT);
    expect(r.text.endsWith('…')).toBe(true);
  });
  it('keeps markup as plain text (rendered with textContent)', () => {
    expect(sanitizeNotif({ ...base, title: '<img src=x onerror=alert(1)>' })!.title).toBe('<img src=x onerror=alert(1)>');
  });
});

describe('clean', () => {
  it('collapses whitespace and strips control / bidi characters', () => {
    expect(clean('  a\n\nb\tc\u0000d\u202ee\u2066f ', 100)).toBe('a b c d e f');
  });
  it('does not split surrogate pairs', () => {
    const s = clean('😀'.repeat(10), 5);
    expect(Array.from(s)).toEqual(['😀', '😀', '😀', '😀', '…']);
  });
  it('leaves short strings alone', () => {
    expect(clean('abc', 3)).toBe('abc');
    expect(clean(undefined, 3)).toBe('');
  });
});

describe('safeIcon', () => {
  it('accepts base64 PNG and WebP data URLs', () => {
    expect(safeIcon(PNG)).toBe(PNG);
    expect(safeIcon('data:image/webp;base64,UklGRg==')).toBe('data:image/webp;base64,UklGRg==');
  });
  it('rejects everything else', () => {
    for (const v of [
      'https://evil.example/x.png', 'javascript:alert(1)', 'data:image/svg+xml;base64,PHN2Zz4=',
      'data:image/png,raw', 'data:image/png;base64,', 'data:image/png;base64,abc"onerror="x',
      'DATA:image/png;base64,AAAA', 42, null,
    ]) expect(safeIcon(v)).toBeUndefined();
  });
  it('enforces the size limit', () => {
    const prefix = 'data:image/png;base64,';
    expect(safeIcon(prefix + 'A'.repeat(MAX_ICON - prefix.length))).toBeDefined();
    expect(safeIcon(prefix + 'A'.repeat(MAX_ICON - prefix.length + 1))).toBeUndefined();
  });
  it('drops a bad icon but keeps the notification', () => {
    const r = sanitizeNotif({ ...base, icon: 'javascript:alert(1)' })!;
    expect(r).not.toBeNull();
    expect('icon' in r).toBe(false);
  });
});

describe('banner queue', () => {
  it('shows only the newest pop-up (the map stays visible)', () => {
    let l: Banner[] = [];
    for (const id of ['a', 'b', 'c', 'd']) l = addBanner(l, n(id), 0);
    expect(l).toHaveLength(MAX_VISIBLE);
    expect(ids(l)).toEqual(['d']);
  });
  it('replaces the same id in place and restarts its timer', () => {
    let l = addBanner([], n('a'), 0);
    l = addBanner(l, { ...n('a'), title: 'updated' }, 5000);
    expect(ids(l)).toEqual(['a']);
    expect(l[0].n.title).toBe('updated');
    expect(l[0].expires).toBe(5000 + AUTO_HIDE_MS);
  });
  it('removes by id (unknown ids are a no-op)', () => {
    let l = addBanner(addBanner([], n('call', 'call'), 0), n('b'), 0);
    l = removeBanner(l, 'call');
    expect(ids(l)).toEqual(['b']);
    expect(ids(removeBanner(l, 'zzz'))).toEqual(['b']);
  });
  it('expires after 6 s, except calls', () => {
    let l = addBanner([], n('call', 'call'), 0);
    l = addBanner(l, n('m'), 1000);
    expect(nextExpiry(l)).toBe(1000 + AUTO_HIDE_MS);
    expect(ids(pruneExpired(l, 1000 + AUTO_HIDE_MS - 1))).toEqual(['m', 'call']);
    l = pruneExpired(l, 1000 + AUTO_HIDE_MS);
    expect(ids(l)).toEqual(['call']);
    expect(nextExpiry(l)).toBeNull();
    expect(ids(pruneExpired(l, 1e12))).toEqual(['call']);
  });
  it('an update can turn a banner into a call (no expiry) and back', () => {
    let l = addBanner([], n('x'), 0);
    l = addBanner(l, n('x', 'call'), 100);
    expect(l[0].expires).toBeNull();
    l = addBanner(l, n('x', 'msg'), 200);
    expect(l[0].expires).toBe(200 + AUTO_HIDE_MS);
  });
  it('does not mutate its input', () => {
    const l = addBanner([], n('a'), 0);
    const copy = structuredClone(l);
    addBanner(l, n('b'), 0);
    removeBanner(l, 'a');
    pruneExpired(l, 1e12);
    expect(l).toEqual(copy);
  });
});

describe('sanitizeNotif edge cases', () => {
  it('keeps text at exactly the limit and ellipsizes one over', () => {
    expect(clean('t'.repeat(MAX_TITLE), MAX_TITLE)).toBe('t'.repeat(MAX_TITLE));
    const over = clean('t'.repeat(MAX_TITLE + 1), MAX_TITLE);
    expect(Array.from(over)).toHaveLength(MAX_TITLE);
    expect(over).toBe('t'.repeat(MAX_TITLE - 1) + '…');
  });
  it('whitespace-only fields count as empty', () => {
    expect(sanitizeNotif({ ...base, pkg: ' ', app: '\n', title: '\t', text: '‮ ' })).toBeNull();
    expect(sanitizeNotif({ ...base, app: '  ', title: ' ', text: ' ' })).toEqual({
      id: 'n3f9a1c', pkg: 'com.whatsapp', app: 'com.whatsapp', title: '', text: '', kind: 'msg',
    });
  });
  it('one non-empty field is enough', () => {
    expect(sanitizeNotif({ id: 'x', title: 'Hi' })).toEqual({ id: 'x', pkg: '', app: '', title: 'Hi', text: '', kind: 'other' });
    expect(sanitizeNotif({ id: 'x', text: 'Hi' })!.text).toBe('Hi');
  });
  it('does not pass unknown fields through', () => {
    const r = sanitizeNotif({ ...base, t: 'notif', onclick: 'x', __proto__: { polluted: 1 } }) as unknown as Record<string, unknown>;
    expect(Object.keys(r).sort()).toEqual(['app', 'id', 'kind', 'pkg', 'text', 'title']);
  });
  it('trims before ellipsizing (no "word …")', () => {
    expect(clean('abcd efgh', 6)).toBe('abcd…');
  });
});

describe('banner queue edge cases', () => {
  it('nextExpiry of an empty list is null and picks the earliest', () => {
    expect(nextExpiry([])).toBeNull();
    let l = addBanner([], n('a'), 3000);
    l = addBanner(l, n('b'), 1000);
    expect(nextExpiry(l)).toBe(1000 + AUTO_HIDE_MS);
  });
  it('updating a banner does not reorder or evict the others', () => {
    let l: Banner[] = [];
    for (const id of ['c1', 'c2']) l = addBanner(l, n(id, 'call'), 0);
    l = addBanner(l, n('m'), 0);
    l = addBanner(l, n('c1', 'call'), 10);
    expect(ids(l)).toEqual(['m', 'c2', 'c1']);
  });
});

describe('ringing calls survive the cap', () => {
  it('evicts the oldest non-call banner instead of a call', async () => {
    const { addBanner } = await import('./notifs');
    const mk = (id: string, kind: 'call' | 'msg') =>
      ({ id, pkg: 'p', app: 'A', title: id, text: '', kind } as Parameters<typeof addBanner>[1]);
    let list = addBanner([], mk('call', 'call'), 0);
    for (const id of ['m1', 'm2', 'm3', 'm4']) list = addBanner(list, mk(id, 'msg'), 1);
    expect(list.map((b) => b.n.id)).toEqual(['m4', 'call']);
  });
});

describe('tray', () => {
  it('keeps everything that popped up, newest first, until removed', () => {
    let t: Notif[] = [];
    for (const id of ['a', 'b', 'c']) t = addToTray(t, n(id));
    expect(t.map((x) => x.id)).toEqual(['c', 'b', 'a']);
    t = removeFromTray(t, 'b');
    expect(t.map((x) => x.id)).toEqual(['c', 'a']);
  });
  it('an update moves it to the top with the new content', () => {
    let t = addToTray(addToTray([], n('a')), n('b'));
    t = addToTray(t, { ...n('a'), title: 'new' });
    expect(t.map((x) => x.id)).toEqual(['a', 'b']);
    expect(t[0].title).toBe('new');
  });
  it('drops calls (also when an entry turns into one) and caps the size', () => {
    let t = addToTray([], n('x'));
    t = addToTray(t, n('x', 'call'));
    expect(t).toEqual([]);
    for (let i = 0; i < MAX_TRAY + 5; i++) t = addToTray(t, n(`m${i}`));
    expect(t).toHaveLength(MAX_TRAY);
    expect(t[0].id).toBe(`m${MAX_TRAY + 4}`);
  });
});

describe('banner and tray together (as main.ts uses them)', () => {
  // One incoming notif: banner + tray, like onMessage('notif').
  const arrive = (s: { b: Banner[]; t: Notif[] }, x: Notif, now = 0) => ({ b: addBanner(s.b, x, now), t: addToTray(s.t, x) });
  const forget = (s: { b: Banner[]; t: Notif[] }, id: string) => ({ b: removeBanner(s.b, id), t: removeFromTray(s.t, id) });

  it('a new message replaces the pop-up but every message stays in the tray', () => {
    let s = { b: [] as Banner[], t: [] as Notif[] };
    for (const id of ['a', 'b', 'c']) s = arrive(s, n(id));
    expect(ids(s.b)).toEqual(['c']);
    expect(s.t.map((x) => x.id)).toEqual(['c', 'b', 'a']);
    expect(trayOthers(s.t, 'c')).toBe(2);
  });
  it('a ringing call pops up alongside one message, and is not in the tray', () => {
    let s = { b: [] as Banner[], t: [] as Notif[] };
    s = arrive(s, n('m1'));
    s = arrive(s, n('call', 'call'));
    s = arrive(s, n('m2'));
    expect(ids(s.b)).toEqual(['m2', 'call']);
    expect(ids(callsFirst(s.b))).toEqual(['call', 'm2']);
    expect(s.t.map((x) => x.id)).toEqual(['m2', 'm1']);
    expect(trayOthers(s.t, 'call')).toBe(2); // main.ts hides the chip on calls anyway
  });
  it('forgetting (gone / opened / dismissed) clears both', () => {
    let s = arrive(arrive({ b: [], t: [] }, n('a')), n('b'));
    s = forget(s, 'b');
    expect(s.b).toEqual([]);
    expect(s.t.map((x) => x.id)).toEqual(['a']);
  });
  it('a banner expiring leaves it in the tray', () => {
    let s = arrive({ b: [], t: [] }, n('a'), 0);
    s = { ...s, b: pruneExpired(s.b, AUTO_HIDE_MS) };
    expect(s.b).toEqual([]);
    expect(s.t.map((x) => x.id)).toEqual(['a']);
  });
});

describe('trayOthers ("+N" chip)', () => {
  it('counts tray entries other than the banner', () => {
    const t = [n('a'), n('b'), n('c')];
    expect(trayOthers(t, 'a')).toBe(2);
    expect(trayOthers(t, 'zzz')).toBe(3);
    expect(trayOthers([n('a')], 'a')).toBe(0);
    expect(trayOthers([], 'a')).toBe(0);
  });
});

describe('trayBadge', () => {
  it('shows the count up to 9, then 9+', () => {
    expect(trayBadge(0)).toBe('');
    expect(trayBadge(1)).toBe('1');
    expect(trayBadge(9)).toBe('9');
    expect(trayBadge(10)).toBe('9+');
    expect(trayBadge(MAX_TRAY)).toBe('9+');
  });
});

describe('callsFirst', () => {
  it('puts ringing calls first and keeps the queue order otherwise (stable)', () => {
    let l: Banner[] = [];
    l = addBanner(l, n('c1', 'call'), 0);
    l = addBanner(l, n('m'), 0);
    l = addBanner(l, n('c2', 'call'), 0);
    expect(ids(l)).toEqual(['c2', 'm', 'c1']);
    expect(ids(callsFirst(l))).toEqual(['c2', 'c1', 'm']);
  });
  it('does not mutate its input and handles empty / no-call lists', () => {
    const l = addBanner(addBanner([], n('c', 'call'), 0), n('m'), 0);
    const copy = structuredClone(l);
    callsFirst(l);
    expect(l).toEqual(copy);
    expect(callsFirst([])).toEqual([]);
    expect(ids(callsFirst(addBanner([], n('m'), 0)))).toEqual(['m']);
  });
});
