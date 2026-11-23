// SPDX-License-Identifier: Apache-2.0
//
// Excluded transactions of a range (D-19, F-A23, docs/14 collection_health_get `excluded`): the
// requests the agent's exclusion rules turned away (argos_agent_ignored_total). Empty states say
// "N requests excluded in this range" (docs/15 §6.7, 05 §2.0-8) so that a missing transaction is
// explained. null means the server has no source for the range (not measured), never 0.
import { api, unwrap, type EnvelopeOf } from './client';

/** Sum of the excluded counts of [from, to) by reason, or null when there is no source. */
export async function excludedCount(
  appId: number,
  from: string,
  to: string,
  signal?: AbortSignal,
): Promise<number | null> {
  const result = await unwrap<EnvelopeOf<'/api/v1/apps/{appId}/collection-health', 'get'>>(
    api().client.GET('/api/v1/apps/{appId}/collection-health', {
      params: { path: { appId }, query: { from, to } },
      ...(signal === undefined ? {} : { signal }),
    }),
  );
  const excluded = result.data.excluded;
  const reasons = Object.values(excluded.by_reason);
  if (reasons.length > 0) {
    return reasons.reduce((sum, n) => sum + n, 0);
  }
  // Without a breakdown, the series: null buckets have no source.
  const values = excluded.value.filter((v): v is number => typeof v === 'number');
  return values.length === 0 ? null : values.reduce((sum, n) => sum + n, 0);
}
