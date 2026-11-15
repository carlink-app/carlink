import { describe, expect, it } from 'vitest';
import { acceptIcons, applyCache, chunkByBytes, ICON_REQUEST_BYTES, MAX_CACHED, MAX_ICON_CHARS, validIcon, type CachedIcon } from './iconcache';

const PNG = 'data:image/png;base64,AAAA';

describe('validIcon', () => {
  it('only PNG data URLs within the size cap', () => {
    expect(validIcon(PNG)).toBe(true);
    expect(validIcon('data:image/svg+xml;base64,AAAA')).toBe(false);
    expect(validIcon('data:image/png;base64,' + 'A'.repeat(MAX_ICON_CHARS))).toBe(false);
    expect(validIcon(42)).toBe(false);
  });
  it('accepts exactly the size cap, refuses one more, and needs the exact prefix', () => {
    const at = (n: number) => 'data:image/png;base64,' + 'A'.repeat(n - 'data:image/png;base64,'.length);
    expect(validIcon(at(MAX_ICON_CHARS))).toBe(true);
    expect(validIcon(at(MAX_ICON_CHARS + 1))).toBe(false);
    expect(validIcon('data:image/png;base64,')).toBe(true);
    expect(validIcon('DATA:image/png;base64,AAAA')).toBe(false);
    expect(validIcon(' data:image/png;base64,AAAA')).toBe(false);
    expect(validIcon('data:image/png,AAAA')).toBe(false);
    expect(validIcon('javascript:alert(1)')).toBe(false);
    expect(validIcon('')).toBe(false);
    expect(validIcon(null)).toBe(false);
    expect(validIcon(undefined)).toBe(false);
    expect(validIcon({ startsWith: () => true, length: 1 })).toBe(false);
  });
});

describe('applyCache', () => {
  it('fills cached icons with a matching hash and lists the rest', () => {
    const cache = new Map<string, CachedIcon>([['a', { h: '1', icon: PNG }], ['b', { h: 'old', icon: PNG }]]);
    const list = [{ pkg: 'a', ih: '1' }, { pkg: 'b', ih: 'new' }, { pkg: 'c', ih: '3' }, { pkg: 'd' }] as { pkg: string; ih?: string; icon?: string }[];
    expect(applyCache(list, cache)).toEqual(['b', 'c']);
    expect(list[0].icon).toBe(PNG);
    expect(list[1].icon).toBeUndefined();
    expect(list[3].icon).toBeUndefined(); // no hash: an old engine sends icons inline, or none
  });
  it('leaves icons sent inline alone', () => {
    const list = [{ pkg: 'a', ih: '1', icon: PNG }];
    expect(applyCache(list, new Map())).toEqual([]);
  });
  it('empty list, empty cache', () => {
    expect(applyCache([], new Map())).toEqual([]);
  });
  it('a cached icon for a package the phone sends without a hash is not used', () => {
    const list: { pkg: string; ih?: string; icon?: string }[] = [{ pkg: 'a' }];
    expect(applyCache(list, new Map([['a', { h: '1', icon: PNG }]]))).toEqual([]);
    expect(list[0].icon).toBeUndefined();
  });
  it('an empty inline icon counts as missing', () => {
    const list = [{ pkg: 'a', ih: '1', icon: '' }];
    expect(applyCache(list, new Map())).toEqual(['a']);
  });
  it('a non-string hash is ignored', () => {
    const list = [{ pkg: 'a', ih: 1 as unknown as string }];
    expect(applyCache(list, new Map([['a', { h: '1', icon: PNG }]]))).toEqual([]);
  });
  it('the same package twice is listed twice and both are filled from the cache', () => {
    const list: { pkg: string; ih?: string; icon?: string }[] = [{ pkg: 'a', ih: '1' }, { pkg: 'a', ih: '1' }];
    expect(applyCache(list, new Map([['a', { h: '1', icon: PNG }]]))).toEqual([]);
    expect(list.map((x) => x.icon)).toEqual([PNG, PNG]);
    expect(applyCache([{ pkg: 'b', ih: '2' }, { pkg: 'b', ih: '2' }], new Map())).toEqual(['b', 'b']);
  });
});

// What the core accepts per control line (core/src/server.rs MAX_LINE).
const MAX_LINE = 16 * 1024;
const lineBytes = (pkgs: string[]) => new TextEncoder().encode(JSON.stringify({ t: 'app-icons', pkgs }) + '\n').length;

describe('chunkByBytes', () => {
  const realistic = Array.from({ length: MAX_CACHED }, (_, i) => `com.example.vendor${i}.app.with.a.longish.name`);
  const worst = Array.from({ length: MAX_CACHED }, (_, i) => `p${i}.`.padEnd(255, 'x'));

  for (const [name, pkgs] of [['realistic', realistic], ['worst-case 255-char', worst]] as const) {
    it(`${name} names: every package once, in order, each line under the core's limit`, () => {
      const chunks = chunkByBytes(pkgs, ICON_REQUEST_BYTES);
      expect(chunks.flat()).toEqual(pkgs);
      expect(chunks.length).toBeGreaterThan(1);
      for (const c of chunks) {
        expect(c.length).toBeGreaterThan(0);
        expect(lineBytes(c)).toBeLessThan(MAX_LINE);
        expect(c.reduce((n, p) => n + p.length + 3, 0)).toBeLessThanOrEqual(ICON_REQUEST_BYTES);
      }
    });
  }

  it('fills chunks: only splits when the next name would go over', () => {
    expect(chunkByBytes(['aaaa', 'bbbb', 'cccc'], 14)).toEqual([['aaaa', 'bbbb'], ['cccc']]);
    expect(chunkByBytes(['aaaa', 'bbbb'], 13)).toEqual([['aaaa'], ['bbbb']]);
    expect(chunkByBytes(['aaaa', 'bbbb'], 7)).toEqual([['aaaa'], ['bbbb']]);
  });

  it('empty input: no messages', () => {
    expect(chunkByBytes([], ICON_REQUEST_BYTES)).toEqual([]);
  });

  it('a name over the budget goes alone, without an empty chunk before it', () => {
    const huge = 'x'.repeat(ICON_REQUEST_BYTES + 1);
    expect(chunkByBytes([huge], ICON_REQUEST_BYTES)).toEqual([[huge]]);
    expect(chunkByBytes(['a', huge, 'b'], ICON_REQUEST_BYTES)).toEqual([['a'], [huge], ['b']]);
  });
});

describe('acceptIcons', () => {
  const ICON = 'data:image/png;base64,BBBB';
  const setup = () => ({
    apps: [{ pkg: 'a', ih: '1' }, { pkg: 'b', ih: '2' }, { pkg: 'c' }] as { pkg: string; ih?: string; icon?: string }[],
    requested: new Set(['a', 'b', 'c']),
  });

  it('takes requested, valid icons whose hash matches the list', () => {
    const { apps, requested } = setup();
    const add = acceptIcons([{ pkg: 'a', ih: '1', icon: ICON }], apps, requested);
    expect([...add]).toEqual([['a', { h: '1', icon: ICON }]]);
    expect(apps[0].icon).toBe(ICON);
    expect(requested.has('a')).toBe(false);
  });

  it('refuses packages not asked for, or already received', () => {
    const { apps, requested } = setup();
    expect(acceptIcons([{ pkg: 'z', ih: '1', icon: ICON }], [...apps, { pkg: 'z', ih: '1' }], requested).size).toBe(0);
    acceptIcons([{ pkg: 'a', ih: '1', icon: ICON }], apps, requested);
    apps[0].icon = undefined;
    expect(acceptIcons([{ pkg: 'a', ih: '1', icon: ICON }], apps, requested).size).toBe(0);
    expect(apps[0].icon).toBeUndefined();
  });

  it('refuses invalid icons (and the request is used up)', () => {
    const { apps, requested } = setup();
    const add = acceptIcons([
      { pkg: 'a', ih: '1', icon: 'data:image/svg+xml;base64,AAAA' },
      { pkg: 'b', ih: '2', icon: 'data:image/png;base64,' + 'A'.repeat(MAX_ICON_CHARS) },
    ], apps, requested);
    expect(add.size).toBe(0);
    expect(apps.map((a) => a.icon)).toEqual([undefined, undefined, undefined]);
    expect(requested.has('a') || requested.has('b')).toBe(false);
  });

  it('refuses a hash different from the list, or an app without one', () => {
    const { apps, requested } = setup();
    const add = acceptIcons([{ pkg: 'a', ih: 'other', icon: ICON }, { pkg: 'c', ih: 'x', icon: ICON }], apps, requested);
    expect(add.size).toBe(0);
    expect(apps[0].icon).toBeUndefined();
    expect(apps[2].icon).toBeUndefined();
  });

  it('skips junk entries', () => {
    const { apps, requested } = setup();
    const junk = [null, 7, { ih: '1', icon: ICON }, { pkg: 1, ih: '1', icon: ICON }] as unknown as { pkg: string; ih: string; icon: string }[];
    expect(acceptIcons(junk, apps, requested).size).toBe(0);
    expect(requested.size).toBe(3);
  });

  it('a package listed twice (two activities) gets the icon on both', () => {
    const apps = [{ pkg: 'a', ih: '1' }, { pkg: 'a', ih: '1' }] as { pkg: string; ih?: string; icon?: string }[];
    const add = acceptIcons([{ pkg: 'a', ih: '1', icon: ICON }], apps, new Set(['a']));
    expect(add.size).toBe(1);
    expect(apps.map((a) => a.icon)).toEqual([ICON, ICON]);
  });
});
