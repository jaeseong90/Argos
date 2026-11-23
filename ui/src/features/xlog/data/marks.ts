// SPDX-License-Identifier: Apache-2.0
//
// Overlay marks of the XLog screen from the API: event markers from `GET /apps/{appId}/events`
// (docs/14 §4.12, docs/15 §6.1 shapes) and collection outage shading from `meta.outages` of the
// points response (docs/14 §2.5, §4.5; ADR-0010 C-205 (2): real outages appear once WO-22 records
// instance state events). Titles and versions stay collected strings (rendered with SafeText).
import type { ResponseMeta } from '../../../api/client';
import type { ChartMarker, ChartOutage, MarkerType } from '../../../charts/chartModel';

import type { AppEvent } from './xlogApi';

const MARKER_TYPES: ReadonlySet<string> = new Set([
  'deploy',
  'start',
  'stop',
  'config',
  'alert',
  'state',
  'apdex',
  'custom',
]);

function markerType(type: string): MarkerType {
  return MARKER_TYPES.has(type) ? (type as MarkerType) : 'custom';
}

/** Markers of the events; an event with an unparsable time is left out. */
export function eventMarkers(events: readonly AppEvent[]): ChartMarker[] {
  const markers: ChartMarker[] = [];
  for (const event of events) {
    const ts = Date.parse(event.ts);
    if (!Number.isFinite(ts)) {
      continue;
    }
    markers.push({
      id: event.event_id,
      ts,
      type: markerType(event.type),
      title: event.title,
      version: event.version,
      severity: null,
    });
  }
  return markers;
}

/** Outages of a response's meta (C-5): `to` missing = still ongoing (null). */
export function metaOutages(meta: ResponseMeta | null | undefined): ChartOutage[] {
  const outages: ChartOutage[] = [];
  for (const outage of meta?.outages ?? []) {
    const from = Date.parse(outage.from);
    const to = outage.to === undefined ? null : Date.parse(outage.to);
    if (!Number.isFinite(from) || (to !== null && !Number.isFinite(to))) {
      continue;
    }
    outages.push({ from, to, reason: outage.reason });
  }
  return outages;
}
