import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { attachLongPress, LONG_PRESS_MS, LONG_PRESS_SLOP } from './longpress';

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

describe('attachLongPress details', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  const setup = (ms?: number) => {
    const el = new EventTarget();
    const onLong = vi.fn();
    attachLongPress(el, onLong, ms);
    return { el, onLong };
  };

  it('movement within the slop still fires; just past it cancels', () => {
    const a = setup();
    a.el.dispatchEvent(ev('pointerdown', 0, 0));
    a.el.dispatchEvent(ev('pointermove', LONG_PRESS_SLOP, 0));
    vi.advanceTimersByTime(LONG_PRESS_MS);
    expect(a.onLong).toHaveBeenCalledTimes(1);
    const b = setup();
    b.el.dispatchEvent(ev('pointerdown', 0, 0));
    b.el.dispatchEvent(ev('pointermove', LONG_PRESS_SLOP + 1, 0));
    vi.advanceTimersByTime(LONG_PRESS_MS);
    expect(b.onLong).not.toHaveBeenCalled();
  });

  it('moves of another pointer are ignored', () => {
    const { el, onLong } = setup();
    el.dispatchEvent(ev('pointerdown', 0, 0));
    el.dispatchEvent(ev('pointermove', 100, 100, { pointerId: 9 }));
    vi.advanceTimersByTime(LONG_PRESS_MS);
    expect(onLong).toHaveBeenCalledTimes(1);
  });

  it('leaving the button or lifting early cancels', () => {
    for (const t of ['pointerleave', 'pointerup']) {
      const { el, onLong } = setup();
      el.dispatchEvent(ev('pointerdown'));
      vi.advanceTimersByTime(LONG_PRESS_MS - 1);
      el.dispatchEvent(ev(t));
      vi.advanceTimersByTime(LONG_PRESS_MS);
      expect(onLong, t).not.toHaveBeenCalled();
    }
  });

  it('a custom duration', () => {
    const { el, onLong } = setup(200);
    el.dispatchEvent(ev('pointerdown'));
    vi.advanceTimersByTime(199);
    expect(onLong).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(onLong).toHaveBeenCalledTimes(1);
  });

  it('the swallowed click is cancelled; the context menu never opens', () => {
    const { el } = setup();
    el.dispatchEvent(ev('pointerdown'));
    vi.advanceTimersByTime(LONG_PRESS_MS);
    const click = ev('click');
    el.dispatchEvent(click);
    expect(click.defaultPrevented).toBe(true);
    const menu = ev('contextmenu');
    el.dispatchEvent(menu);
    expect(menu.defaultPrevented).toBe(true);
  });

  it('after a second finger cancelled, a fresh press works again', () => {
    const { el, onLong } = setup();
    el.dispatchEvent(ev('pointerdown'));
    el.dispatchEvent(ev('pointerdown', 0, 0, { pointerId: 2, isPrimary: false }));
    el.dispatchEvent(ev('pointerup'));
    el.dispatchEvent(ev('pointerdown'));
    vi.advanceTimersByTime(LONG_PRESS_MS);
    expect(onLong).toHaveBeenCalledTimes(1);
  });
});
