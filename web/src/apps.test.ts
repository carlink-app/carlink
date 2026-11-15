import { describe, expect, it } from 'vitest';
import { afterClose, groupApps } from './apps';
import type { AppInfo } from './protocol';

const app = (pkg: string, label: string, cat?: string): AppInfo => ({ pkg, cls: `${pkg}.Main`, label, cat } as AppInfo);

describe('groupApps', () => {
  it('orders groups nav, music, social, work, other and sorts by label', () => {
    const g = groupApps([
      app('x.notes', 'Notes', 'work'), app('com.waze', 'Waze', 'nav'), app('x.game', 'Game'),
      app('com.spotify.music', 'Spotify', 'music'), app('maps', 'Maps', 'nav'), app('tg', 'Telegram', 'social'),
    ]);
    expect(g.map((x) => x.title)).toEqual(['Navigation', 'Music', 'Social', 'Work', 'Other']);
    expect(g[0].apps.map((a) => a.label)).toEqual(['Maps', 'Waze']);
  });

  it('skips empty groups and puts unknown categories in Other', () => {
    const g = groupApps([app('a', 'A', 'weird'), app('b', 'B', 'nav')]);
    expect(g.map((x) => x.cat)).toEqual(['nav', 'other']);
    expect(g[1].apps[0].pkg).toBe('a');
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
