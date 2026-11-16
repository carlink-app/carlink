// Long press on a button: holding still for LONG_PRESS_MS calls onLong and
// swallows the click that follows the lift, so a normal tap still taps.
// Moving (scrolling the launcher), a second finger or lifting early cancels.

export const LONG_PRESS_MS = 500;
/** Finger travel (CSS px) that turns a press into a scroll. */
export const LONG_PRESS_SLOP = 12;

type PEvent = Event & { clientX: number; clientY: number; pointerId: number; isPrimary?: boolean };

export function attachLongPress(el: EventTarget, onLong: () => void, ms = LONG_PRESS_MS) {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let start: { x: number; y: number; id: number } | null = null;
  let fired = false;
  const cancel = () => { clearTimeout(timer); timer = undefined; start = null; };

  el.addEventListener('pointerdown', (ev) => {
    const e = ev as PEvent;
    if (start || e.isPrimary === false) { cancel(); return; } // second finger
    fired = false;
    start = { x: e.clientX, y: e.clientY, id: e.pointerId };
    timer = setTimeout(() => { cancel(); fired = true; onLong(); }, ms);
  });
  el.addEventListener('pointermove', (ev) => {
    const e = ev as PEvent;
    if (start && e.pointerId === start.id && Math.hypot(e.clientX - start.x, e.clientY - start.y) > LONG_PRESS_SLOP) cancel();
  });
  for (const t of ['pointerup', 'pointercancel', 'pointerleave']) el.addEventListener(t, cancel);
  // The click of the lift that ended a long press is not a tap.
  el.addEventListener('click', (e) => {
    if (!fired) return;
    fired = false;
    e.stopImmediatePropagation();
    e.preventDefault();
  }, { capture: true });
  // Touch browsers open a context menu on a long press.
  el.addEventListener('contextmenu', (e) => e.preventDefault());
}
