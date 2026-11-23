// SPDX-License-Identifier: Apache-2.0
//
// What the trace response says about missing and partial data (docs/14 §4.7, docs/15 §9.5, D-05,
// D-07), as plain values the trace view renders. Nothing is reconstructed on the client: missing
// nodes are the server's `missing` and `external_nodes`, the critical path is `critical_path`, the
// network/wait part is `caller_step.network_wait_ms`.
import { isApiError } from '../../../api/errors';

import type { ResponseMeta, Trace, TraceMissing, TraceNode } from './traceApi';

/** Transactions per trace response (docs/14 §4.7); more are cut and `meta.truncated` is set. */
export const TRACE_NODE_LIMIT = 500;

/**
 * Why a trace was not found: `not_in_window` is the server's answer when the searched range holds
 * none of its transactions (widen the window or add a ts hint); `not_found` any other 404.
 */
export type TraceNotFoundReason = 'not_in_window' | 'not_found';

/** The not-found reason of a failed trace call, or null when the error is not a 404. */
export function traceNotFoundReason(error: unknown): TraceNotFoundReason | null {
  if (!isApiError(error) || error.status !== 404) {
    return null;
  }
  return error.hasDetail('not_in_window', 'trace_id') ? 'not_in_window' : 'not_found';
}

/** True when the response was cut at TRACE_NODE_LIMIT transactions (show the partial banner). */
export function traceTruncated(meta: ResponseMeta): boolean {
  return meta.truncated === true;
}

/**
 * Missing node kinds of 05 §2.8-3: an uninstrumented system (external node), a transaction not
 * collected yet or lost (`not_yet_collected`: "not collected yet [query again]"), and a node whose
 * profile was not captured (the D-07 reason of its `profile_state`).
 */
export type MissingKind = 'not_instrumented' | 'not_yet_collected';

export function missingKind(missing: TraceMissing): MissingKind {
  return missing.reason;
}

/** Profile state of a node (`profile_state`, ADR-0010 C-154); unknown values are `unknown`. */
export type NodeProfileState = 'captured' | 'sampled_out' | 'lost' | 'unknown';

export function nodeProfileState(node: TraceNode): NodeProfileState {
  const raw = node.profile_state;
  return raw === 'captured' || raw === 'sampled_out' || raw === 'lost' ? raw : 'unknown';
}

/**
 * The network and waiting time between a caller's HTTP step and this node (D-05), from the server's
 * `caller_step`; null for a root node or when the caller step is not known.
 */
export function networkWaitMs(node: TraceNode): number | null {
  return node.caller_step === null ? null : node.caller_step.network_wait_ms;
}

/** The set of tx ids on the critical path (`critical_path`), for the bold outline. */
export function criticalPathSet(trace: Trace): ReadonlySet<string> {
  return new Set(trace.critical_path);
}
