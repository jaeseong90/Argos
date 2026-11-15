// SPDX-License-Identifier: Apache-2.0
//
// The Untrusted brand (SEC-60, DSN-14-13): run-time helpers and, checked by `tsc -b`
// (pnpm typecheck), the types that gen:api derives from x-argos-untrusted.
import { describe, expect, expectTypeOf, it } from 'vitest';

import type { components } from './generated/schema';
import { untrusted, untrustedEntries, type Untrusted } from './untrusted';

type Schemas = components['schemas'];

describe('Untrusted', () => {
  it('is a plain string at run time', () => {
    const value = untrusted('<img src=x onerror=window.__xss=1>');
    expect(typeof value).toBe('string');
    expect(value).toBe('<img src=x onerror=window.__xss=1>');
    expectTypeOf(value).toEqualTypeOf<Untrusted>();
    // `Untrusted<'GET'>` narrows the string type and keeps the brand.
    expectTypeOf<Untrusted<'GET'>>().toExtend<Untrusted>();
    // Usable wherever a string is expected, but a plain string is not an Untrusted.
    expectTypeOf<Untrusted>().toExtend<string>();
    expectTypeOf<string>().not.toExtend<Untrusted>();
  });

  it('keeps map keys Untrusted through untrustedEntries', () => {
    const headers = { 'x-seed': untrusted('"><svg/onload=window.__xss=1>') };
    const entries = untrustedEntries(headers);
    expect(entries).toEqual([['x-seed', '"><svg/onload=window.__xss=1>']]);
    expectTypeOf(entries).toEqualTypeOf<[Untrusted, Untrusted][]>();
    expect(untrustedEntries(null)).toEqual([]);
    expect(untrustedEntries(undefined)).toEqual([]);
  });

  it('types the x-argos-untrusted fields of the generated API (gen:api)', () => {
    expectTypeOf<Schemas['App']['name']>().toEqualTypeOf<Untrusted>();
    expectTypeOf<Schemas['App']['app_id']>().toEqualTypeOf<number>();
    expectTypeOf<Schemas['ActiveTransaction']['user_id']>().toEqualTypeOf<Untrusted | null>();
    expectTypeOf<Schemas['ActiveTransaction']['tx_id']>().toEqualTypeOf<string>();
    expectTypeOf<Schemas['Instance']['modules_disabled']>().toEqualTypeOf<Untrusted[] | null>();
    expectTypeOf<Schemas['TransactionDetail']['headers']>().toEqualTypeOf<
      Record<string, Untrusted>
    >();
    expectTypeOf<Schemas['SearchMatch']['label']>().toEqualTypeOf<Untrusted>();
    expect(true).toBe(true);
  });
});
