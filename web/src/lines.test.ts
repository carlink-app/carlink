import { describe, expect, it } from 'vitest';
import { LineSplitter } from './lines';

describe('LineSplitter', () => {
  it('splits lines across chunks', () => {
    const l = new LineSplitter();
    expect(l.push('{"t":"a"}\n{"t":')).toEqual([{ t: 'a' }]);
    expect(l.push('"b"}')).toEqual([]);
    expect(l.push('\n')).toEqual([{ t: 'b' }]);
  });
  it('handles several lines per chunk, CRLF and blank lines', () => {
    const l = new LineSplitter();
    expect(l.push('{"t":"a"}\r\n\n  \r\n{"t":"b"}\n{"t":"c"}\n')).toEqual([{ t: 'a' }, { t: 'b' }, { t: 'c' }]);
  });
  it('skips bad JSON and non-objects', () => {
    const bad: string[] = [];
    const l = new LineSplitter((line) => bad.push(line));
    expect(l.push('{oops\n42\n[1]\n{"t":"ok"}\n')).toEqual([{ t: 'ok' }]);
    expect(bad).toEqual(['{oops', '42', '[1]']);
  });
});
