// SPDX-License-Identifier: Apache-2.0
//
// XLog points from the API into the canvas buffer (docs/15 §7.1: columns go straight into the
// XLogBuffer TypedArrays, never row objects). The input is the columnar `data` of
// `GET /apps/{appId}/xlog` (docs/14 §4.5) or of an `xlog` WebSocket push (docs/14 §5.2, same
// fields). Only the API's columns are used (ADR-0010 C-202): HTTP status and error class are not
// point columns, so the buffer's httpStatus/errorClass columns stay 0 here.
import { XLogBuffer, type XLogColumns } from '../../../xlog/XLogBuffer';

import type { LocalDictionary } from './dictionary';
import { parseU64Into } from './u64';

/** The point columns shared by the REST response and the WebSocket push (docs/14 §4.5, §5.2). */
export interface XLogPointColumns {
  readonly tx_id: readonly string[];
  readonly ts: readonly number[];
  readonly elapsed_ms: readonly number[];
  readonly status: readonly number[];
  readonly instance_id: readonly number[];
  readonly service_id: readonly string[];
  readonly kind: readonly number[];
}

export interface AppendStats {
  /** Rows in the input (shortest column). */
  readonly rows: number;
  /** Rows written into the buffer. */
  readonly appended: number;
  /** Rows refused by `accept` (duplicate tx_id, late point). */
  readonly skipped: number;
  /** Rows with an invalid tx_id or a full dictionary. */
  readonly invalid: number;
  /** Time of the tx_id parse pass, ms. */
  readonly parseMs: number;
  /** Time of the whole conversion (parse + index lookup + copy into the buffer), ms. */
  readonly totalMs: number;
}

export interface AppendOptions {
  /**
   * Called for every valid row before it is written; false skips it (realtime de-duplication by
   * tx_id, D-15 late points, the scatter filters of a push). Receives the tx_id as the API sent it
   * (decimal string), the time and the row's index in `columns` (for its other fields).
   */
  readonly accept?: (txId: string, ts: number, row: number) => boolean;
  /** Clock for the timings (performance.now by default). */
  readonly now?: () => number;
}

const NONE = new Uint8Array(0);

function shortest(columns: XLogPointColumns): number {
  return Math.min(
    columns.tx_id.length,
    columns.ts.length,
    columns.elapsed_ms.length,
    columns.status.length,
    columns.instance_id.length,
    columns.service_id.length,
    columns.kind.length,
  );
}

/**
 * Appends the rows of `columns` to `buffer`, resolving service keys and instance ids through
 * `dictionary` (new keys are added). Mismatched column lengths are cut to the shortest column.
 */
export function appendPointColumns(
  buffer: XLogBuffer,
  dictionary: LocalDictionary,
  columns: XLogPointColumns,
  options: AppendOptions = {},
): AppendStats {
  const now = options.now ?? (() => performance.now());
  const started = now();
  const rows = shortest(columns);
  if (rows === 0) {
    return { rows: 0, appended: 0, skipped: 0, invalid: 0, parseMs: 0, totalMs: now() - started };
  }
  // Pass 1: tx_id halves (the costly part, measured on its own).
  const hi = new Uint32Array(rows);
  const lo = new Uint32Array(rows);
  const valid = new Uint8Array(rows);
  for (let i = 0; i < rows; i += 1) {
    const text = columns.tx_id[i];
    valid[i] = typeof text === 'string' && parseU64Into(text, hi, lo, i) ? 1 : 0;
  }
  const parsed = now();

  // Pass 2: accepted rows into compact columns, keys into local indices.
  const ts = new Float64Array(rows);
  const elapsed = new Float64Array(rows);
  const status = new Uint8Array(rows);
  const service = new Uint32Array(rows);
  const instance = new Uint16Array(rows);
  const kind = new Uint8Array(rows);
  const txIdHi = new Uint32Array(rows);
  const txIdLo = new Uint32Array(rows);
  let count = 0;
  let skipped = 0;
  let invalid = 0;
  const accept = options.accept;
  for (let i = 0; i < rows; i += 1) {
    if (valid[i] !== 1) {
      invalid += 1;
      continue;
    }
    const t = columns.ts[i] ?? Number.NaN;
    if (accept !== undefined && !accept(columns.tx_id[i] ?? '', t, i)) {
      skipped += 1;
      continue;
    }
    const s = dictionary.serviceIndex(columns.service_id[i] ?? '');
    const n = dictionary.instanceIndex(columns.instance_id[i] ?? 0);
    if (s < 0 || n < 0) {
      invalid += 1;
      continue;
    }
    ts[count] = t;
    elapsed[count] = columns.elapsed_ms[i] ?? Number.NaN;
    status[count] = columns.status[i] ?? 0;
    service[count] = s;
    instance[count] = n;
    kind[count] = columns.kind[i] ?? 0;
    txIdHi[count] = hi[i] ?? 0;
    txIdLo[count] = lo[i] ?? 0;
    count += 1;
  }
  if (count > 0) {
    const out: XLogColumns = {
      ts: ts.subarray(0, count),
      elapsed: elapsed.subarray(0, count),
      status: status.subarray(0, count),
      instance: instance.subarray(0, count),
      service: service.subarray(0, count),
      txIdLo: txIdLo.subarray(0, count),
      txIdHi: txIdHi.subarray(0, count),
      kind: kind.subarray(0, count),
      httpStatus: NONE,
      errorClass: NONE,
    };
    buffer.appendColumns(out);
  }
  const finished = now();
  return {
    rows,
    appended: count,
    skipped,
    invalid,
    parseMs: parsed - started,
    totalMs: finished - started,
  };
}
