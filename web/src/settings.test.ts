import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DEFAULT_HOST, DEFAULT_PORT } from './protocol';
import { DEFAULT_DISPLAY, effective, endpoint, loadSaved, paneDpi, sameEndpoint, save, saveDisplay, validPort, type Display, type Settings } from './settings';

function memoryStorage(): Storage {
  const m = new Map<string, string>();
  return {
    get length() { return m.size; },
    clear: () => m.clear(),
    getItem: (k) => m.get(k) ?? null,
    key: (i) => [...m.keys()][i] ?? null,
    removeItem: (k) => { m.delete(k); },
    setItem: (k, v) => { m.set(k, String(v)); },
  };
}

describe('settings', () => {
  it('validates ports', () => {
    expect(validPort('4433')).toBe(4433);
    expect(validPort(24433)).toBe(24433);
    expect(validPort('0')).toBeNull();
    expect(validPort('70000')).toBeNull();
    expect(validPort('abc')).toBeNull();
    expect(validPort('')).toBeNull();
    expect(validPort('  ')).toBeNull();
    expect(validPort('44.5')).toBeNull();
    expect(validPort(null)).toBeNull();
  });
  it('builds endpoints', () => {
    expect(endpoint({ host: '9.9.0.1', port: 4433 })).toBe('https://9.9.0.1:4433');
    expect(endpoint({ host: 'fe80::1', port: 1 })).toBe('https://[fe80::1]:1');
    expect(endpoint({ host: '[fe80::1]', port: 1 })).toBe('https://[fe80::1]:1');
  });
});

describe('saved settings and fragment overrides', () => {
  beforeEach(() => { vi.stubGlobal('localStorage', memoryStorage()); });
  afterEach(() => { vi.unstubAllGlobals(); });

  const d = DEFAULT_DISPLAY;
  const defaults: Settings = { code: '', host: DEFAULT_HOST, port: DEFAULT_PORT, ...d };
  const saved: Settings = { code: 'SAVED', host: '10.0.0.2', port: 5000, ...d };

  it('defaults when nothing is saved', () => {
    expect(loadSaved()).toEqual(defaults);
  });
  it('round-trips through save', () => {
    save({ code: 'ABCD-EFGH-JKLM', host: '10.0.0.2', port: 5000, showText: false });
    expect(loadSaved()).toEqual({ code: 'ABCD-EFGH-JKLM', host: '10.0.0.2', port: 5000, showText: false });
  });
  it('falls back per field on corrupt or wrongly typed storage', () => {
    localStorage.setItem('carlink.settings', '{not json');
    expect(loadSaved()).toEqual(defaults);
    localStorage.setItem('carlink.settings', JSON.stringify({ code: 7, host: '', port: 99999 }));
    expect(loadSaved()).toEqual(defaults);
    localStorage.setItem('carlink.settings', JSON.stringify({ code: 'X', host: 'h', port: '4434' }));
    expect(loadSaved()).toEqual({ code: 'X', host: 'h', port: 4434, ...d });
    localStorage.setItem('carlink.settings', JSON.stringify({ code: 'X', showText: 'no' }));
    expect(loadSaved()).toEqual({ ...defaults, code: 'X' });
  });
  it('fragment overrides win but are not saved', () => {
    save({ ...saved });
    expect(effective('#code=DEV&host=127.0.0.1&port=4500')).toEqual({ code: 'DEV', host: '127.0.0.1', port: 4500, showText: true });
    expect(loadSaved()).toEqual({ ...saved });
  });
  it('ignores missing or invalid overrides', () => {
    save(saved);
    expect(effective('')).toEqual(saved);
    expect(effective('#host=&port=nope')).toEqual(saved);
    // An explicit empty code is an override (unpair for this load).
    expect(effective('#code=')).toEqual({ ...saved, code: '' });
  });
  it('keeps settings saved before display settings existed (milestone 2 format)', () => {
    localStorage.setItem('carlink.settings', JSON.stringify({ code: 'OLD', host: '10.0.0.3', port: 4433 }));
    expect(loadSaved()).toEqual({ code: 'OLD', host: '10.0.0.3', port: 4433, showText: true });
  });
  it('saves display settings without touching the pairing or persisting overrides', () => {
    save(saved);
    effective('#code=DEV&host=127.0.0.1'); // in effect, but must not leak into storage
    saveDisplay({ showText: false });
    expect(loadSaved()).toEqual({ ...saved, showText: false });
  });
  it('save persists only known fields', () => {
    save({ ...saved, extra: 'x' } as Settings);
    expect(JSON.parse(localStorage.getItem('carlink.settings')!)).toEqual(saved);
  });
  it('display changes are not an endpoint change', () => {
    expect(sameEndpoint(saved, { ...saved, showText: false })).toBe(true);
    expect(sameEndpoint(saved, { ...saved, code: 'OTHER' })).toBe(false);
    expect(sameEndpoint(saved, { ...saved, host: 'h' })).toBe(false);
    expect(sameEndpoint(saved, { ...saved, port: 1 })).toBe(false);
  });
});


describe('display settings edge cases', () => {
  beforeEach(() => { vi.stubGlobal('localStorage', memoryStorage()); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it('saveDisplay with nothing saved keeps the default pairing', () => {
    saveDisplay({ showText: false });
    expect(loadSaved()).toEqual({ code: '', host: DEFAULT_HOST, port: DEFAULT_PORT, showText: false });
  });
  it('saveDisplay ignores extra fields on its argument', () => {
    saveDisplay({ showText: true, code: 'LEAK', host: 'x' } as unknown as Display);
    expect(loadSaved()).toEqual({ code: '', host: DEFAULT_HOST, port: DEFAULT_PORT, showText: true });
  });
});


describe('paneDpi', () => {
  it('biggest UI that keeps tablet layouts (shortest side ~640 dp)', () => {
    // Tesla browser, normal or expanded: ~880 px tall -> 220 dpi -> 640 dp.
    expect(paneDpi(1190, 880, 1)).toBe(220);
    expect(paneDpi(1840, 880, 1)).toBe(220);
    // (A window under ~750 px tall hits the 200 dpi floor and gets phone layouts; fine.)
    for (const h of [800, 880, 1000, 1150]) expect((h * 160) / paneDpi(1840, h, 1)).toBeGreaterThanOrEqual(600);
  });
  it('bounded to 200..260 dpi (ceiling x pixel ratio)', () => {
    expect(paneDpi(1920, 1150, 1)).toBe(260); // full screen: capped
    expect(paneDpi(800, 480, 1)).toBe(200);
    expect(paneDpi(3648, 1810, 2)).toBe(453); // 2x test screen: same layout as the car
  });
  it('Tesla as measured (zoom 1.53, 1036x918 pane): tablet layout, not phone', () => {
    const dpi = paneDpi(1036, 918, 1.53);
    expect(dpi).toBe(230);
    expect((918 * 160) / dpi).toBeGreaterThanOrEqual(600);
  });
  it('floor is in device pixels (not scaled by zoom), ceiling is', () => {
    expect(paneDpi(800, 480, 2)).toBe(200); // a scaled floor would give 400
    expect(paneDpi(1036, 700, 1.53)).toBe(200);
    expect(paneDpi(1920, 1600, 1.53)).toBe(398); // 260 x 1.53
  });
  it('stays within the phone\'s 72..640 and survives bad input', () => {
    expect(paneDpi(4000, 4000, 3)).toBe(640);
    expect(paneDpi(0, 0, 0)).toBe(220);
    expect(paneDpi(1840, 880, Number.NaN)).toBe(220);
  });
});
