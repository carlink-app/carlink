import { describe, expect, it } from 'vitest';
import { toPane } from './touch';

describe('toPane', () => {
  const rect = { left: 96, top: 0, width: 912, height: 600 };
  it('maps 1:1 aspect exactly', () => {
    expect(toPane(96, 0, rect, 1824, 1200)).toEqual({ x: 0, y: 0 });
    expect(toPane(96 + 456, 300, rect, 1824, 1200)).toEqual({ x: 912, y: 600 });
  });
  it('accounts for letterboxing and clamps', () => {
    // 1920x1200 video in a 912x600 box: scale 0.475, height 570, 15px bars.
    expect(toPane(96, 15, rect, 1920, 1200)).toEqual({ x: 0, y: 0 });
    expect(toPane(96, 0, rect, 1920, 1200)).toEqual({ x: 0, y: 0 });
    expect(toPane(2000, 2000, rect, 1920, 1200)).toEqual({ x: 1919, y: 1199 });
  });
});

import { PointerIds } from './touch';

describe('PointerIds', () => {
  it('allocates 0..9, then null', () => {
    const p = new PointerIds();
    for (let i = 0; i < 10; i++) expect(p.alloc(100 + i)).toBe(i);
    expect(p.alloc(200)).toBeNull();
  });
  it('reuses the lowest free id', () => {
    const p = new PointerIds();
    [1, 2, 3, 4].forEach((pid) => p.alloc(pid)); // 0..3
    expect(p.release(3)).toBe(2);
    expect(p.release(1)).toBe(0);
    expect(p.get(2)).toBe(1);
    expect(p.alloc(9)).toBe(0);
    expect(p.alloc(8)).toBe(2);
    expect(p.release(77)).toBeUndefined();
  });
});
