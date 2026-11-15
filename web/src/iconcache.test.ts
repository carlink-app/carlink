import { describe, expect, it } from 'vitest';
import { applyCache, MAX_ICON_CHARS, validIcon, type CachedIcon } from './iconcache';

const PNG = 'data:image/png;base64,AAAA';

describe('validIcon', () => {
  it('only PNG data URLs within the size cap', () => {
    expect(validIcon(PNG)).toBe(true);
    expect(validIcon('data:image/svg+xml;base64,AAAA')).toBe(false);
    expect(validIcon('data:image/png;base64,' + 'A'.repeat(MAX_ICON_CHARS))).toBe(false);
    expect(validIcon(42)).toBe(false);
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
});
