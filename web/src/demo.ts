// ?demo=1: no network. Feeds the bundled clip through the same worker path as
// network frames (24-byte header + access unit), at 60 fps, looping.

import { codecString, splitAccessUnits } from './h26x';
import { FLAG_KEY, HEADER_LEN, KIND_VIDEO, writeHeader, type AppInfo, type PhoneMsg } from './protocol';

export const DEMO_W = 1920;
export const DEMO_H = 1200;
const FRAME_US = 16667;

/** Shown when the clip isn't there: it's gitignored, so e.g. GitHub Pages doesn't have it. */
export const NO_CLIP = 'Demo video not available on this site (demo/clip.h264 isn\'t deployed). Apps and banners still work.';

/**
 * Why the fetched clip can't be played, or null if it looks like Annex B
 * H.264 starting with a keyframe. A missing file often comes back as an HTML
 * page (404 page or an SPA fallback with status 200), not as an error.
 */
export function clipProblem(status: number, contentType: string | null, bytes: Uint8Array | null): string | null {
  if (status === 404 || /text\/html/i.test(contentType ?? '')) return NO_CLIP;
  if (status < 200 || status > 299) return `Demo video: HTTP ${status}`;
  if (!bytes || !bytes.length) return NO_CLIP;
  const aus = splitAccessUnits(bytes, 'h264');
  if (!aus.length || !codecString(aus[0], 'h264')) return 'Demo video: demo/clip.h264 is not an Annex B H.264 clip';
  return null;
}

/** Splits the clip into access units, or reports (once) why it can't be played. */
async function loadClip(): Promise<Uint8Array[] | string> {
  let res: Response;
  try {
    res = await fetch(new URL('demo/clip.h264', document.baseURI));
  } catch {
    return NO_CLIP; // offline, or the service worker had no copy
  }
  const ok = res.ok && !/text\/html/i.test(res.headers.get('content-type') ?? '');
  const bytes = ok ? new Uint8Array(await res.arrayBuffer()) : null;
  return clipProblem(res.status, res.headers.get('content-type'), bytes) ?? splitAccessUnits(bytes!, 'h264');
}

export async function startDemo(onFrame: (buf: ArrayBuffer) => void, onError: (msg: string) => void): Promise<void> {
  let aus: Uint8Array[];
  try {
    const r = await loadClip();
    if (typeof r === 'string') { onError(r); return; }
    aus = r;
  } catch (e) {
    onError(`Demo video: ${(e as Error).message}`);
    return;
  }
  const t0 = performance.now();
  let seq = 0;
  const feed = () => {
    const now = performance.now();
    // Catch up if the timer was late, like a live stream would.
    while (t0 + (seq * FRAME_US) / 1000 <= now) {
      const i = seq % aus.length;
      const au = aus[i];
      const key = i === 0; // the clip's only keyframe
      const out = new Uint8Array(HEADER_LEN + au.length);
      writeHeader({ kind: KIND_VIDEO, flags: key ? FLAG_KEY : 0, pane: 0, seq, ptsUs: seq * FRAME_US,
        sendUs: Math.round((now - t0) * 1000) }, out);
      out.set(au, HEADER_LEN);
      onFrame(out.buffer);
      seq++;
    }
    setTimeout(feed, Math.max(0, t0 + (seq * FRAME_US) / 1000 - performance.now()));
  };
  feed();
}

const DEMO_APPS: [string, string][] = [
  ['Maps', '#1a73e8'], ['Spotify', '#1db954'], ['YouTube Music', '#ff0000'], ['Waze', '#33ccff'],
  ['Messages', '#4285f4'], ['Phone', '#34a853'], ['Podcasts', '#8e24aa'], ['Audible', '#f29a33'],
  ['Calendar', '#1967d2'], ['Weather', '#fbbc04'], ['WhatsApp', '#25d366'], ['Pocket Casts', '#f43e37'],
  ['Settings', '#5f6368'], ['Chrome', '#0f9d58'], ['Photos', '#ea4335'], ['Clock', '#3c4043'],
];

/** A PNG data URL (the only icon format the page accepts): coloured circle + letter. */
function icon(label: string, color: string): string {
  const c = document.createElement('canvas');
  c.width = c.height = 96;
  const g = c.getContext('2d')!;
  g.fillStyle = color;
  g.beginPath();
  g.arc(48, 48, 46, 0, Math.PI * 2);
  g.fill();
  g.fillStyle = '#fff';
  g.font = '700 42px sans-serif';
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  g.fillText(label[0], 48, 50);
  return c.toDataURL('image/png');
}

export const demoApps = (): AppInfo[] => DEMO_APPS.map(([label, color]) => ({
  pkg: `demo.${label.toLowerCase().replace(/\W/g, '')}`, cls: 'MainActivity', label, icon: icon(label, color),
}));

/**
 * Fake phone notifications as [delay ms, message]: exercises replace-in-place,
 * the cap of 3, a call that stays until notif-gone, clamping, a hostile title
 * and a rejected icon.
 */
export function demoNotifs(): [number, PhoneMsg][] {
  const wa = icon('WhatsApp', '#25d366');
  return [
    [3000, { t: 'notif', id: 'demo-wa', pkg: 'com.whatsapp', app: 'WhatsApp', title: 'Alice', text: 'On my way', icon: wa, kind: 'msg' }],
    [4500, { t: 'notif', id: 'demo-maps', pkg: 'com.google.android.apps.maps', app: 'Maps', title: 'Turn left in 300 m',
      text: 'Onto Main Street', icon: icon('Maps', '#1a73e8'), kind: 'nav' }],
    [6000, { t: 'notif', id: 'demo-call', pkg: 'com.google.android.dialer', app: 'Phone', title: 'Bob',
      text: 'Incoming call', icon: icon('Phone', '#34a853'), kind: 'call' }],
    [8000, { t: 'notif', id: 'demo-wa', pkg: 'com.whatsapp', app: 'WhatsApp', title: 'Alice (2 messages)',
      text: 'On my way. Traffic is terrible on the bridge, so I will probably be about twenty minutes late — '
        + 'start without me if you need to, and order me whatever you are having. This text is long on purpose '
        + 'to show that banners clamp the message to two lines.', icon: wa, kind: 'msg' }],
    [11000, { t: 'notif', id: 'demo-cal', pkg: 'com.google.android.calendar', app: 'Calendar', title: '<b>Not bold</b> <img src=x>',
      text: 'Markup shows as text; the javascript: icon is rejected (letter fallback).', icon: 'javascript:alert(1)', kind: 'other' }],
    [25000, { t: 'notif-gone', id: 'demo-call' }],
  ];
}
