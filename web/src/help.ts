// What to tell the driver while the page can't reach the phone; no DOM here.
import { NO_WEBTRANSPORT, REJECTED, type Status } from './connection';

export type HelpKind = 'connecting' | 'unreachable' | 'code' | 'handshake' | 'browser';

export interface Help {
  kind: HelpKind;
  title: string;
  /** What went wrong, in the connection's words (retry countdown removed); '' if nothing yet. */
  detail: string;
  steps: string[];
  /** A "Retry now" button makes sense (the connection retries by itself otherwise). */
  retry: boolean;
}

const HOTSPOT = 'Is the car connected to your phone\'s hotspot (Wi-Fi)?';
const RUNNING = 'Is TSLink running on the phone? Open it and tap Start engine.';
const CODE_AGAIN = 'Pairing code changed? Tap Pairing and enter the code the phone shows.';
const ADDRESS = 'Still nothing? Check the phone address in Pairing.';

/** The connection's error text without the " · retrying in Ns" suffix. */
export const errorText = (text: string): string => text.replace(/ · retrying in .*$/, '');

/** The kind of trouble behind an error status text. */
export function helpKind(text: string): HelpKind {
  const t = errorText(text);
  if (t === NO_WEBTRANSPORT) return 'browser';
  if (t.startsWith(REJECTED) || t.startsWith('No pairing code')) return 'code';
  if (/handshake|certificate/i.test(t)) return 'handshake';
  return 'unreachable';
}

const HELP: Record<HelpKind, Omit<Help, 'detail' | 'retry'>> = {
  connecting: { kind: 'connecting', title: 'Connecting to your phone…', steps: [HOTSPOT, RUNNING, CODE_AGAIN] },
  unreachable: { kind: 'unreachable', title: 'Can\'t reach your phone', steps: [HOTSPOT, RUNNING, ADDRESS] },
  code: {
    kind: 'code', title: 'The phone didn\'t accept the pairing code',
    steps: ['The pairing code on the phone may have changed.', 'Open TSLink on the phone to see the current code.', 'Tap Pairing and enter it again.'],
  },
  // A wrong code, a phone that isn't listening and an unreachable phone all look like this.
  handshake: {
    kind: 'handshake', title: 'The phone didn\'t answer the secure connection',
    steps: [HOTSPOT, RUNNING, CODE_AGAIN, 'Is the date and time right on both the car and the phone?'],
  },
  browser: {
    kind: 'browser', title: 'This browser can\'t connect',
    steps: ['TSLink needs WebTransport, which this browser doesn\'t have.', 'Use the car\'s built-in browser after a software update, or a recent Chrome or Edge.'],
  },
};

/**
 * Help for a status: null when connected or idle (the pairing screen covers
 * "not paired"). Connecting shows the last error's help if there was one,
 * so the panel doesn't flip back and forth between retries.
 */
export function helpFor(st: Status, lastError: string | null): Help | null {
  if (st.s === 'error') {
    const kind = helpKind(st.text);
    return { ...HELP[kind], detail: errorText(st.text), retry: kind !== 'browser' };
  }
  if (st.s === 'connecting') {
    if (lastError) return { ...HELP[helpKind(lastError)], detail: 'Trying again…', retry: false };
    return { ...HELP.connecting, detail: '', retry: false };
  }
  return null;
}
