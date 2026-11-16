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

describe('parseMedia limits', () => {
  it('caps text, defaults missing fields, takes only real booleans', () => {
    const n = parseMedia({ pkg: 'p', title: 't'.repeat(500), app: 'a'.repeat(100), artist: 7, playing: 'true', prev: 1 })!;
    expect(n.title).toHaveLength(120);
    expect(n.app).toHaveLength(60);
    expect(n.artist).toBe('');
    expect(n.playing).toBe(false);
    expect(n.prev).toBe(false);
    expect(n.next).toBe(false);
    expect(n.art).toBeUndefined();
  });
  it('PNG art too; anything beyond base64 in the payload is refused', () => {
    expect(parseMedia({ ...msg, art: 'data:image/png;base64,AA==' })!.art).toBe('data:image/png;base64,AA==');
    expect(parseMedia({ ...msg, art: 'data:image/png;base64,AA")' })!.art).toBeUndefined(); // would break out of url("…")
    expect(parseMedia({ ...msg, art: '' })!.art).toBeUndefined();
    expect(parseMedia({ ...msg, art: 5 })!.art).toBeUndefined();
  });
  it('none only when exactly true', () => {
    expect(parseMedia({ ...msg, none: 'yes' })).not.toBeNull();
  });
  it('subtitle with neither part is empty', () => {
    expect(subtitle({ ...parseMedia(msg)!, artist: '', app: '' })).toBe('');
  });
});
