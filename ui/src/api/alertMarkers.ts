// SPDX-License-Identifier: Apache-2.0
//
// Alert transition markers of the charts (docs/15 §4.3 item 4, §6.1; ADR-0010 C-226 (8)): the
// state transitions of the application's alerts in the chart range from `GET /alerts/events`
// (docs/14 §4.13; app, env, from, to, one page of at most 500, newest first). Each transition is a
// ChartMarker of type `alert` with id = event_id (the `alert=` deep-link key emphasises exactly
// that marker) in its severity colour. More transitions than one page are reported as truncated
// (the newest 500 are drawn). The `alert=` event itself is looked up in the same answer for the
// summary banner (AlertBanner). Rule and target names stay Untrusted (SafeText in the tooltip).
import { useQuery } from '@tanstack/react-query';

import type { ChartMarker } from '../charts/chartModel';

import { api, unwrap, type EnvelopeOf } from './client';
import type { components } from './generated/schema';

export type AlertEvent = components['schemas']['AlertEvent'];

type EventsEnvelope = EnvelopeOf<'/api/v1/alerts/events', 'get'>;

/** Most transitions drawn on a chart (one page of the events API). */
export const ALERT_MARKER_LIMIT = 500;

/** Marker queries use whole minutes, so a live window that moves does not re-query each push. */
export const ALERT_MARKER_STEP_MS = 60_000;

export interface AlertMarkerRequest {
  readonly appId: number;
  readonly env?: string | null | undefined;
  readonly range: { readonly from: number; readonly to: number };
}

export interface AlertMarkers {
  readonly markers: readonly ChartMarker[];
  readonly events: readonly AlertEvent[];
  /** More transitions in the range than one page: only the newest are drawn. */
  readonly truncated: boolean;
}

/** The request range in whole minutes (from rounded down, to rounded up). */
export function markerRange(range: { readonly from: number; readonly to: number }): {
  from: number;
  to: number;
} {
  return {
    from: Math.floor(range.from / ALERT_MARKER_STEP_MS) * ALERT_MARKER_STEP_MS,
    to: Math.ceil(range.to / ALERT_MARKER_STEP_MS) * ALERT_MARKER_STEP_MS,
  };
}

/** One transition as a chart marker; null when its time does not parse. */
export function alertMarker(event: AlertEvent): ChartMarker | null {
  const ts = Date.parse(event.ts);
  if (!Number.isFinite(ts)) {
    return null;
  }
  const from = event.from_state ?? '—';
  return {
    id: event.event_id,
    ts,
    type: 'alert',
    // The rule name is entered by users: the tooltip renders the title with SafeText.
    title: `${event.rule.name} · ${from} → ${event.state}`,
    severity: event.severity,
  };
}

/** Markers of the events (unparsable times left out), oldest first. */
export function alertMarkers(events: readonly AlertEvent[]): ChartMarker[] {
  const markers: ChartMarker[] = [];
  for (const event of events) {
    const marker = alertMarker(event);
    if (marker !== null) {
      markers.push(marker);
    }
  }
  return markers.sort((a, b) => a.ts - b.ts);
}

/** `GET /alerts/events` of the application and range, one page of 500. */
export async function fetchAlertMarkers(
  request: AlertMarkerRequest,
  signal?: AbortSignal,
): Promise<AlertMarkers> {
  const query: {
    from: string;
    to: string;
    app: number;
    env?: string;
    limit: number;
  } = {
    from: new Date(request.range.from).toISOString(),
    to: new Date(request.range.to).toISOString(),
    app: request.appId,
    limit: ALERT_MARKER_LIMIT,
  };
  if (request.env !== undefined && request.env !== null && request.env !== '') {
    query.env = request.env;
  }
  const result = await unwrap<EventsEnvelope>(
    api().client.GET('/api/v1/alerts/events', {
      params: { query },
      ...(signal === undefined ? {} : { signal }),
    }),
  );
  const nextCursor = result.meta.next_cursor;
  return {
    events: result.data,
    markers: alertMarkers(result.data),
    truncated:
      result.meta.truncated === true || (typeof nextCursor === 'string' && nextCursor !== ''),
  };
}

/** Query key of the markers (under the `alerts` prefix: an acknowledgement refreshes them). */
export function alertMarkersKey(request: AlertMarkerRequest) {
  const range = markerRange(request.range);
  return [
    'alerts',
    'markers',
    request.appId,
    request.env ?? null,
    new Date(range.from).toISOString(),
    new Date(range.to).toISOString(),
  ] as const;
}

/**
 * The alert transitions of a chart range (whole minutes). Disabled without an application. A
 * failure leaves the chart without alert markers (the chart itself still renders).
 */
export function useAlertMarkers(request: AlertMarkerRequest, enabled = true) {
  const range = markerRange(request.range);
  return useQuery({
    queryKey: alertMarkersKey(request),
    queryFn: ({ signal }) => fetchAlertMarkers({ ...request, range }, signal),
    enabled: enabled && request.appId > 0,
    staleTime: 60_000,
  });
}

/** The event with `eventId` in the answer, or null. */
export function findAlertEvent(
  events: readonly AlertEvent[] | undefined,
  eventId: string | null | undefined,
): AlertEvent | null {
  if (events === undefined || eventId === null || eventId === undefined) {
    return null;
  }
  return events.find((event) => event.event_id === eventId) ?? null;
}

/** Chart markers merged with the alert markers (the event markers first). */
export function mergeMarkers(
  events: readonly ChartMarker[],
  alerts: readonly ChartMarker[] | undefined,
): readonly ChartMarker[] {
  return alerts === undefined || alerts.length === 0 ? events : [...events, ...alerts];
}
