// A colour to theme the now-playing card with, from cover art or the app icon.

export type Rgb = [number, number, number];

/**
 * The strongest colour in RGBA [pixels]: saturated pixels (not near black or
 * white, not transparent) are grouped by hue; the group with the most
 * saturation wins and its pixels are averaged. Averaging all colours instead
 * would mix them to grey. Grey images: null.
 */
export function accentFrom(pixels: ArrayLike<number>): Rgb | null {
  const groups = Array.from({ length: HUES }, () => ({ r: 0, g: 0, b: 0, w: 0 }));
  for (let i = 0; i + 3 < pixels.length; i += 4) {
    if (pixels[i + 3] < 128) continue; // transparent (icon corners)
    const pr = pixels[i], pg = pixels[i + 1], pb = pixels[i + 2];
    const max = Math.max(pr, pg, pb), min = Math.min(pr, pg, pb);
    if (max < 40 || min > 225) continue; // near black or white
    const sat = (max - min) / max;
    if (sat < 0.25) continue;
    const grp = groups[Math.floor((hue(pr, pg, pb, max, min) / 360) * HUES) % HUES];
    const w = sat * sat; // vivid pixels count more
    grp.r += pr * w; grp.g += pg * w; grp.b += pb * w; grp.w += w;
  }
  const best = groups.reduce((a, b) => (b.w > a.w ? b : a));
  if (best.w === 0) return null;
  return lift([best.r / best.w, best.g / best.w, best.b / best.w]);
}

const HUES = 12;

function hue(r: number, g: number, b: number, max: number, min: number): number {
  const d = max - min;
  const h = max === r ? ((g - b) / d) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return (h * 60 + 360) % 360;
}

/** Bright enough to read as a colour on the dark card (max channel at least 150). */
function lift([r, g, b]: Rgb): Rgb {
  const max = Math.max(r, g, b);
  const k = max < 150 ? 150 / max : 1;
  return [r, g, b].map((v) => Math.round(Math.min(255, v * k))) as Rgb;
}

/** Black or white text on [c]. */
export const textOn = ([r, g, b]: Rgb): string => (0.299 * r + 0.587 * g + 0.114 * b > 128 ? '#000' : '#fff');
