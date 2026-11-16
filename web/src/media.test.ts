import { describe, expect, it } from 'vitest';
import { parseMedia, subtitle } from './media';

const msg = { t: 'media', pkg: 'com.spotify.music', app: 'Spotify', title: 'Song', artist: 'Band', playing: true, next: true };

describe('media', () => {
  it('reads what plays', () => {
    const n = parseMedia(msg)!;
    expect(n).toMatchObject({ title: 'Song', artist: 'Band', playing: true, next: true, prev: false });
    expect(subtitle(n)).toBe('Band · Spotify');
    expect(subtitle({ ...n, artist: '' })).toBe('Spotify');
  });
  it('nothing playing', () => {
    expect(parseMedia({ t: 'media', none: true })).toBeNull();
    expect(parseMedia({ ...msg, title: '' })).toBeNull();
    expect(parseMedia({ ...msg, pkg: 5 })).toBeNull();
  });
  it('only image data URLs as art', () => {
    expect(parseMedia({ ...msg, art: 'data:image/jpeg;base64,AAAA' })!.art).toBe('data:image/jpeg;base64,AAAA');
    expect(parseMedia({ ...msg, art: 'javascript:alert(1)' })!.art).toBeUndefined();
    expect(parseMedia({ ...msg, art: 'data:image/svg+xml;base64,AAAA' })!.art).toBeUndefined();
    expect(parseMedia({ ...msg, art: `data:image/jpeg;base64,${'A'.repeat(40000)}` })!.art).toBeUndefined();
  });
});
