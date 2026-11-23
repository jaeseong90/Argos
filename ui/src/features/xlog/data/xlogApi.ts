// SPDX-License-Identifier: Apache-2.0
//
// Server calls of the XLog screen through the generated client (docs/api/openapi.yaml, src/api):
// the points of a range (docs/14 §4.5) and the events of a range for the markers (docs/14 §4.12).
// No hand-written requests (03 §3.3); errors come back as ApiError (docs/14 §2.6).
import {
  api,
  unwrap,
  type Enveloped,
  type EnvelopeOf,
  type ResponseMeta,
} from '../../../api/client';

import type { XLogPointColumns } from './columns';
import type { XLogRequestQuery } from './xlogQuery';

type XLogEnvelope = EnvelopeOf<'/api/v1/apps/{appId}/xlog', 'get'>;
type EventsEnvelope = EnvelopeOf<'/api/v1/apps/{appId}/events', 'get'>;

/** The `data` of `GET /apps/{appId}/xlog`: point columns plus the local dictionary. */
export type XLogPointsData = XLogEnvelope['data'];
/** One event of `GET /apps/{appId}/events` (docs/14 §4.12). */
export type AppEvent = EventsEnvelope['data'][number];

export interface XLogPoints {
  readonly data: XLogPointsData;
  readonly meta: ResponseMeta;
}

/** The point columns of a response, as the buffer conversion reads them. */
export function pointColumns(data: XLogPointsData): XLogPointColumns {
  return data;
}

/** `GET /apps/{appId}/xlog` (range ≤ 24 h, ≤ 100,000 points, gzip; docs/14 §4.5). */
export async function fetchXLogPoints(
  appId: number,
  query: XLogRequestQuery,
  signal?: AbortSignal,
): Promise<XLogPoints> {
  const result: Enveloped<XLogPointsData> = await unwrap<XLogEnvelope>(
    api().client.GET('/api/v1/apps/{appId}/xlog', {
      params: { path: { appId }, query },
      signal,
    }),
  );
  return { data: result.data, meta: result.meta };
}

/** `GET /apps/{appId}/events` of [from, to) (≤ 1,000 rows; docs/14 §4.12). */
export async function fetchAppEvents(
  appId: number,
  range: { readonly from: number; readonly to: number },
  signal?: AbortSignal,
): Promise<{ readonly events: readonly AppEvent[]; readonly meta: ResponseMeta }> {
  const result = await unwrap<EventsEnvelope>(
    api().client.GET('/api/v1/apps/{appId}/events', {
      params: {
        path: { appId },
        query: { from: new Date(range.from).toISOString(), to: new Date(range.to).toISOString() },
      },
      signal,
    }),
  );
  return { events: result.data, meta: result.meta };
}
