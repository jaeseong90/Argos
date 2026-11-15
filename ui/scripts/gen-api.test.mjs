// SPDX-License-Identifier: Apache-2.0
//
// check:api in the unit test run (docs/19 §6.2, 03 §3.3): the committed
// src/api/generated/schema.ts must equal a fresh generation from docs/api/openapi.yaml, and the
// x-argos-untrusted strings must come out as the Untrusted brand (SEC-60, DSN-14-13).
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { afterEach, beforeAll, describe, expect, it } from 'vitest';

import {
  checkGenerated,
  countUntrustedReferences,
  DEFAULT_OUT,
  DEFAULT_SPEC,
  firstDifference,
  generate,
  HEADER,
  main,
  prepareUntrusted,
} from './gen-api.mjs';
import { createFixture } from './test-fixtures.mjs';

/** @type {(() => void)[]} */
const cleanups = [];
afterEach(() => {
  cleanups.splice(0).forEach((cleanup) => {
    cleanup();
  });
});

/** @type {Awaited<ReturnType<typeof checkGenerated>>} */
let committed;
beforeAll(async () => {
  committed = await checkGenerated();
}, 60_000);

/**
 * The members of one component schema in the generated text (`<Name>: { … };` at schema depth).
 *
 * @param {string} text
 * @param {string} name
 * @returns {string}
 */
function schemaBlock(text, name) {
  const match = new RegExp(String.raw`\n {4}${name}: \{\n([\s\S]*?)\n {4}\};`).exec(text);
  if (match?.[1] === undefined) {
    throw new Error(`schema ${name} not found in the generated module`);
  }
  return match[1];
}

describe('gen:api / check:api', () => {
  it('the committed src/api/generated/schema.ts equals a fresh generation (check:api)', () => {
    expect(committed.actual, 'run pnpm gen:api').not.toBeNull();
    expect(
      committed.ok,
      committed.actual === null ? 'missing' : firstDifference(committed.expected, committed.actual),
    ).toBe(true);
  });

  it('brands x-argos-untrusted properties as Untrusted (App.name, ActiveTransaction.user_id)', () => {
    const app = schemaBlock(committed.expected, 'App');
    expect(app).toMatch(/\n {6}name: Untrusted;/);
    expect(app).toMatch(/\n {6}app_id: number;/);
    const active = schemaBlock(committed.expected, 'ActiveTransaction');
    expect(active).toMatch(/\n {6}user_id: Untrusted \| null;/);
    // tx_id is a 64-bit key as a decimal string (C-175), not collected text.
    expect(active).toMatch(/\n {6}tx_id: string;/);
  });

  it('brands untrusted arrays and maps element-wise and keeps non-string values', () => {
    const instance = schemaBlock(committed.expected, 'Instance');
    expect(instance).toMatch(/\n {6}modules_disabled: Untrusted\[\] \| null;/);
    const detail = schemaBlock(committed.expected, 'TransactionDetail');
    expect(detail).toMatch(/\n {6}headers: \{\n {8}\[key: string\]: Untrusted;\n {6}\};/);
    const series = schemaBlock(committed.expected, 'DashboardSeries');
    expect(series).toMatch(
      /\n {6}pool_usage_ratio: \{\n {8}\[key: string\]: \(number \| null\)\[\];/,
    );
  });

  it('types every x-argos-untrusted string of the contract as Untrusted, and nothing else', () => {
    const { stats } = committed;
    expect(stats.untrustedStrings).toBeGreaterThan(0);
    expect(countUntrustedReferences(committed.expected)).toBe(stats.untrustedStrings);
    // Every flag of the contract was seen (none hides behind a schema without a type).
    const flags = readFileSync(DEFAULT_SPEC, 'utf8').match(/^\s*x-argos-untrusted: true\s*$/gm);
    expect(stats.untrustedSchemas - stats.propagated).toBe(flags?.length ?? 0);
  });

  it('writes the SPDX header, the generated-file notice and LF line endings', () => {
    expect(committed.expected.startsWith(HEADER)).toBe(true);
    expect(committed.expected.split('\n')[0]).toBe('// SPDX-License-Identifier: Apache-2.0');
    expect(committed.expected).toContain('do not edit');
    expect(committed.expected).not.toContain('\r');
    expect(committed.expected.endsWith('\n')).toBe(true);
  });

  it('is deterministic', async () => {
    const again = await generate();
    expect(again.text).toBe(committed.expected);
  }, 60_000);

  it('check:api fails on a hand-edited or missing file and passes on a fresh one', async () => {
    const fixture = createFixture({
      'schema.ts': committed.expected.replace('name: Untrusted;', 'name: string;'),
    });
    cleanups.push(fixture.cleanup);
    const out = join(fixture.root, 'schema.ts');
    expect(await main(['--check', '--out', out])).toBe(1);
    expect(await main(['--check', '--out', join(fixture.root, 'missing.ts')])).toBe(1);
    expect(await main(['--out', out])).toBe(0);
    expect(readFileSync(out, 'utf8')).toBe(committed.expected);
    expect(await main(['--check', '--out', out])).toBe(0);
  }, 60_000);

  it('uses the default paths of docs/19 §6.2', () => {
    expect(DEFAULT_SPEC.replaceAll('\\', '/')).toMatch(/\/docs\/api\/openapi\.yaml$/);
    expect(DEFAULT_OUT.replaceAll('\\', '/')).toMatch(/\/ui\/src\/api\/generated\/schema\.ts$/);
  });
});

describe('prepareUntrusted', () => {
  it('carries the flag of arrays and maps to their string items and values', () => {
    const items = { type: 'string' };
    const values = { type: ['string', 'null'] };
    const root = {
      components: {
        schemas: {
          A: {
            properties: {
              list: { type: 'array', items, 'x-argos-untrusted': true },
              map: { type: 'object', additionalProperties: values, 'x-argos-untrusted': true },
              plain: { type: 'string' },
            },
          },
        },
      },
    };
    const stats = prepareUntrusted(root);
    expect(items).toEqual({ type: 'string', 'x-argos-untrusted': true });
    expect(values).toEqual({ type: ['string', 'null'], 'x-argos-untrusted': true });
    expect(stats).toEqual({
      untrustedSchemas: 4,
      untrustedStrings: 2,
      propagated: 2,
      nonString: [],
    });
  });

  it('records flagged non-string leaves and leaves enums unbranded', () => {
    const stats = prepareUntrusted({
      components: {
        schemas: {
          A: {
            properties: {
              n: { type: 'integer', 'x-argos-untrusted': true },
              e: { type: 'string', enum: ['a'], 'x-argos-untrusted': true },
            },
          },
        },
      },
    });
    expect(stats.untrustedStrings).toBe(0);
    expect(stats.nonString).toEqual([
      '#/components/schemas/A/properties/n',
      '#/components/schemas/A/properties/e',
    ]);
  });

  it('fails on an untrusted container whose items are a $ref', () => {
    expect(() =>
      prepareUntrusted({
        components: {
          schemas: {
            A: {
              type: 'array',
              items: { $ref: '#/components/schemas/B' },
              'x-argos-untrusted': true,
            },
          },
        },
      }),
    ).toThrow(/items is a \$ref/);
  });
});

describe('firstDifference', () => {
  it('names the first differing line', () => {
    expect(firstDifference('a\nb\n', 'a\nc\n')).toBe('line 2: expected "b", found "c"');
    expect(firstDifference('a\n', 'a\nb')).toBe('line 2: expected "", found "b"');
    expect(firstDifference('a', 'a')).toBe('no difference');
  });
});
