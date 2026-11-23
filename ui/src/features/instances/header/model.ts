// SPDX-License-Identifier: Apache-2.0
//
// Pure parts of the instance header (05 §2.7-1): uptime, restarts in the selected range, agent
// version compatibility, IP capture and virtual-thread modes, clock skew warning. Missing values
// stay null (the header shows the D-23 dash), never 0.
import type { Instance, InstanceEvent } from '../../../api/instances';
import type { ChartMarker, MarkerType } from '../../../charts/chartModel';

/** Clock offset from which the server reports WARN (docs/12 §4.2: 1 s). */
export const CLOCK_SKEW_WARN_MS = 1000;

/** States in which the process runs, so that an uptime means something. */
const RUNNING_STATES: ReadonlySet<string> = new Set(['UP', 'WARN', 'STALE']);

/**
 * Milliseconds since the process started (agent clock `started_at`), while the instance runs;
 * null without a start time, for a DOWN instance or for a start time in the future.
 */
export function uptimeMs(instance: Instance, now: number): number | null {
  if (instance.started_at === null || !RUNNING_STATES.has(instance.state.toUpperCase())) {
    return null;
  }
  const started = Date.parse(instance.started_at);
  if (!Number.isFinite(started) || started > now) {
    return null;
  }
  return now - started;
}

/**
 * Restarts of the instance in the selected range: its `start` events (docs/14 §4.12, written when
 * an agent session of the instance begins). Null while the events are not loaded.
 */
export function restartCount(
  events: readonly InstanceEvent[] | null | undefined,
  instanceId: number,
): number | null {
  if (events === null || events === undefined) {
    return null;
  }
  return events.filter((event) => event.type === 'start' && event.instance_id === instanceId)
    .length;
}

export type Compat = 'ok' | 'old' | 'unsupported';

/** The agent version compatibility (`ok`, `old`, `unsupported`), null when unknown. */
export function compatOf(instance: Pick<Instance, 'compat'>): Compat | null {
  switch (instance.compat?.toLowerCase()) {
    case 'ok':
      return 'ok';
    case 'old':
      return 'old';
    case 'unsupported':
      return 'unsupported';
    default:
      return null;
  }
}

export type IpMode = 'full' | 'masked' | 'off';

/** The remote IP capture mode (docs/16 §6.3), null when unknown. */
export function ipModeOf(instance: Pick<Instance, 'ip_mode'>): IpMode | null {
  switch (instance.ip_mode?.toLowerCase()) {
    case 'full':
      return 'full';
    case 'masked':
      return 'masked';
    case 'off':
      return 'off';
    default:
      return null;
  }
}

/** The virtual-thread mode: 'on', 'off', or null when the agent did not say. */
export function virtualThreadMode(
  instance: Pick<Instance, 'virtual_threads'>,
): 'on' | 'off' | null {
  if (instance.virtual_threads === null) {
    return null;
  }
  return instance.virtual_threads ? 'on' : 'off';
}

/** True when the clock offset is large enough for the server's WARN (|skew| ≥ 1 s). */
export function clockSkewWarns(skewMs: number | null): boolean {
  return skewMs !== null && Math.abs(skewMs) >= CLOCK_SKEW_WARN_MS;
}

/** WARN reasons the header can derive itself (the API does not list them, docs/12 §4.2). */
export function warnReasons(instance: Instance): string[] {
  const reasons: string[] = [];
  if (clockSkewWarns(instance.clock_skew_ms)) {
    reasons.push('clock_skew');
  }
  if (compatOf(instance) === 'old') {
    reasons.push('old_version');
  }
  if ((instance.modules_disabled ?? []).length > 0) {
    reasons.push('module_disabled');
  }
  if (instance.config_drift === true) {
    reasons.push('config_drift');
  }
  if ((instance.drops_24h ?? 0) > 0) {
    reasons.push('drops');
  }
  return reasons;
}

/**
 * Chart markers of the instance screen (docs/15 §6.1): the application's deploys and this
 * instance's start, stop and config events; events of other instances are left out.
 */
export function instanceMarkers(
  events: readonly InstanceEvent[] | null | undefined,
  instanceId: number,
): ChartMarker[] {
  const markers: ChartMarker[] = [];
  for (const event of events ?? []) {
    const ts = Date.parse(event.ts);
    if (!Number.isFinite(ts)) {
      continue;
    }
    const type: MarkerType | null =
      event.type === 'deploy'
        ? 'deploy'
        : event.instance_id !== instanceId
          ? null
          : event.type === 'start' || event.type === 'stop' || event.type === 'config'
            ? event.type
            : null;
    if (type === null) {
      continue;
    }
    markers.push({ id: event.event_id, ts, type, title: event.title, version: event.version });
  }
  return markers;
}
