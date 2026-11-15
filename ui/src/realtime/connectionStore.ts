// SPDX-License-Identifier: Apache-2.0
//
// Connection state of the WebSocket (docs/15 §10): status, time of the last server message and
// counters for dropped frames. Transient (Zustand), never shared through the URL.
import { create } from 'zustand';

export type ConnectionStatus = 'idle' | 'connecting' | 'open' | 'closed';

export interface ConnectionState {
  readonly status: ConnectionStatus;
  /** Epoch ms of the last data frame (ack, push, notice; not pong), or null before the first. */
  readonly lastMessageAt: number | null;
  /** Active subscriptions (stale badge and polling fallback only matter with some). */
  readonly subscriptions: number;
  /** Epoch ms since when there is at least one subscription, or null. */
  readonly activeSince: number | null;
  /** Server frames that failed validation and were dropped (docs/15 §10). */
  readonly invalidFrames: number;
  /** Items the server dropped for backpressure (notice backpressure, docs/14 §5.3). */
  readonly serverDropped: number;
  /** Last measured ping round trip in ms. */
  readonly rttMs: number | null;
  /** Subscriptions refused on the client (limit of 20, SEC-44). */
  readonly refusedSubscriptions: number;
}

export const INITIAL_CONNECTION_STATE: ConnectionState = {
  status: 'idle',
  lastMessageAt: null,
  subscriptions: 0,
  activeSince: null,
  invalidFrames: 0,
  serverDropped: 0,
  rttMs: null,
  refusedSubscriptions: 0,
};

export const useConnectionStore = create<ConnectionState>()(() => INITIAL_CONNECTION_STATE);
