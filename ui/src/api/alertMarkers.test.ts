// SPDX-License-Identifier: Apache-2.0
//
// Alert transition markers of the charts (docs/15 §4.3 item 4, ADR-0010 C-226 (8)): the events
// request (app, env, range in whole minutes, one page of 500), the mapping to ChartMarker (type
// alert, id = event_id so `alert=` emphasises exactly that transition, severity colour), the
// truncation flag, the lookup of the `alert=` event and the merge with the event markers.
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { envelope, installApiStub, uninstallApiStub, type ApiStub } from '../../test/apiStub';
import { groupMarkers } from '../charts/chartModel';

import {
  ALERT_MARKER_LIMIT,
  alertMarker,
  alertMarkers,
  alertMarkersKey,
  fetchAlertMarkers,
  findAlertEvent,
  markerRange,
  mergeMarkers,
  type AlertEvent,
} from './alertMarkers';
import { untrusted } from './untrusted';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U09' };

const ROUTE = 'GET /api/v1/alerts/events';

function event(id: string, ts: string, overrides: Partial<AlertEvent> = {}): AlertEvent {
  return {
    event_id: id,
    ts,
    app: { id: 1, name: untrusted('order-api') },
    env: untrusted('prod'),
    target: { type: 'app', id: null, name: null },
    rule: { id: 'r-1', name: untrusted('Error rate'), metric: 'error_rate', unit: 'ratio' },
    severity: 'warn',
    from_state: 'OK',
    state: 'FIRING',
    reason: 'condition',
    value: 0.07,
    threshold: 0.05,
    duration_s: null,
    acked_by: null,
    acked_at: null,
    note: null,
    deliveries: [],
    deeplink: '/apps/1/dashboard',
    ...overrides,
  };
}

let stub: ApiStub;

beforeEach(() => {
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
});

describe('alert markers (C-226 (8))', () => {
  it('maps each transition to an alert marker with the event id and severity', () => {
    const marker = alertMarker(
      event('ev-2', '2026-11-20T03:05:00Z', {
        severity: 'critical',
        from_state: 'FIRING',
        state: 'ACKED',
      }),
    );
    expect(marker).toEqual({
      id: 'ev-2',
      ts: Date.parse('2026-11-20T03:05:00Z'),
      type: 'alert',
      title: 'Error rate · FIRING → ACKED',
      severity: 'critical',
    });
    expect(alertMarker(event('bad', 'not a time'))).toBeNull();
    // Oldest first for the charts (the API answers newest first).
    expect(
      alertMarkers([event('b', '2026-11-20T03:05:00Z'), event('a', '2026-11-20T03:01:00Z')]).map(
        (m) => m.id,
      ),
    ).toEqual(['a', 'b']);
  });

  it('asks the application, environment and whole-minute range with one page of 500', async () => {
    stub.on(ROUTE, () => envelope([event('ev-1', '2026-11-20T03:04:00Z')], { next_cursor: null }));
    const result = await fetchAlertMarkers({
      appId: 1,
      env: 'prod',
      range: { from: Date.parse('2026-11-20T03:00:00Z'), to: Date.parse('2026-11-20T03:10:00Z') },
    });
    const query = Object.fromEntries(stub.calls(ROUTE)[0]?.query.entries() ?? []);
    expect(query).toEqual({
      app: '1',
      env: 'prod',
      from: '2026-11-20T03:00:00.000Z',
      to: '2026-11-20T03:10:00.000Z',
      limit: String(ALERT_MARKER_LIMIT),
    });
    expect(result.markers.map((m) => m.id)).toEqual(['ev-1']);
    expect(result.truncated).toBe(false);
  });

  it('reports more transitions than one page as truncated and leaves out an empty env', async () => {
    stub.on(ROUTE, () => envelope([event('ev-1', '2026-11-20T03:04:00Z')], { next_cursor: 'c2' }));
    const result = await fetchAlertMarkers({
      appId: 1,
      env: null,
      range: { from: 0, to: 60_000 },
    });
    expect(result.truncated).toBe(true);
    expect(stub.calls(ROUTE)[0]?.query.has('env')).toBe(false);
  });

  it('rounds the range to whole minutes so a moving live window keeps its key', () => {
    expect(markerRange({ from: 61_000, to: 119_000 })).toEqual({ from: 60_000, to: 120_000 });
    expect(
      alertMarkersKey({ appId: 1, env: 'prod', range: { from: 61_000, to: 119_000 } }),
    ).toEqual(alertMarkersKey({ appId: 1, env: 'prod', range: { from: 65_000, to: 110_000 } }));
    // Under the `alerts` prefix: an acknowledgement refreshes the markers too.
    expect(alertMarkersKey({ appId: 1, range: { from: 0, to: 0 } })[0]).toBe('alerts');
  });

  it('finds the alert= event and lets it lead its marker group (emphasis)', () => {
    const events = [event('ev-1', '2026-11-20T03:04:00Z'), event('ev-2', '2026-11-20T03:04:01Z')];
    expect(findAlertEvent(events, 'ev-2')?.event_id).toBe('ev-2');
    expect(findAlertEvent(events, 'nope')).toBeNull();
    expect(findAlertEvent(undefined, 'ev-2')).toBeNull();
    const merged = mergeMarkers(
      [{ id: 'deploy-1', ts: Date.parse('2026-11-20T03:04:00Z'), type: 'deploy' }],
      alertMarkers(events),
    );
    expect(merged.map((m) => m.id)).toEqual(['deploy-1', 'ev-1', 'ev-2']);
    const groups = groupMarkers(merged, () => 50, 100, 'ev-2');
    expect(groups).toHaveLength(1);
    expect(groups[0]?.lead.id).toBe('ev-2');
    expect(groups[0]?.emphasized).toBe(true);
  });
});
