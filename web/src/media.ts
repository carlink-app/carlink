// Now playing on the phone (the `media` message, docs/PROTOCOL.md).

export interface NowPlaying {
  pkg: string;
  app: string;
  title: string;
  artist: string;
  playing: boolean;
  prev: boolean;
  next: boolean;
  art?: string;
}

const ART = /^data:image\/(jpeg|png);base64,[A-Za-z0-9+/=]+$/;
const str = (v: unknown, max: number) => (typeof v === 'string' ? v.slice(0, max) : '');

/** What's playing, from a `media` message; null when nothing is (or it's malformed). */
export function parseMedia(m: Record<string, unknown>): NowPlaying | null {
  if (m.none === true) return null;
  const pkg = str(m.pkg, 200);
  const title = str(m.title, 120);
  if (!pkg || !title) return null;
  const art = typeof m.art === 'string' && m.art.length <= 32 * 1024 && ART.test(m.art) ? m.art : undefined;
  return {
    pkg, title, art,
    app: str(m.app, 60),
    artist: str(m.artist, 120),
    playing: m.playing === true,
    prev: m.prev === true,
    next: m.next === true,
  };
}

/** "Artist · App", without empty parts. */
export const subtitle = (n: NowPlaying) => [n.artist, n.app].filter(Boolean).join(' · ');
