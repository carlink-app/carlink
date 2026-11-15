// Pointer input on a pane canvas -> touch messages in pane (video) pixels.

import type { PageMsg } from './protocol';

export interface TouchTarget {
  pane: number;
  el: HTMLElement;
  /** Current video size of the pane in pixels (what the phone renders). */
  size(): { w: number; h: number };
  send(m: PageMsg): void;
  /** Where ripples are drawn (positioned ancestor of `el`). */
  rippleHost: HTMLElement;
}

const MAX_POINTERS = 10;

/** Maps PointerEvent.pointerId to small ints 0..9, reusing the lowest free one. */
export class PointerIds {
  private ids = new Map<number, number>();
  alloc(pointerId: number): number | null {
    const used = new Set(this.ids.values());
    for (let i = 0; i < MAX_POINTERS; i++) {
      if (!used.has(i)) { this.ids.set(pointerId, i); return i; }
    }
    return null;
  }
  get(pointerId: number): number | undefined { return this.ids.get(pointerId); }
  release(pointerId: number): number | undefined {
    const id = this.ids.get(pointerId);
    this.ids.delete(pointerId);
    return id;
  }
}

/**
 * Map a client point to pane pixels. The canvas is shown with
 * `object-fit: contain`, so account for letterboxing when the aspect differs.
 */
export function toPane(clientX: number, clientY: number, rect: DOMRect | { left: number; top: number; width: number; height: number },
  w: number, h: number): { x: number; y: number } {
  const scale = Math.min(rect.width / w, rect.height / h);
  const ox = rect.left + (rect.width - w * scale) / 2;
  const oy = rect.top + (rect.height - h * scale) / 2;
  const x = Math.min(w - 1, Math.max(0, (clientX - ox) / scale));
  const y = Math.min(h - 1, Math.max(0, (clientY - oy) / scale));
  return { x: Math.round(x * 10) / 10, y: Math.round(y * 10) / 10 };
}

/** Minimum gap between moves of one pointer (~125 Hz): a 1000 Hz mouse or
 * pointerrawupdate would otherwise flood the phone (it caps control messages). */
export const MOVE_INTERVAL_MS = 8;

/**
 * Per-pointer move throttle: at most one move per [interval] ms, always the
 * newest position, and nothing pending is lost (flush before up/cancel).
 */
export class MoveThrottle {
  private last = new Map<number, number>();
  private pending = new Map<number, { x: number; y: number; timer: ReturnType<typeof setTimeout> }>();

  constructor(
    private readonly send: (id: number, clientX: number, clientY: number) => void,
    private readonly now: () => number = () => performance.now(),
    private readonly interval = MOVE_INTERVAL_MS,
  ) {}

  move(id: number, x: number, y: number): void {
    const p = this.pending.get(id);
    if (p) { p.x = x; p.y = y; return; } // a send is already scheduled: just update it
    const wait = this.interval - (this.now() - (this.last.get(id) ?? -Infinity));
    if (wait <= 0) { this.emit(id, x, y); return; }
    const timer = setTimeout(() => this.flush(id), wait);
    this.pending.set(id, { x, y, timer });
  }

  /** Send any pending move for [id] now (call before its up/cancel). */
  flush(id: number): void {
    const p = this.pending.get(id);
    if (!p) return;
    clearTimeout(p.timer);
    this.pending.delete(id);
    this.emit(id, p.x, p.y);
  }

  /** Pointer finished: flush, then forget it. */
  end(id: number): void {
    this.flush(id);
    this.last.delete(id);
  }

  private emit(id: number, x: number, y: number) {
    this.last.set(id, this.now());
    this.send(id, x, y);
  }
}

export function attachTouch(t: TouchTarget): void {
  const el = t.el;
  el.style.touchAction = 'none';
  const ids = new PointerIds();
  let rect = el.getBoundingClientRect();

  const emitAt = (a: 'down' | 'move' | 'up' | 'cancel', clientX: number, clientY: number, id: number) => {
    const { w, h } = t.size();
    if (!w || !h) return;
    const { x, y } = toPane(clientX, clientY, rect, w, h);
    t.send({ t: 'touch', pane: t.pane, a, id, x, y });
  };
  const emit = (a: 'down' | 'up' | 'cancel', e: PointerEvent, id: number) => emitAt(a, e.clientX, e.clientY, id);
  const moves = new MoveThrottle((id, x, y) => emitAt('move', x, y, id));

  el.addEventListener('pointerdown', (e) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    e.preventDefault();
    rect = el.getBoundingClientRect();
    const id = ids.alloc(e.pointerId);
    if (id === null) return;
    try { el.setPointerCapture(e.pointerId); } catch { /* pointer already gone */ }
    emit('down', e, id);
    ripple(t.rippleHost, e.clientX, e.clientY);
  });

  const onMove = (e: PointerEvent) => {
    const id = ids.get(e.pointerId);
    if (id === undefined) return;
    moves.move(id, e.clientX, e.clientY);
  };

  if ('onpointerrawupdate' in window) {
    // Fires as soon as the input arrives, not aligned to animation frames.
    el.addEventListener('pointerrawupdate', (e) => onMove(e as PointerEvent));
  } else {
    el.addEventListener('pointermove', (e) => {
      const list = typeof e.getCoalescedEvents === 'function' ? e.getCoalescedEvents() : [];
      if (list.length) list.forEach(onMove);
      else onMove(e);
    });
  }

  const end = (a: 'up' | 'cancel') => (e: PointerEvent) => {
    const id = ids.release(e.pointerId);
    if (id === undefined) return;
    moves.end(id);
    emit(a, e, id);
  };
  el.addEventListener('pointerup', end('up'));
  el.addEventListener('pointercancel', end('cancel'));
  el.addEventListener('lostpointercapture', end('cancel'));
  el.addEventListener('contextmenu', (e) => e.preventDefault());
}

/** Instant local feedback; purely cosmetic. */
export function ripple(host: HTMLElement, clientX: number, clientY: number): void {
  const r = host.getBoundingClientRect();
  const d = document.createElement('div');
  d.className = 'ripple';
  d.style.left = `${clientX - r.left}px`;
  d.style.top = `${clientY - r.top}px`;
  d.addEventListener('animationend', () => d.remove());
  host.appendChild(d);
}
