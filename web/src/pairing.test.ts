import { describe, expect, it } from 'vitest';
import { drawQr, newSecret, pairedCode, qrStatusText, pairingView, PAIR_LINK, qrModules, qrText, SECRET_LENGTH } from './pairing';

describe('pairing', () => {
  it('makes secrets from the code alphabet', () => {
    const s = newSecret();
    expect(s).toHaveLength(SECRET_LENGTH);
    expect(s).toMatch(/^[A-HJ-NP-Z2-9]+$/);
    expect(newSecret((a) => a.fill(0))).toBe('A'.repeat(SECRET_LENGTH));
  });
  it('QR text is what the phone expects', () => {
    // A link, so the phone's camera can open TSLink; the secret after # never reaches a server.
    expect(qrText('ABC')).toBe('https://tslink.app/pair#ABC');
    expect(PAIR_LINK).toBe('https://tslink.app/pair#');
    const m = qrModules(qrText(newSecret()));
    expect(m.length).toBeGreaterThanOrEqual(21);
    expect(m.every((row) => row.length === m.length)).toBe(true);
  });
  it('accepts only well-formed codes from the phone', () => {
    expect(pairedCode('abcd-efgh-jkmn')).toBe('ABCD-EFGH-JKMN');
    expect(pairedCode('ABCDEFGHJKMN')).toBe('ABCD-EFGH-JKMN');
    expect(pairedCode('ABCD-EFGH-JKM0')).toBeNull(); // 0 isn't in the alphabet
    expect(pairedCode('ABCD-EFGH')).toBeNull();
    expect(pairedCode(42)).toBeNull();
    expect(pairedCode('<script>')).toBeNull();
  });
});

describe('pairing edge cases', () => {
  it('maps every byte value into the alphabet (no bias, no out of range)', () => {
    const all = newSecret((a) => a.map((_, i) => [0, 31, 32, 255, 224][i % 5]));
    expect(all.slice(0, 5)).toBe('A9A9A'); // 224 % 32 == 0
    expect(newSecret((a) => a.fill(255))).toBe('9'.repeat(SECRET_LENGTH));
  });
  it('refuses codes of the wrong length or with look-alike characters', () => {
    expect(pairedCode('ABCD-EFGH-JKMNP')).toBeNull(); // 13
    expect(pairedCode('ABCD-EFGH-JKMI')).toBeNull(); // I
    expect(pairedCode('ABCD-EFGH-JKMO')).toBeNull(); // O
    expect(pairedCode('ABCD-EFGH-JKM1')).toBeNull(); // 1
    expect(pairedCode('')).toBeNull();
    expect(pairedCode(null)).toBeNull();
    expect(pairedCode(' abcd efgh 2345 ')).toBe('ABCD-EFGH-2345');
  });
  it('the QR code round-trips the text length it was given', () => {
    // Longer text needs a bigger symbol: modules grow, never shrink.
    expect(qrModules(qrText('A'.repeat(64))).length).toBeGreaterThanOrEqual(qrModules(qrText('A')).length);
  });
});

describe('drawQr', () => {
  const fakeCanvas = () => {
    const rects: [string, number, number, number, number][] = [];
    const g = { fillStyle: '', fillRect(x: number, y: number, w: number, h: number) { rects.push([g.fillStyle, x, y, w, h]); } };
    const canvas = { width: 0, height: 0, style: { width: '', height: '' }, getContext: () => g };
    return { canvas: canvas as unknown as HTMLCanvasElement, rects };
  };

  it('whole device pixels per module, a 4-module quiet zone, CSS size from dpr', () => {
    const { canvas, rects } = fakeCanvas();
    const m = [[true, false], [false, true]]; // 2 modules + 8 quiet = 10
    drawQr(canvas, m, 100, 2);
    expect(canvas.width).toBe(200); // floor(100*2/10)=20 px per module
    expect(canvas.height).toBe(200);
    expect(canvas.style.width).toBe('100px');
    expect(rects[0]).toEqual(['#fff', 0, 0, 200, 200]);
    expect(rects.slice(1)).toEqual([['#000', 80, 80, 20, 20], ['#000', 100, 100, 20, 20]]);
  });

  it('never below one pixel per module', () => {
    const { canvas } = fakeCanvas();
    drawQr(canvas, Array.from({ length: 40 }, () => Array(40).fill(false)), 10, 1);
    expect(canvas.width).toBe(48);
  });

  it('rounds down so the code fits the requested size', () => {
    const { canvas } = fakeCanvas();
    drawQr(canvas, Array.from({ length: 25 }, () => Array(25).fill(false)), 300, 1.5);
    expect(canvas.width).toBe(13 * 33); // floor(450/33)=13
    expect(parseFloat(canvas.style.width)).toBeLessThanOrEqual(300);
  });
});

describe('pairingView', () => {
  it('shows the QR code when there is no code yet', () => {
    expect(pairingView(false, false)).toBe('qr');
  });
  it('shows the QR code when the saved code no longer connects (unpaired on the phone)', () => {
    expect(pairingView(true, false)).toBe('qr');
  });
  it('keeps a live connection: paired view with Pair again', () => {
    expect(pairingView(true, true)).toBe('paired');
  });
});

describe('qrStatusText', () => {
  it('waits while the phone answers (not scanned yet) or after a single miss', () => {
    expect(qrStatusText(0, '9.9.0.1', 4433)).toEqual({ text: 'Waiting for your phone…', warn: false });
    expect(qrStatusText(1, '9.9.0.1', 4433)).toEqual({ text: 'Waiting for your phone…', warn: false });
  });
  it('says the address may be wrong once nothing answers twice in a row', () => {
    expect(qrStatusText(2, '9.9.0.1', 4433)).toEqual({
      text: "Can't reach the phone at 9.9.0.1:4433. Is the car on the phone's hotspot? Otherwise check the address under Advanced.",
      warn: true,
    });
  });
});
