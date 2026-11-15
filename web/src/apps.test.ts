import { describe, expect, it } from 'vitest';
import { afterClose, groupApps } from './apps';
import type { AppInfo } from './protocol';

const app = (pkg: string, label: string, cat?: string): AppInfo => ({ pkg, cls: `${pkg}.Main`, label, cat } as AppInfo);

describe('groupApps', () => {
  it('orders groups nav, energy, music, social, work, other and sorts by label', () => {
    const g = groupApps([
      app('x.notes', 'Notes', 'work'), app('com.waze', 'Waze', 'nav'), app('x.game', 'Game'), app('ps', 'PlugShare', 'energy'),
      app('com.spotify.music', 'Spotify', 'music'), app('maps', 'Maps', 'nav'), app('tg', 'Telegram', 'social'),
    ]);
    expect(g.map((x) => x.title)).toEqual(['Navigation', 'Charging & fuel', 'Music', 'Social', 'Work', 'Other']);
    expect(g[0].apps.map((a) => a.label)).toEqual(['Maps', 'Waze']);
  });

  it('skips empty groups and puts unknown categories in Other', () => {
    const g = groupApps([app('a', 'A', 'weird'), app('b', 'B', 'nav')]);
    expect(g.map((x) => x.cat)).toEqual(['nav', 'other']);
    expect(g[1].apps[0].pkg).toBe('a');
  });
});

describe('groupApps edge cases', () => {
  it('empty list: no groups', () => expect(groupApps([])).toEqual([]));

  it('missing labels sort as empty and do not throw; the input is not reordered', () => {
    const input = [app('z', 'Zed', 'nav'), { pkg: 'n', cls: 'n.Main', cat: 'nav' } as AppInfo, app('a', 'Alpha', 'nav')];
    const g = groupApps(input);
    expect(g[0].apps.map((a) => a.pkg)).toEqual(['n', 'a', 'z']);
    expect(input.map((a) => a.pkg)).toEqual(['z', 'n', 'a']);
  });

  it('every app lands in exactly one group', () => {
    const input = [app('a', 'A', 'nav'), app('b', 'B'), app('c', 'C', 'music'), app('d', 'D', ''), app('e', 'E', 'other')];
    const g = groupApps(input);
    expect(g.flatMap((x) => x.apps).map((a) => a.pkg).sort()).toEqual(['a', 'b', 'c', 'd', 'e']);
    expect(g.find((x) => x.cat === 'other')?.apps.map((a) => a.pkg)).toEqual(['b', 'd', 'e']);
  });

  it('inherited object keys are not categories', () => {
    const g = groupApps([app('a', 'A', 'constructor'), app('b', 'B', '__proto__')]);
    expect(g.map((x) => x.cat)).toEqual(['other']);
  });
});

describe('afterClose', () => {
  const rail = [app('maps', 'Maps'), app('waze', 'Waze'), app('tg', 'Telegram')];

  it('closing the front app moves to the next one on the rail', () => {
    const r = afterClose(rail, 'maps', 'maps');
    expect(r.recents.map((a) => a.pkg)).toEqual(['waze', 'tg']);
    expect(r).toMatchObject({ wasCurrent: true, next: { pkg: 'waze' } });
  });

  it('closing the last app leaves nothing: launcher', () => {
    const r = afterClose([app('maps', 'Maps')], 'maps', 'maps');
    expect(r).toEqual({ recents: [], wasCurrent: true, next: null });
  });

  it('closing a background app keeps the front one', () => {
    const r = afterClose(rail, 'maps', 'tg');
    expect(r.recents.map((a) => a.pkg)).toEqual(['maps', 'waze']);
    expect(r).toMatchObject({ wasCurrent: false, next: null });
  });
});

describe('afterClose edge cases', () => {
  const rail = [app('maps', 'Maps'), app('waze', 'Waze')];

  it('nothing in front (launcher showing): rail shrinks, front unchanged', () => {
    expect(afterClose(rail, null, 'maps')).toEqual({ recents: [rail[1]], wasCurrent: false, next: null });
  });

  it('closing an app not on the rail changes nothing but still reports the front', () => {
    const r = afterClose(rail, 'waze', 'spotify');
    expect(r.recents).toEqual(rail);
    expect(r).toMatchObject({ wasCurrent: false, next: null });
  });

  it('front app that was not on the rail: next is the rail head', () => {
    const r = afterClose(rail, 'tg', 'tg');
    expect(r).toMatchObject({ wasCurrent: true, next: { pkg: 'maps' } });
  });

  it('drops every rail entry of the package (several activities) and does not mutate the input', () => {
    const multi = [app('maps', 'Maps'), { ...app('maps', 'Maps'), cls: 'maps.Other' }, app('waze', 'Waze')];
    const r = afterClose(multi, 'maps', 'maps');
    expect(r.recents.map((a) => a.pkg)).toEqual(['waze']);
    expect(r.next?.pkg).toBe('waze');
    expect(multi.length).toBe(3);
  });
});
