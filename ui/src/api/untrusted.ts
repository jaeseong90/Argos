// SPDX-License-Identifier: Apache-2.0
//
// Branded type for collected strings (DSN-14-13, SEC-60, docs/16 §9.1). The server does not
// escape collected strings (service names, URLs, SQL, exception messages, header and thread
// names, user ids, ...); the OpenAPI contract marks them `x-argos-untrusted: true` and gen:api
// types them `Untrusted` (scripts/gen-api.mjs). The ESLint rule argos/untrusted-text rejects an
// Untrusted value as a JSX child or attribute anywhere but the value/href props of SafeText,
// SafeLink and CodeBlock, so collected data reaches the DOM as text nodes through one reviewed
// component.
//
// An Untrusted value is still a string at run time: it can be compared, sorted, used as a map
// key or sent back to the server as is. Template literals, String() and string methods return a
// plain `string` and drop the brand, so render the value itself, not a copy of it.

/**
 * A string collected from monitored applications or entered by users: render only through
 * SafeText, SafeLink or CodeBlock. `Untrusted` and `Untrusted<string>` are the same type.
 * The brand property `__argosUntrusted` exists only in the type system, never at run time; the
 * ESLint rule argos/untrusted-text looks for it (eslint-rules/untrusted-text.mjs).
 */
export type Untrusted<T extends string = string> = T & {
  readonly __argosUntrusted: true;
};

/**
 * Marks a string as untrusted, for values that did not come through the generated client
 * (WebSocket frames after zod validation, test fixtures). Never use it to launder a value in the
 * other direction: an Untrusted value is assignable to `string` without a call.
 */
export function untrusted(value: string): Untrusted {
  return value as Untrusted;
}

/**
 * Entries of a map whose keys are collected strings too (HTTP header names, metric labels, pool
 * names: `x-argos-untrusted` objects in the contract). TypeScript cannot brand the keys that
 * Object.entries returns, so iterate those maps with this helper to keep keys Untrusted.
 */
export function untrustedEntries<V>(
  record: Readonly<Record<string, V>> | null | undefined,
): [Untrusted, V][] {
  if (record === null || record === undefined) {
    return [];
  }
  return Object.entries(record).map(([key, value]): [Untrusted, V] => [untrusted(key), value]);
}
