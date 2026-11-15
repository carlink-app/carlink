import { describe, expect, it } from 'vitest';
import { DrawGaps, STALL_MS } from './stats';

const FRAME_MS = 1000 / 60;

describe('DrawGaps', () => {
  it('first draw after creation or reset is reported, then not', () => {
    const g = new DrawGaps();
    expect(g.draw(1000, 0)).toBe(true);
    expect(g.draw(1016, 16_000)).toBe(false);
    g.reset();
    expect(g.draw(5000, 999_000)).toBe(true);
    expect(g.maxGap).toBeNull(); // no gap across a reset
  });

  it('normal 60 fps: no stalls, max gap one frame', () => {
    const g = new DrawGaps();
    for (let i = 0; i < 120; i++) g.draw(100 + i * FRAME_MS, i * FRAME_MS * 1000);
    expect(g.stalls).toBe(0);
    expect(g.maxGap).toBeCloseTo(FRAME_MS);
  });

  it('static screen: a big gap in both draw and phone time is no stall', () => {
    const g = new DrawGaps();
    g.draw(100, 0);
    g.draw(5100, 5_000_000); // nothing sent for 5 s
    g.draw(5130, 5_010_000); // a little jitter
    expect(g.stalls).toBe(0);
    expect(g.maxGap).toBe(5000);
  });

  it('a real freeze: frames drawn much later than they were made', () => {
    const g = new DrawGaps();
    g.draw(100, 0);
    g.draw(100 + FRAME_MS + 400, FRAME_MS * 1000);
    expect(g.stalls).toBe(1);
    expect(g.maxGap).toBeCloseTo(FRAME_MS + 400);
  });

  it('the threshold is strictly over STALL_MS', () => {
    const g = new DrawGaps();
    g.draw(100, 0);
    g.draw(100 + STALL_MS, 0);
    expect(g.stalls).toBe(0);
    g.draw(100 + 2 * STALL_MS + 1, 0);
    expect(g.stalls).toBe(1);
  });

  it('reset clears stalls and max gap', () => {
    const g = new DrawGaps();
    g.draw(100, 0);
    g.draw(1000, 0);
    expect(g.stalls).toBe(1);
    g.reset();
    expect(g.stalls).toBe(0);
    expect(g.maxGap).toBeNull();
    g.draw(2000, 0);
    g.draw(2000 + FRAME_MS, FRAME_MS * 1000);
    expect(g.stalls).toBe(0);
  });
});
