// The Rust core drops a session on any page message type it doesn't know
// (core/src/server.rs CONTROL_TYPES). Every type the page can send must be
// there: a new one added only on the page and phone would cut the connection.
import { describe, expect, it } from 'vitest';
import proto from './protocol.ts?raw';
import core from '../../core/src/server.rs?raw';

describe('page messages vs the core allow-list', () => {
  it('every PageMsg type is a core control type', () => {
    const union = proto.slice(proto.indexOf('export type PageMsg'));
    const block = union.slice(0, union.indexOf(';\n\n') > 0 ? union.indexOf(';\n\n') : undefined);
    const pageTypes = [...block.matchAll(/t: '([a-z-]+)'/g)].map((m) => m[1]).filter((t) => t !== 'auth'); // auth is the handshake
    const list = core.match(/const CONTROL_TYPES: &\[&str\] = &\[([^\]]*)\]/)![1];
    const coreTypes = [...list.matchAll(/"([a-z-]+)"/g)].map((m) => m[1]);
    expect(pageTypes.length).toBeGreaterThan(8);
    expect(pageTypes.filter((t) => !coreTypes.includes(t))).toEqual([]);
  });
});
