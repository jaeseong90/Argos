// SPDX-License-Identifier: Apache-2.0
//
// The YAML subset of the required-elements files (e2e/required/yaml.ts).
import { describe, expect, it } from 'vitest';

import { parseYaml } from './yaml';

describe('required-elements YAML subset', () => {
  it('parses mappings, sequences of mappings, scalars and flow sequences', () => {
    const doc = parseYaml(
      [
        '# comment',
        'screen: common',
        "title: 'Common elements (docs/05 §2.0)'",
        'items:',
        '  - id: "2.0-1"',
        '    req: [F-U14, "SEC-64"]',
        '    owner: WO-30   # trailing comment',
        '    steps:',
        '      - goto: /apps/{primary_app_id}/dashboard?from={range_from}',
        '      - press: "]"',
        '      - fill:',
        '          testid: login-username',
        "          value: 'a # not a comment'",
        '    assert:',
        '    - visible: time-range-picker',
      ].join('\n'),
    );
    expect(doc).toEqual({
      screen: 'common',
      title: 'Common elements (docs/05 §2.0)',
      items: [
        {
          id: '2.0-1',
          req: ['F-U14', 'SEC-64'],
          owner: 'WO-30',
          steps: [
            { goto: '/apps/{primary_app_id}/dashboard?from={range_from}' },
            { press: ']' },
            { fill: { testid: 'login-username', value: 'a # not a comment' } },
          ],
          assert: [{ visible: 'time-range-picker' }],
        },
      ],
    });
  });

  it('reads numbers, booleans, null and empty values', () => {
    expect(parseYaml('a: 1\nb: true\nc: null\nd:\ne: -2.5\nf: x:y')).toEqual({
      a: 1,
      b: true,
      c: null,
      d: null,
      e: -2.5,
      f: 'x:y',
    });
  });

  it('rejects what the subset does not support, with the line number', () => {
    expect(() => parseYaml('a:\n\t- x')).toThrow(/line 2: tabs/);
    expect(() => parseYaml('a: &anchor x')).toThrow(/line 1: unsupported/);
    expect(() => parseYaml('a: { b: 1 }')).toThrow(/unsupported/);
    expect(() => parseYaml('a: 1\na: 2')).toThrow(/line 2: duplicate key a/);
    expect(() => parseYaml('a: [1, [2]]')).toThrow(/nested flow/);
    expect(() => parseYaml("a: 'open")).toThrow(/unterminated/);
    expect(() => parseYaml('  a: 1')).toThrow(/column 1/);
  });
});
