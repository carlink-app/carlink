// Annex B H.264 / HEVC helpers (ported from spike/web/worker.js).

export type Codec = 'h264' | 'hevc';

const nalType = (b: number, codec: Codec): number => (codec === 'hevc' ? (b >> 1) & 0x3f : b & 0x1f);

interface Nal { type: number; at: number } // `at` = offset of the NAL header byte

function nals(au: Uint8Array, codec: Codec): Nal[] {
  const out: Nal[] = [];
  for (let i = 0; i + 3 < au.length; i++) {
    if (au[i] === 0 && au[i + 1] === 0 && au[i + 2] === 1) {
      out.push({ type: nalType(au[i + 3], codec), at: i + 3 });
      i += 2;
    }
  }
  return out;
}

/** Remove emulation prevention bytes (00 00 03 -> 00 00), up to `len` output bytes. */
function unescapeRbsp(b: Uint8Array, from: number, len: number): number[] {
  const out: number[] = [];
  let zeros = 0;
  for (let i = from; i < b.length && out.length < len; i++) {
    if (zeros >= 2 && b[i] === 3) { zeros = 0; continue; }
    zeros = b[i] === 0 ? zeros + 1 : 0;
    out.push(b[i]);
  }
  return out;
}

const hex2 = (b: number) => b.toString(16).padStart(2, '0').toUpperCase();

const SPS_TYPE: Record<Codec, number> = { h264: 7, hevc: 33 };

/** Bytes of the SPS NAL (up to the next start code), or null. */
export function spsBytes(au: Uint8Array, codec: Codec): Uint8Array | null {
  const list = nals(au, codec);
  const i = list.findIndex((n) => n.type === SPS_TYPE[codec]);
  if (i < 0) return null;
  const end = i + 1 < list.length ? list[i + 1].at - 3 : au.length;
  return au.subarray(list[i].at, end);
}

/** WebCodecs codec string derived from the SPS in an access unit, or null if none. */
export function codecString(au: Uint8Array, codec: Codec): string | null {
  const sps = spsBytes(au, codec);
  if (!sps) return null;
  if (codec === 'h264') {
    if (sps.length < 4) return null;
    return `avc1.${hex2(sps[1])}${hex2(sps[2])}${hex2(sps[3])}`;
  }
  // HEVC: profile_tier_level starts 3 bytes into the SPS NAL unit (2-byte header + 1 byte).
  const b = unescapeRbsp(sps, 0, 15);
  if (b.length < 15) return null;
  const ptl = b.slice(3);
  const space = ['', 'A', 'B', 'C'][ptl[0] >> 6];
  const tier = (ptl[0] >> 5) & 1 ? 'H' : 'L';
  const profile = ptl[0] & 0x1f;
  let compat = ((ptl[1] << 24) | (ptl[2] << 16) | (ptl[3] << 8) | ptl[4]) >>> 0;
  let rev = 0;
  for (let i = 0; i < 32; i++) { rev = ((rev << 1) | (compat & 1)) >>> 0; compat >>>= 1; }
  const constraints = ptl.slice(5, 11);
  while (constraints.length && constraints[constraints.length - 1] === 0) constraints.pop();
  const level = ptl[11];
  return ['hvc1', `${space}${profile}`, rev.toString(16).toUpperCase(), `${tier}${level}`,
    ...constraints.map((c) => c.toString(16).toUpperCase())].join('.');
}

/** Split an Annex B elementary stream into access units at AUD NAL units. */
export function splitAccessUnits(b: Uint8Array, codec: Codec): Uint8Array[] {
  const aud = codec === 'hevc' ? 35 : 9;
  const aus: Uint8Array[] = [];
  let start = -1;
  for (let i = 0; i + 3 < b.length; i++) {
    if (b[i] !== 0 || b[i + 1] !== 0 || b[i + 2] !== 1) continue;
    if (nalType(b[i + 3], codec) === aud) {
      let pos = i;
      if (pos > 0 && b[pos - 1] === 0) pos--;
      if (start >= 0) aus.push(b.subarray(start, pos));
      start = pos;
    }
    i += 2;
  }
  if (start >= 0) aus.push(b.subarray(start));
  return aus;
}
