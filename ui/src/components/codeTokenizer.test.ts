// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import { tokenize, tokenizeConfig, tokenizeSql, tokenizeStack, toLines } from './codeTokenizer';
import { XSS_PAYLOADS } from './testing/xssPayloads';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'SEC-61' };

const join = (tokens: readonly { text: string }[]): string => tokens.map((t) => t.text).join('');

describe('code tokenizer (docs/16 §9.3)', () => {
  it('T-SEC-61 loses or adds no character for any payload in any language', () => {
    const samples = [
      ...XSS_PAYLOADS,
      "SELECT * FROM t WHERE a = 'x''y' /* open",
      'java.lang.IllegalStateException: boom\r\n\tat a.B.c(B.java:1)\n... 3 more',
      'key=value\n# comment\nother: 1\r\n',
      '',
    ];
    for (const sample of samples) {
      for (const language of ['sql', 'stack', 'config', 'text'] as const) {
        expect(join(tokenize(sample, language)), `${language}: ${JSON.stringify(sample)}`).toBe(
          sample,
        );
      }
    }
  });

  it('classifies SQL keywords, strings, numbers, comments and placeholders', () => {
    const tokens = tokenizeSql('select a, 42 from t where b = :name and c = $1 and d = ? -- x');
    const of = (type: string) => tokens.filter((t) => t.type === type).map((t) => t.text);
    expect(of('keyword')).toEqual(['select', 'from', 'where', 'and', 'and']);
    expect(of('number')).toEqual(['42']);
    expect(of('placeholder')).toEqual([':name', '$1', '?']);
    expect(of('comment')).toEqual(['-- x']);
  });

  it('splits stack traces into exception, frames, locations and omitted frames', () => {
    const tokens = tokenizeStack(
      'java.lang.IllegalStateException: boom\n\tat com.example.Svc.run(Svc.java:42)\nCaused by: java.io.IOException: x\n\t... 3 more',
    );
    const of = (type: string) => tokens.filter((t) => t.type === type).map((t) => t.text);
    expect(of('exception')).toEqual([
      'java.lang.IllegalStateException: boom',
      ' java.io.IOException: x',
    ]);
    expect(of('frame')).toEqual(['com.example.Svc.run']);
    expect(of('location')).toEqual(['(Svc.java:42)']);
    expect(of('heading')).toEqual(['Caused by:']);
  });

  it('marks configuration keys and comments', () => {
    const tokens = tokenizeConfig('server.port=8080\n# note\nlevel: info');
    expect(tokens.filter((t) => t.type === 'key').map((t) => t.text)).toEqual([
      'server.port',
      'level',
    ]);
    expect(tokens.filter((t) => t.type === 'comment').map((t) => t.text)).toEqual(['# note']);
  });

  it('cuts tokens into display lines without terminators', () => {
    const lines = toLines(tokenizeSql('select 1 /* a\nb */\r\nfrom t\n'));
    expect(lines.map((line) => join(line))).toEqual(['select 1 /* a', 'b */', 'from t']);
  });
});
