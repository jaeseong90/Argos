// SPDX-License-Identifier: Apache-2.0
//
// Template variables of the required-elements files and specs, from the seed manifest (absolute
// times of the generator's range, never fixed dates; ADR-0010 C-170).
import { deepLinkQuery } from '../fixtures/urlModel';
import { XSS_PAYLOADS } from '../fixtures/xssPayloads';
import type { SeedManifest } from '../stack/manifest';

/** Filter fields of the XSS deep link (one payload per field, in this order). */
export const XSS_FILTER_FIELDS = [
  'service',
  'instance',
  'host',
  'user',
  'url',
  'error',
  'sql',
  'thread',
  'method',
  'status',
  'request',
  'trace',
  'version',
] as const;

/** ISO-8601 UTC without milliseconds. */
function iso(ms: number): string {
  return new Date(ms).toISOString().replace('.000Z', 'Z');
}

export function manifestVariables(seed: SeedManifest): Record<string, string> {
  const start = Date.parse(seed.generator.range.start);
  const end = Date.parse(seed.generator.range.end);
  const primary = seed.apps.find((a) => a.app_id === seed.primary_app_id);
  const secondary = seed.apps.find((a) => a.app_id === seed.secondary_app_id);
  const vars: Record<string, string> = {
    primary_app_id: String(seed.primary_app_id),
    primary_app_name: primary?.name ?? '',
    primary_env: primary?.env ?? '',
    secondary_app_id: String(seed.secondary_app_id),
    secondary_app_name: secondary?.name ?? '',
    traffic_app_id: String(seed.traffic.app_id),
    range_from: seed.generator.range.start,
    range_to: seed.generator.range.end,
    // A 15-minute window in the middle of the generator's range (deep link 2: fixed range).
    mid15_from: iso(Math.floor((start + end) / 2 / 60_000) * 60_000 - 7.5 * 60_000),
    mid15_to: iso(Math.floor((start + end) / 2 / 60_000) * 60_000 + 7.5 * 60_000),
    primary_instance_id: String(primary?.instances[0]?.instance_id ?? ''),
    // Deep link with every XSS payload of ADR-0004 C-45 as a filter value (2.0-10, T-SEC-64).
    xss_filter_query: deepLinkQuery({
      filters: XSS_PAYLOADS.map((value, i) => ({
        field: XSS_FILTER_FIELDS[i] ?? `f${String(i)}`,
        value,
      })),
    }),
  };
  for (const [category, tx] of Object.entries(seed.tx)) {
    if (tx !== null) {
      vars[`tx.${category}.tx_id`] = tx.tx_id;
      vars[`tx.${category}.ts`] = tx.ts;
      vars[`tx.${category}.app_id`] = String(tx.app_id);
      // The trace categories (trace_root_two_apps) also name their trace (required/trace.yaml).
      if ('trace_id' in tx && typeof tx.trace_id === 'string') {
        vars[`tx.${category}.trace_id`] = tx.trace_id;
      }
    }
  }
  for (const window of seed.windows) {
    vars[`window.${window.name}.from`] = window.from;
    vars[`window.${window.name}.to`] = window.to;
    vars[`window.${window.name}.total`] = String(window.total);
  }
  return vars;
}
