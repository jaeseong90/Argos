// SPDX-License-Identifier: Apache-2.0
//
// Links out of the trace view (docs/15 §9.5, 05 §2.8-4): a node opens its transaction detail on the
// canonical route with the `ts` hint (ADR-0010 C-166, ADR-0007 C-50) and the carried keys of the
// trace URL (the range it was opened with), an application opens its
// dashboard with the time range the trace view shows (the searched window of the response, as an
// absolute range, live off), keeping the carried keys of docs/15 §5 (kind, environment, kiosk).
// 64-bit ids stay decimal strings (C-175).
import {
  buildUrl,
  CommonQ,
  carriedState,
  defaultState,
  parseInstant,
  TxDetailQ,
  type CommonState,
  type TxDetailState,
} from '../../../state/urlState';

/**
 * The detail route of a transaction (C-166) with the ts hint (C-50) when the instant is valid, the
 * tab when it is not the default, and the carried keys of the trace view's URL (docs/15 §5: the
 * time range the trace was opened with, environment, kiosk; live off). A detail opened from a trace
 * that was opened from a detail therefore keeps that detail's range for its own links. The kind is
 * left to the transaction itself (the nodes of one trace can be of different kinds).
 */
export function transactionDetailHref(
  appId: number,
  txId: string,
  ts: string,
  current: Partial<CommonState> = {},
  tab: TxDetailState['tab'] = 'timeline',
): string {
  const path = `/apps/${String(appId)}/transactions/${encodeURIComponent(txId)}`;
  const ms = parseInstant(ts) ?? Date.parse(ts);
  const defaults = defaultState(TxDetailQ);
  return buildUrl(path, TxDetailQ, {
    ...defaults,
    ...carriedState(current),
    kind: defaults.kind,
    live: false,
    ts: Number.isFinite(ms) && ms > 0 ? ms : undefined,
    tab,
  });
}

/** The time range of the trace view: the searched window of the response, in epoch ms. */
export interface TraceRange {
  readonly fromMs: number;
  readonly toMs: number;
}

/** The searched window of a response as epoch ms, or null when it cannot be read. */
export function traceRange(window: {
  readonly from: string;
  readonly to: string;
}): TraceRange | null {
  const fromMs = Date.parse(window.from);
  const toMs = Date.parse(window.to);
  return Number.isFinite(fromMs) && Number.isFinite(toMs) && fromMs < toMs
    ? { fromMs, toMs }
    : null;
}

/**
 * The dashboard of an application for the same time range (05 §2.8-4): an absolute range, live
 * off, with the carried keys of the trace view's URL. Without a readable range: the dashboard with
 * the carried keys only.
 */
export function appDashboardHref(
  appId: number,
  range: TraceRange | null,
  current: Partial<CommonState>,
): string {
  const base: CommonState = { ...defaultState(CommonQ), ...carriedState(current) };
  const state: CommonState =
    range === null
      ? base
      : { ...base, time: { kind: 'absolute', from: range.fromMs, to: range.toMs }, live: false };
  return buildUrl(`/apps/${String(appId)}/dashboard`, CommonQ, state);
}
