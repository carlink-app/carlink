import { describe, expect, it } from 'vitest';
import { afterClose, groupApps, refreshRecents } from './apps';
import type { AppInfo } from './protocol';

const app = (pkg: string, label: string, cat?: string): AppInfo => ({ pkg, cls: `${pkg}.Main`, label, cat } as AppInfo);

describe('groupApps', () => {
  it('orders groups nav, energy, music, social, work, other and sorts by label', () => {
    const g = groupApps([
      app('x.notes', 'Notes', 'work'), app('com.waze', 'Waze', 'nav'), app('x.game', 'Game'), app('ps', 'PlugShare', 'energy'), app('nf', 'Netflix', 'video'),
      app('com.spotify.music', 'Spotify', 'music'), app('maps', 'Maps', 'nav'), app('tg', 'Telegram', 'social'),
    ]);
    expect(g.map((x) => x.title)).toEqual(['Navigation', 'Charging & fuel', 'Music', 'Video', 'Social', 'Work', 'Other']);
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
  const sidebar = [app('maps', 'Maps'), app('waze', 'Waze'), app('tg', 'Telegram')];

  it('closing the front app moves to the next one on the sidebar', () => {
    const r = afterClose(sidebar, 'maps', 'maps');
    expect(r.recents.map((a) => a.pkg)).toEqual(['waze', 'tg']);
    expect(r).toMatchObject({ wasCurrent: true, next: { pkg: 'waze' } });
  });

  it('closing the last app leaves nothing: launcher', () => {
    const r = afterClose([app('maps', 'Maps')], 'maps', 'maps');
    expect(r).toEqual({ recents: [], wasCurrent: true, next: null });
  });

  it('closing a background app keeps the front one', () => {
    const r = afterClose(sidebar, 'maps', 'tg');
    expect(r.recents.map((a) => a.pkg)).toEqual(['maps', 'waze']);
    expect(r).toMatchObject({ wasCurrent: false, next: null });
  });
});

describe('afterClose edge cases', () => {
  const sidebar = [app('maps', 'Maps'), app('waze', 'Waze')];

  it('nothing in front (launcher showing): sidebar shrinks, front unchanged', () => {
    expect(afterClose(sidebar, null, 'maps')).toEqual({ recents: [sidebar[1]], wasCurrent: false, next: null });
  });

  it('closing an app not on the sidebar changes nothing but still reports the front', () => {
    const r = afterClose(sidebar, 'waze', 'spotify');
    expect(r.recents).toEqual(sidebar);
    expect(r).toMatchObject({ wasCurrent: false, next: null });
  });

  it('front app that was not on the sidebar: next is the sidebar head', () => {
    const r = afterClose(sidebar, 'tg', 'tg');
    expect(r).toMatchObject({ wasCurrent: true, next: { pkg: 'maps' } });
  });

  it('drops every sidebar entry of the package (several activities) and does not mutate the input', () => {
    const multi = [app('maps', 'Maps'), { ...app('maps', 'Maps'), cls: 'maps.Other' }, app('waze', 'Waze')];
    const r = afterClose(multi, 'maps', 'maps');
    expect(r.recents.map((a) => a.pkg)).toEqual(['waze']);
    expect(r.next?.pkg).toBe('waze');
    expect(multi.length).toBe(3);
  });
});

describe('refreshRecents', () => {
  const PNG = 'data:image/png;base64,AAAA';
  const OLD = 'data:image/png;base64,OLD';
  const r = (pkg: string, extra: Partial<AppInfo> = {}): AppInfo => ({ pkg, cls: `${pkg}.Main`, label: `old ${pkg}`, ...extra });

  it("takes the phone's label and icon", () => {
    const out = refreshRecents([r('a', { icon: OLD })], [{ ...r('a'), label: 'A', icon: PNG }]);
    expect(out).toEqual([{ ...r('a'), label: 'A', icon: PNG }]);
  });
  it("keeps the stored icon until the phone's arrives, with the phone's label", () => {
    const phone = { ...r('a'), label: 'A', ih: 'h1' };
    const out = refreshRecents([r('a', { icon: OLD })], [phone]);
    expect(out).toEqual([{ ...phone, icon: OLD }]);
    expect(phone.icon).toBeUndefined(); // the phone's list isn't changed
  });
  it('no stored icon and none from the phone: the phone entry as is', () => {
    const phone = { ...r('a'), label: 'A' };
    expect(refreshRecents([r('a')], [phone])[0]).toBe(phone);
  });
  it('recents not on the phone (or another activity) are kept unchanged, order kept', () => {
    const b = r('b', { icon: OLD });
    const otherCls = r('a', { cls: 'a.Other' });
    const out = refreshRecents([b, otherCls], [{ ...r('a'), icon: PNG }]);
    expect(out[0]).toBe(b);
    expect(out[1]).toBe(otherCls);
  });
  it('empty inputs', () => {
    expect(refreshRecents([], [r('a')])).toEqual([]);
    expect(refreshRecents([r('a')], [])).toEqual([r('a')]);
  });
});
