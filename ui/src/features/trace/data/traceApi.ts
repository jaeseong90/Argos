// SPDX-License-Identifier: Apache-2.0
//
// Distributed trace call of docs/14 §4.7 through the generated client (03 §3.3): GET
// /traces/{traceId}?ts=&window= (nodes, external nodes, missing nodes, critical path; at most 500
// transactions, meta.truncated). Types come from the contract through EnvelopeOf, so collected
// strings keep their Untrusted brand (SEC-60) and 64-bit tx ids stay decimal strings (C-175).
//
// Trace JSON export (ADR-0010 C-207 (1)): the response already received is saved as a file through
// the one download helper saveFile(); the trace response holds no personal fields (no user id, no
// IP), so it is not audited. If personal fields are ever added, this must become a server export.
import {
  api,
  unwrap,
  type Enveloped,
  type EnvelopeOf,
  type ResponseMeta,
} from '../../../api/client';
import { saveFile, type SaveFileEnvironment } from '../../../api/exportCsv';
import type { TRACE_WINDOWS } from '../../../state/urlState';

type TraceEnvelope = EnvelopeOf<'/api/v1/traces/{traceId}', 'get'>;

/** docs/14 §4.7 trace (Trace of docs/api/openapi.yaml). */
export type Trace = TraceEnvelope['data'];
/** A transaction of the trace (app, env, service, instance, elapsed, status, caller step, …). */
export type TraceNode = Trace['nodes'][number];
/** The caller's HTTP step of a node: its elapsed time and the network/wait part (D-05). */
export type TraceCallerStep = NonNullable<TraceNode['caller_step']>;
/** An outgoing call to an uninstrumented system (missing kind 1: "external node"). */
export type TraceExternalNode = Trace['external_nodes'][number];
/** A transaction the trace refers to but did not return (`not_instrumented` / `not_yet_collected`). */
export type TraceMissing = Trace['missing'][number];
/** The searched range and its basis (window, ts hint, id time, default). */
export type TraceWindowRange = Trace['window'];
/** `window` values of docs/14 §4.7 (30d counts against the HEAVY bucket). */
export type TraceWindow = (typeof TRACE_WINDOWS)[number];

export type { ResponseMeta };

/** trace_id: 32 hex digits (W3C), not all zeros (server Ids.traceId). */
const TRACE_ID = /^[0-9a-f]{32}$/i;

/** True when the text has the form the server accepts as a trace id. */
export function isTraceIdForm(traceId: string): boolean {
  return TRACE_ID.test(traceId) && !/^0+$/.test(traceId);
}

/**
 * GET /traces/{traceId} (docs/14 §4.7). `ts` (ISO-8601 UTC) searches ±1 h around it; `window`
 * searches that long until now; neither: the time in an Argos id ±1 h, else the last 24 h. Throws
 * an ApiError (404 not_found with detail trace_id/not_in_window when the range holds none; 429 for
 * a 30d window over the HEAVY bucket).
 */
export function fetchTrace(
  traceId: string,
  options: { readonly ts?: string; readonly window?: TraceWindow } = {},
  signal?: AbortSignal,
): Promise<Enveloped<Trace>> {
  const query: { ts?: string; window?: TraceWindow } = {};
  if (options.ts !== undefined) {
    query.ts = options.ts;
  }
  if (options.window !== undefined) {
    query.window = options.window;
  }
  return unwrap<TraceEnvelope>(
    api().client.GET('/api/v1/traces/{traceId}', {
      params: { path: { traceId }, query },
      signal,
    }),
  );
}

/** File name of a saved trace (`argos-trace-<trace id>.json`, the form of the server's exports). */
export function traceFileName(traceId: string): string {
  return `argos-trace-${traceId.toLowerCase()}.json`;
}

/**
 * Saves the received trace response ({data, meta}, as the server sent it) as a JSON file
 * (ADR-0010 C-207 (1)). Returns the file name. No request is made.
 */
export function saveTraceJson(response: Enveloped<Trace>, env: SaveFileEnvironment = {}): string {
  const fileName = traceFileName(response.data.trace_id);
  const body = JSON.stringify({ data: response.data, meta: response.meta }, null, 2);
  saveFile(new Blob([body], { type: 'application/json' }), fileName, env);
  return fileName;
}
