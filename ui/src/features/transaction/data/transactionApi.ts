// SPDX-License-Identifier: Apache-2.0
//
// Transaction detail calls of docs/14 §4.6 through the generated client (03 §3.3): the detail
// (`transactions_get`, ?ts hint of ADR-0007 C-50), the service baseline (`transactions_baseline`)
// and the server JSON export (`transactions_export`, audited as EXPORT_CSV format json and
// SENSITIVE_VIEW, ADR-0010 C-176 (1)). Types come from the generated contract through EnvelopeOf, so
// collected strings keep their Untrusted brand (SEC-60) and 64-bit ids stay decimal strings
// (C-175). Nothing here rebuilds or re-serializes server data.
import {
  api,
  unwrap,
  type Enveloped,
  type EnvelopeOf,
  type ResponseMeta,
} from '../../../api/client';
import { networkError, toApiError } from '../../../api/errors';
import type { SaveFileEnvironment } from '../../../api/exportCsv';

type DetailEnvelope = EnvelopeOf<'/api/v1/transactions/{txId}', 'get'>;
type BaselineEnvelope = EnvelopeOf<'/api/v1/transactions/{txId}/baseline', 'get'>;

/** docs/14 §4.6 transaction detail (TransactionDetail of docs/api/openapi.yaml). */
export type TransactionDetail = DetailEnvelope['data'];
/** The profile part (state, steps, self time, params mode, truncation). */
export type TransactionProfile = TransactionDetail['profile'];
/** One profile step (fields that do not apply to its type are absent). */
export type TransactionStep = TransactionProfile['steps'][number];
/** The summary of a SUMMARY step (ADR-0010 C-141). */
export type TransactionStepSummary = NonNullable<TransactionStep['summary']>;
/** The calling transaction of a distributed call (`caller.tx_id`, ADR-0010 C-207 (2)). */
export type TransactionCaller = NonNullable<TransactionDetail['caller']>;
/** Server-built links (`links.trace`, `links.logs`: null unless the log link template is set). */
export type TransactionLinks = TransactionDetail['links'];
/** docs/14 §4.6 baseline: same-service p50/p95, percentile position, one normal sample. */
export type TransactionBaseline = BaselineEnvelope['data'];

export type { ResponseMeta };

/** tx_id: an unsigned 64-bit decimal string (C-175, the server's `^[0-9]{1,20}$`). */
export const TX_ID_PATTERN = /^[0-9]{1,20}$/;

/** True when the text has the form the server accepts as a tx_id (the server checks the range). */
export function isTxIdForm(txId: string): boolean {
  return TX_ID_PATTERN.test(txId);
}

function hintQuery(ts: string | undefined): { ts?: string } {
  return ts === undefined ? {} : { ts };
}

/**
 * GET /transactions/{txId} (docs/14 §4.6). `ts` is the start hint (ISO-8601 UTC) of the link or the
 * URL; without it the server restores the time from the id (C-50). Throws an ApiError (404
 * not_found with detail tx_id/clock_skew_possible when the transaction is not near its time).
 */
export function fetchTransaction(
  txId: string,
  ts?: string,
  signal?: AbortSignal,
): Promise<Enveloped<TransactionDetail>> {
  return unwrap<DetailEnvelope>(
    api().client.GET('/api/v1/transactions/{txId}', {
      params: { path: { txId }, query: hintQuery(ts) },
      signal,
    }),
  );
}

/**
 * GET /transactions/{txId}/baseline (docs/14 §4.6): p50/p95 of the same service within ±30 minutes,
 * the percentile of this transaction and a normal sample (same instance first). The transaction is
 * found as by fetchTransaction, so pass the same `ts` hint (the detail's own `ts` once known).
 */
export function fetchTransactionBaseline(
  txId: string,
  ts?: string,
  signal?: AbortSignal,
): Promise<Enveloped<TransactionBaseline>> {
  return unwrap<BaselineEnvelope>(
    api().client.GET('/api/v1/transactions/{txId}/baseline', {
      params: { path: { txId }, query: hintQuery(ts) },
      signal,
    }),
  );
}

/**
 * The path of the server JSON export (GET /transactions/{txId}/export, docs/14 §4.6) for a download
 * anchor: `<a href={safeHref(exportHref(txId, ts))} download>`. The browser saves the server's
 * Content-Disposition file; the client never re-serializes the detail. A plain anchor does not go
 * through the transport (no refresh on 401, no Retry-After hint on 429), so a button should call
 * downloadTransactionExport() on click and keep this path as the anchor's href.
 */
export function exportHref(txId: string, ts?: string): string {
  const path = `/api/v1/transactions/${encodeURIComponent(txId)}/export`;
  return ts === undefined ? path : `${path}?ts=${encodeURIComponent(ts)}`;
}

/** The generated client's GET of the export, asked for the raw bytes. */
interface ExportResult {
  readonly data?: ArrayBuffer;
  readonly error?: unknown;
  readonly response: Response;
}

/**
 * Downloads the server JSON export through the shared transport (cookies, one refresh on 401) and
 * saves the server's bytes unchanged under the Content-Disposition name (saveFile, `.json`).
 * Returns the saved file name. Throws an ApiError: 429 rate_limited with `retryAfterSeconds` (HEAVY
 * bucket, C-176: show the hint, never retry in a loop), 404, network. Nothing is saved on an error.
 */
export async function downloadTransactionExport(
  txId: string,
  ts?: string,
  env: SaveFileEnvironment = {},
): Promise<string> {
  let result: ExportResult;
  try {
    result = await api().client.GET('/api/v1/transactions/{txId}/export', {
      params: { path: { txId }, query: hintQuery(ts) },
      parseAs: 'arrayBuffer',
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error;
    }
    throw networkError();
  }
  const { response } = result;
  if (!response.ok || result.data === undefined) {
    throw toApiError(response, result.error);
  }
  // Loaded on click, like the CSV export (ADR-0010 C-209).
  const { jsonFileName, saveFile } = await import('../../../api/exportCsv');
  const fileName = jsonFileName(response.headers.get('Content-Disposition'));
  saveFile(new Blob([result.data], { type: 'application/json' }), fileName, env);
  return fileName;
}
