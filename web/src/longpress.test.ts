import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { attachLongPress, LONG_PRESS_MS } from './longpress';

const ev = (type: string, x = 0, y = 0, extra: object = {}) =>
  Object.assign(new Event(type, { cancelable: true }), { clientX: x, clientY: y, pointerId: 1, isPrimary: true, ...extra });

describe('attachLongPress', () => {
  let el: EventTarget;
  let onLong: ReturnType<typeof vi.fn<() => void>>;
  let onClick: ReturnType<typeof vi.fn<(e: Event) => void>>;
  beforeEach(() => {
    vi.useFakeTimers();
    el = new EventTarget();
    onLong = vi.fn();
    onClick = vi.fn();
    attachLongPress(el, onLong);
    el.addEventListener('click', onClick);
  });
  afterEach(() => vi.useRealTimers());

  it('a tap is a tap', () => {
    el.dispatchEvent(ev('pointerdown'));
    vi.advanceTimersByTime(100);
    el.dispatchEvent(ev('pointerup'));
    el.dispatchEvent(ev('click'));
    vi.advanceTimersByTime(LONG_PRESS_MS);
    expect(onLong).not.toHaveBeenCalled();
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('holding still fires once, and the click of the lift is swallowed', () => {
    el.dispatchEvent(ev('pointerdown', 10, 10));
    el.dispatchEvent(ev('pointermove', 14, 12)); // a little wobble is fine
    vi.advanceTimersByTime(LONG_PRESS_MS);
    expect(onLong).toHaveBeenCalledTimes(1);
    el.dispatchEvent(ev('pointerup'));
    el.dispatchEvent(ev('click'));
    expect(onClick).not.toHaveBeenCalled();
    // The next tap works again.
    el.dispatchEvent(ev('pointerdown'));
    el.dispatchEvent(ev('pointerup'));
    el.dispatchEvent(ev('click'));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('moving (a scroll) cancels', () => {
    el.dispatchEvent(ev('pointerdown', 10, 10));
    el.dispatchEvent(ev('pointermove', 10, 40));
    vi.advanceTimersByTime(LONG_PRESS_MS * 2);
    expect(onLong).not.toHaveBeenCalled();
  });

  it('pointercancel (the browser took over for scrolling) cancels', () => {
    el.dispatchEvent(ev('pointerdown'));
    el.dispatchEvent(ev('pointercancel'));
    vi.advanceTimersByTime(LONG_PRESS_MS * 2);
    expect(onLong).not.toHaveBeenCalled();
  });

  it('a second finger cancels', () => {
    el.dispatchEvent(ev('pointerdown'));
    el.dispatchEvent(ev('pointerdown', 50, 50, { pointerId: 2, isPrimary: false }));
    vi.advanceTimersByTime(LONG_PRESS_MS * 2);
    expect(onLong).not.toHaveBeenCalled();
  });
});
