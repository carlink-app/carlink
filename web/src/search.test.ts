import { describe, expect, it } from 'vitest';
import { appsByLetter, filterApps, letterOf, letters } from './search';
import type { AppInfo } from './protocol';

const app = (label: string, pkg = `p.${label}`): AppInfo => ({ pkg, cls: 'Main', label });

describe('letterOf', () => {
  it('first letter, upper case, accents stripped', () => {
    expect(letterOf(app('spotify'))).toBe('S');
    expect(letterOf(app('Élan'))).toBe('E');
    expect(letterOf(app('Яндекс'))).toBe('Я');
  });

  it('digits, symbols and blank labels go under #', () => {
    expect(letterOf(app('7-Eleven'))).toBe('#');
    expect(letterOf(app('@home'))).toBe('#');
    expect(letterOf({ pkg: '9x', cls: 'M', label: '' })).toBe('#'); // falls back to the package name
    expect(letterOf({ pkg: 'com.x', cls: 'M', label: '' })).toBe('C');
  });
});

describe('letters', () => {
  it('only letters with apps: A-Z, then other scripts, then #', () => {
    expect(letters([app('Waze'), app('2GIS'), app('Яндекс'), app('maps'), app('Audible'), app('Music')])).toEqual(['A', 'M', 'W', 'Я', '#']);
    expect(letters([])).toEqual([]);
  });
});

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

describe('appsByLetter', () => {
  it('apps under a letter, across categories, sorted', () => {
    const list = [{ ...app('Spotify'), cat: 'music' }, app('Maps'), { ...app('signal'), cat: 'social' }, app('Settings')] as AppInfo[];
    expect(appsByLetter(list, 'S').map((a) => a.label)).toEqual(['Settings', 'signal', 'Spotify']);
    expect(appsByLetter(list, 'Q')).toEqual([]);
  });
});
