import { describe, expect, it } from 'vitest';
import { addPin, isPinned, MAX_PINS, parsePins, removePin, unpinnedRecents } from './pins';
import { dropIndex, movePin } from './pins';
import type { AppInfo } from './protocol';

const app = (pkg: string, cls = `${pkg}.Main`): AppInfo => ({ pkg, cls, label: pkg.toUpperCase() });

describe('parsePins', () => {
  it('reads what was stored', () => {
    const pins = [app('a'), { ...app('b'), icon: 'data:image/png;base64,AA' }];
    expect(parsePins(JSON.stringify(pins))).toEqual(pins);
  });

  it('empty, corrupt or non-array storage: no pins', () => {
    expect(parsePins(null)).toEqual([]);
    expect(parsePins('{oops')).toEqual([]);
    expect(parsePins('{"pkg":"a"}')).toEqual([]);
  });

  it('drops malformed entries, extra fields and duplicates; caps the count', () => {
    const raw = JSON.stringify([null, 5, { pkg: 'x' }, { pkg: 'a', cls: 'A', label: 7, evil: '<b>' }, { pkg: 'a', cls: 'A', label: 'dup' },
      ...['b', 'c', 'd', 'e', 'f', 'g'].map((p) => app(p))]);
    const pins = parsePins(raw);
    expect(pins[0]).toEqual({ pkg: 'a', cls: 'A', label: 'a' });
    expect(pins.map((p) => p.pkg)).toEqual(['a', 'b', 'c', 'd', 'e']);
    expect(pins.length).toBe(MAX_PINS);
  });
});

describe('addPin / removePin', () => {
  it('adds at the end, once', () => {
    const one = addPin([], app('a'))!;
    const two = addPin(one, app('b'))!;
    expect(two.map((p) => p.pkg)).toEqual(['a', 'b']);
    expect(addPin(two, app('a'))).toEqual(two);
    expect(isPinned(two, app('b'))).toBe(true);
  });

  it('refuses when full', () => {
    const full = ['a', 'b', 'c', 'd', 'e'].map((p) => app(p));
    expect(addPin(full, app('z'))).toBeNull();
    expect(addPin(full, app('c'))).toEqual(full); // already there: fine
  });

  it('matches package and activity', () => {
    const pins = [app('a', 'One')];
    expect(isPinned(pins, app('a', 'Two'))).toBe(false);
    expect(removePin(pins, app('a', 'Two'))).toEqual(pins);
    expect(removePin(pins, app('a', 'One'))).toEqual([]);
  });
});

describe('unpinnedRecents', () => {
  it('leaves out pinned apps, keeping the order', () => {
    const recents = [app('a'), app('b'), app('c')];
    expect(unpinnedRecents(recents, [app('b')]).map((r) => r.pkg)).toEqual(['a', 'c']);
    expect(unpinnedRecents(recents, [])).toEqual(recents);
  });
});

describe('reordering pins', () => {
  const p = (pkg: string) => ({ pkg, cls: 'M', label: pkg });
  const list = [p('a'), p('b'), p('c')];
  const pkgs = (l: { pkg: string }[]) => l.map((x) => x.pkg).join('');
  it('moves a pin', () => {
    expect(pkgs(movePin(list, 0, 2))).toBe('bca');
    expect(pkgs(movePin(list, 2, 0))).toBe('cab');
    expect(pkgs(movePin(list, 1, 1))).toBe('abc');
    expect(pkgs(movePin(list, 0, 99))).toBe('bca');
    expect(pkgs(movePin(list, 5, 0))).toBe('abc');
    expect(pkgs(list)).toBe('abc'); // not changed in place
  });
  it('drop index from the other pins', () => {
    expect(dropIndex([100, 200], 50)).toBe(0);
    expect(dropIndex([100, 200], 150)).toBe(1);
    expect(dropIndex([100, 200], 250)).toBe(2);
  });
});
