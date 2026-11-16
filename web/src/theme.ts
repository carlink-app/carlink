// A colour to theme the now-playing card with, from cover art or the app icon.

export type Rgb = [number, number, number];

/**
 * The most vivid common colour in RGBA [pixels]: saturated, not too dark or
 * light pixels are averaged, weighted by saturation. Grey images: null.
 */
export function accentFrom(pixels: ArrayLike<number>): Rgb | null {
  let r = 0, g = 0, b = 0, w = 0;
  for (let i = 0; i + 3 < pixels.length; i += 4) {
    if (pixels[i + 3] < 128) continue; // transparent (icon corners)
    const pr = pixels[i], pg = pixels[i + 1], pb = pixels[i + 2];
    const max = Math.max(pr, pg, pb), min = Math.min(pr, pg, pb);
    if (max < 40 || min > 225) continue; // near black or white
    const sat = (max - min) / max;
    if (sat < 0.25) continue;
    r += pr * sat; g += pg * sat; b += pb * sat; w += sat;
  }
  if (w === 0) return null;
  return lift([r / w, g / w, b / w]);
}

/** Bright enough to read as a colour on the dark card (max channel at least 150). */
function lift([r, g, b]: Rgb): Rgb {
  const max = Math.max(r, g, b);
  const k = max < 150 ? 150 / max : 1;
  return [r, g, b].map((v) => Math.round(Math.min(255, v * k))) as Rgb;
}

/** Black or white text on [c]. */
export const textOn = ([r, g, b]: Rgb): string => (0.299 * r + 0.587 * g + 0.114 * b > 128 ? '#000' : '#fff');
