import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MoveThrottle, toPane } from './touch';

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

describe('MoveThrottle', () => {
  let t = 0;
  let sent: [number, number, number][] = [];
  const make = () => new MoveThrottle((id, x, y) => { sent.push([id, x, y]); }, () => t, 8);
  beforeEach(() => { t = 0; sent = []; vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  it('caps a 1000 Hz stream to ~125 Hz and keeps the newest position', () => {
    const m = make();
    for (let i = 0; i < 1000; i++) { t = i; vi.advanceTimersByTime(1); m.move(0, i, i); }
    expect(sent.length).toBeGreaterThan(100);
    expect(sent.length).toBeLessThanOrEqual(126);
    m.end(0);
    expect(sent.at(-1)).toEqual([0, 999, 999]);
  });

  it('flushes the pending move before the pointer ends', () => {
    const m = make();
    m.move(0, 1, 1); // sent at once
    t = 2; m.move(0, 5, 5); // pending
    expect(sent).toEqual([[0, 1, 1]]);
    m.end(0);
    expect(sent).toEqual([[0, 1, 1], [0, 5, 5]]);
    vi.advanceTimersByTime(20);
    expect(sent.length).toBe(2); // the timer was cancelled
  });

  it('sends the pending move when its timer fires, with the newest position', () => {
    const m = make();
    m.move(0, 1, 1); // t=0: sent
    t = 3; m.move(0, 2, 2); // pending, due at t=8
    t = 5; m.move(0, 3, 3); // replaces the pending position, same timer
    t = 7; vi.advanceTimersByTime(4);
    expect(sent).toEqual([[0, 1, 1]]);
    t = 8; vi.advanceTimersByTime(1);
    expect(sent).toEqual([[0, 1, 1], [0, 3, 3]]);
    t = 9; m.move(0, 4, 4); // only 1 ms after that send: pending again
    expect(sent.length).toBe(2);
  });

  it('a move after the interval goes out at once', () => {
    const m = make();
    m.move(0, 1, 1);
    t = 8; m.move(0, 2, 2);
    expect(sent).toEqual([[0, 1, 1], [0, 2, 2]]);
  });

  it('flush and end with nothing pending send nothing', () => {
    const m = make();
    m.flush(0);
    m.end(3);
    expect(sent).toEqual([]);
  });

  it('a new stroke after end is not held back by the old one', () => {
    const m = make();
    m.move(0, 1, 1);
    m.end(0);
    t = 1; m.move(0, 9, 9); // same id reused right away (e.g. a quick second tap)
    expect(sent).toEqual([[0, 1, 1], [0, 9, 9]]);
  });

  it('ending one pointer leaves the other\'s pending move alone', () => {
    const m = make();
    m.move(0, 1, 1); m.move(1, 1, 1);
    t = 2; m.move(0, 2, 2); m.move(1, 2, 2);
    m.end(0);
    expect(sent).toEqual([[0, 1, 1], [1, 1, 1], [0, 2, 2]]);
    t = 8; vi.advanceTimersByTime(8);
    expect(sent.at(-1)).toEqual([1, 2, 2]);
  });

  it('throttles each pointer separately', () => {
    const m = make();
    m.move(0, 1, 1);
    m.move(1, 2, 2);
    expect(sent).toEqual([[0, 1, 1], [1, 2, 2]]);
  });
});
