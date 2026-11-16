import { describe, expect, it } from 'vitest';
import { filterApps, fold } from './search';
import type { AppInfo } from './protocol';

const app = (label: string, pkg = `p.${label}`): AppInfo => ({ pkg, cls: 'Main', label });

describe('filterApps', () => {
  const list = [app('YouTube Music'), app('Spotify'), app('Musée'), app('Maps')];

  it('substring of the label, case and accents ignored, sorted', () => {
    expect(filterApps(list, 'MUS').map((a) => a.label)).toEqual(['Musée', 'YouTube Music']);
    expect(filterApps(list, 'musee').map((a) => a.label)).toEqual(['Musée']);
    expect(filterApps(list, '  spot ').map((a) => a.label)).toEqual(['Spotify']);
  });

  it('blank query or no match: nothing', () => {
    expect(filterApps(list, '   ')).toEqual([]);
    expect(filterApps(list, 'zzz')).toEqual([]);
  });
});

describe('search edge cases', () => {
  it('fold strips accents and case', () => {
    expect(fold('Ça Été')).toBe('ca ete');
    expect(fold('')).toBe('');
  });
  it('a missing label falls back to the package name', () => {
    const noLabel = { pkg: 'com.maps', cls: 'M' } as unknown as AppInfo;
    expect(filterApps([noLabel], 'maps')).toEqual([noLabel]);
  });
  it('the query is matched against the label, not the package', () => {
    expect(filterApps([app('Music', 'com.spotify')], 'spotify')).toEqual([]);
  });
  it('accented query matches plain label', () => {
    expect(filterApps([app('Cafe')], 'CAFÉ').map((a) => a.label)).toEqual(['Cafe']);
  });
});
