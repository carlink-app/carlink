import { beforeEach, describe, expect, it } from 'vitest';
import { GAP_MAX_BUFFERED, GAP_TIMEOUT_MS, KEYFRAME_RETRY_MS, Reorderer } from './reorder';

interface F { key: boolean; n: number }
const K = (n: number): F => ({ key: true, n });
const D = (n: number): F => ({ key: false, n });

let t = 0;
let out: number[] = [];
let requests = 0;
let r: Reorderer<F>;
const make = () => new Reorderer<F>({
  now: () => t,
  emit: (seq) => { out.push(seq); },
  requestKeyframe: () => { requests++; },
});

beforeEach(() => { t = 1000; out = []; requests = 0; r = make(); });

describe('Reorderer', () => {
  it('buffers deltas that arrive before their keyframe, then decodes from the key', () => {
    r.push(1, D(1));
    r.push(2, D(2));
    expect(out).toEqual([]);
    r.push(0, K(0)); // big IDR finishes last
    expect(out).toEqual([0, 1, 2]);
    expect(r.syncing).toBe(false);
  });

  it('discards frames older than the keyframe it syncs to', () => {
    r.push(3, D(3));
    r.push(6, D(6));
    r.push(5, K(5));
    expect(out).toEqual([5, 6]);
    expect(r.dropped).toBe(1);
    expect(r.buffered).toBe(0);
  });

  it('reorders out-of-order frames', () => {
    r.push(0, K(0));
    r.push(2, D(2));
    r.push(3, D(3));
    expect(out).toEqual([0]);
    r.push(1, D(1));
    expect(out).toEqual([0, 1, 2, 3]);
  });

  it('drops late frames and duplicates', () => {
    r.push(0, K(0));
    r.push(1, D(1));
    r.push(1, D(1)); // duplicate of an emitted frame: late
    r.push(3, D(3));
    r.push(3, D(3)); // duplicate of a buffered frame
    r.push(0, K(0)); // late
    expect(out).toEqual([0, 1]);
    expect(r.dropped).toBe(3);
  });

  it('declares a gap only after the 150 ms timeout', () => {
    r.push(0, K(0));
    r.push(2, D(2));
    requests = 0;
    t += GAP_TIMEOUT_MS;
    r.tick();
    expect(r.syncing).toBe(false); // exactly at the boundary: still waiting
    t += 1;
    r.tick();
    expect(r.syncing).toBe(true);
    expect(requests).toBe(1);
    r.push(3, K(3));
    expect(out).toEqual([0, 3]);
    expect(r.dropped).toBe(1); // frame 2
  });

  it('forces a gap when more than 30 frames wait', () => {
    r.push(0, K(0));
    for (let s = 2; s < 2 + GAP_MAX_BUFFERED; s++) r.push(s, D(s));
    expect(r.syncing).toBe(false);
    r.push(2 + GAP_MAX_BUFFERED, D(2 + GAP_MAX_BUFFERED));
    expect(r.syncing).toBe(true);
    expect(requests).toBe(1);
  });

  it('on a gap, jumps to the lowest buffered keyframe', () => {
    r.push(0, K(0));
    r.push(2, D(2));
    r.push(7, K(7));
    r.push(5, K(5));
    r.push(6, D(6));
    t += GAP_TIMEOUT_MS + 1;
    r.tick();
    expect(out).toEqual([0, 5, 6, 7]);
    expect(requests).toBe(0);
  });

  it('a lost stream triggers the gap immediately', () => {
    r.push(0, K(0));
    r.push(2, D(2));
    r.lost(1);
    expect(r.syncing).toBe(true);
    expect(requests).toBe(1);
  });

  it('keeps buffered deltas through a gap: their keyframe may still be in flight', () => {
    r.push(0, K(0));
    for (let s = 11; s <= 11 + GAP_MAX_BUFFERED; s++) r.push(s, D(s)); // 1..10 missing, then 11.. waiting
    expect(r.syncing).toBe(true);
    r.push(10, K(10));
    expect(out.slice(0, 3)).toEqual([0, 10, 11]);
    expect(out.at(-1)).toBe(11 + GAP_MAX_BUFFERED);
  });

  it('wraps around 0xFFFFFFFF', () => {
    r.push(0xfffffffe, K(0));
    r.push(0, D(2));
    r.push(1, D(3));
    expect(out).toEqual([0xfffffffe]);
    r.push(0xffffffff, D(1));
    expect(out).toEqual([0xfffffffe, 0xffffffff, 0, 1]);
    r.push(0xfffffffd, D(9)); // older than the start: late
    expect(r.dropped).toBe(1);
  });

  it('syncing across the wrap discards older frames only', () => {
    r.push(0xffffffff, D(0));
    r.push(1, D(2));
    r.push(0, K(1));
    expect(out).toEqual([0, 1]);
  });

  it('rate-limits keyframe re-requests while syncing', () => {
    r.tick();
    expect(requests).toBe(1);
    t += KEYFRAME_RETRY_MS - 1;
    r.tick();
    expect(requests).toBe(1);
    t += 1;
    r.tick();
    expect(requests).toBe(2);
    r.resync(); // forced: not rate-limited
    expect(requests).toBe(3);
  });

  it('resync(true) clears the buffer (encoder recreated, seq may restart)', () => {
    r.push(0, K(0));
    r.push(5000, D(5000));
    r.resync(true);
    r.push(0, K(0));
    r.push(1, D(1));
    expect(out).toEqual([0, 0, 1]);
    expect(r.buffered).toBe(0);
  });
});
