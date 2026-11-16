// Runs the service worker that vite.config.ts generates against fake
// SW globals (caches, clients, fetch), so its caching rules are checked
// without a browser.
import { describe, expect, it } from 'vitest';
import config from '../vite.config';

type Handler = (e: any) => void;

function generate(files: string[]): string {
  const plugin = (config.plugins as any[]).find((p) => p?.name === 'tslink-sw');
  let src = '';
  const bundle = Object.fromEntries(files.map((f) => [f, {}]));
  plugin.generateBundle.call({ emitFile: (f: { fileName: string; source: string }) => { if (f.fileName === 'sw.js') src = f.source; } }, {}, bundle);
  return src;
}

const NONE = Symbol('not handled');
const ORIGIN = 'https://car.example';
const SCOPE = `${ORIGIN}/app/`;

function load(src: string, existingCaches: string[] = []) {
  const listeners: Record<string, Handler> = {};
  const store = new Map<string, { added: any[]; matches: any[][] }>(existingCaches.map((k) => [k, { added: [], matches: [] }]));
  const deleted: string[] = [];
  const calls = { skipWaiting: 0, claim: 0, fetched: [] as any[] };
  let hit: unknown = undefined;
  const open = (name: string) => {
    if (!store.has(name)) store.set(name, { added: [], matches: [] });
    const c = store.get(name)!;
    return Promise.resolve({
      addAll: (reqs: any[]) => { c.added.push(...reqs); return Promise.resolve(); },
      match: (key: any, opts: any) => { c.matches.push([key, opts]); return Promise.resolve(hit); },
    });
  };
  const self = {
    addEventListener: (t: string, h: Handler) => { listeners[t] = h; },
    skipWaiting: () => { calls.skipWaiting++; },
    clients: { claim: () => { calls.claim++; return Promise.resolve(); } },
    registration: { scope: SCOPE },
  };
  class Request { constructor(public url: string, public init?: any) {} }
  const globals = {
    self, Request,
    location: new URL(`${SCOPE}sw.js`),
    caches: {
      open,
      keys: () => Promise.resolve([...store.keys()]),
      delete: (k: string) => { deleted.push(k); store.delete(k); return Promise.resolve(true); },
    },
    fetch: (r: any) => { calls.fetched.push(r); return Promise.resolve('network'); },
  };
  new Function(...Object.keys(globals), src)(...Object.values(globals));
  /** Dispatch an event; resolves with what was passed to waitUntil/respondWith (NONE if neither). */
  const dispatch = (type: string, init: object = {}): Promise<unknown> => {
    let p: Promise<unknown> = Promise.resolve(NONE);
    listeners[type]({ ...init, waitUntil: (x: Promise<unknown>) => { p = x; }, respondWith: (x: Promise<unknown>) => { p = x; } });
    return p;
  };
  return { dispatch, store, deleted, calls, setHit: (h: unknown) => { hit = h; } };
}

const FILES = ['index.html', 'assets/main-abc.js', 'assets/main-abc.js.map', 'assets/style-def.css'];
const cacheName = (src: string) => /const CACHE = '([^']+)'/.exec(src)![1];

describe('generated service worker', () => {
  it('versions the cache by the bundle file list', () => {
    const a = generate(FILES);
    expect(cacheName(a)).toMatch(/^tslink-[0-9a-f]{8}$/);
    expect(cacheName(generate(FILES))).toBe(cacheName(a));
    expect(cacheName(generate(['index.html', 'assets/main-xyz.js']))).not.toBe(cacheName(a));
  });

  it('precaches the shell bypassing the HTTP cache, without source maps', async () => {
    const src = generate(FILES);
    const sw = load(src);
    await sw.dispatch('install');
    const added = sw.store.get(cacheName(src))!.added;
    expect(added.map((r) => r.url)).toEqual(['./', './index.html', './assets/main-abc.js', './assets/style-def.css']);
    expect(added.every((r) => r.init?.cache === 'reload')).toBe(true);
  });

  it('only skips waiting when the page asks', () => {
    const sw = load(generate(FILES));
    sw.dispatch('message', { data: 'something' });
    expect(sw.calls.skipWaiting).toBe(0);
    sw.dispatch('message', { data: 'activate' });
    expect(sw.calls.skipWaiting).toBe(1);
  });

  it('on activate deletes old tslink caches only, then claims clients', async () => {
    const src = generate(FILES);
    const sw = load(src, ['tslink-00000000', 'other-app', cacheName(src)]);
    await sw.dispatch('activate');
    expect(sw.deleted).toEqual(['tslink-00000000']);
    expect(sw.calls.claim).toBe(1);
  });

  it('serves navigations with any query from the cached index', async () => {
    const src = generate(FILES);
    const sw = load(src);
    sw.setHit('cached-index');
    const res = await sw.dispatch('fetch', { request: { method: 'GET', url: `${SCOPE}?demo=1`, mode: 'navigate' } });
    expect(res).toBe('cached-index');
    expect(sw.store.get(cacheName(src))!.matches).toEqual([[SCOPE, { ignoreSearch: true, ignoreVary: true }]]);
    expect(sw.calls.fetched).toEqual([]);
  });

  it('matches assets exactly (ignoring Vary) and falls back to the network', async () => {
    const src = generate(FILES);
    const sw = load(src);
    const req = { method: 'GET', url: `${SCOPE}assets/main-abc.js`, mode: 'cors' };
    expect(await sw.dispatch('fetch', { request: req })).toBe('network');
    expect(sw.store.get(cacheName(src))!.matches).toEqual([[req, { ignoreSearch: false, ignoreVary: true }]]);
    expect(sw.calls.fetched).toEqual([req]);
  });

  it('leaves non-GET and cross-origin requests to the browser', async () => {
    const sw = load(generate(FILES));
    for (const request of [
      { method: 'POST', url: `${SCOPE}x`, mode: 'cors' },
      { method: 'GET', url: 'https://9.9.0.1:4433/tslink', mode: 'cors' },
    ]) {
      // A handler that doesn't call respondWith lets the browser handle it.
      expect(await sw.dispatch('fetch', { request })).toBe(NONE);
    }
    expect(sw.calls.fetched).toEqual([]);
  });
});
