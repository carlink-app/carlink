import { describe, expect, it } from 'vitest';
import { accentFrom, textOn } from './theme';

const px = (...rgba: number[][]) => rgba.flat();

describe('accentFrom', () => {
  it('picks the vivid colour, ignoring black, white, grey and transparent', () => {
    const c = accentFrom(px([30, 215, 96, 255], [0, 0, 0, 255], [255, 255, 255, 255], [128, 128, 128, 255], [255, 0, 0, 0]))!;
    expect(c).toEqual([30, 215, 96]);
  });
  it('the strongest hue wins instead of a grey mix of all', () => {
    const c = accentFrom(px([240, 200, 20, 255], [240, 200, 20, 255], [240, 200, 20, 255], [40, 60, 200, 255]))!;
    expect(c).toEqual([240, 200, 20]);
  });
  it('grey images: none', () => {
    expect(accentFrom(px([128, 128, 128, 255], [20, 20, 20, 255]))).toBeNull();
    expect(accentFrom([])).toBeNull();
  });
  it('dark colours are lifted to read on the dark card', () => {
    const c = accentFrom(px([60, 10, 10, 255]))!;
    expect(Math.max(...c)).toBe(150);
  });
});

describe('textOn', () => {
  it('black on light, white on dark', () => {
    expect(textOn([30, 215, 96])).toBe('#000');
    expect(textOn([120, 20, 160])).toBe('#fff');
  });
});
