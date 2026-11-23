// SPDX-License-Identifier: Apache-2.0
//
// Alert part of a chart screen (docs/15 §4.3 item 4, ADR-0010 C-226 (8)): loads the alert
// transitions of the chart range (`GET /alerts/events`, api/alertMarkers.ts), hands them to the
// screen as chart markers (onMarkers; the screen merges them with its event markers and emphasises
// `alert=`), and renders the summary banner of the `alert=` event and the truncation note. The XLog
// loads this module lazily (its route is on the initial path, C-218 (1)); the dashboard imports it.
import { useEffect } from 'react';

import { findAlertEvent, useAlertMarkers, type AlertMarkerRequest } from '../api/alertMarkers';
import type { ChartMarker } from '../charts/chartModel';

import { AlertBanner, AlertMarkersTruncated } from './AlertBanner';

const NO_MARKERS: readonly ChartMarker[] = [];

export interface AlertOverlayProps {
  /** Application, environment and chart range of the markers. */
  readonly request: AlertMarkerRequest;
  readonly enabled: boolean;
  /** The `alert=` key of the URL (banner), or undefined. */
  readonly alertId: string | undefined;
  /** The alert markers of the current answer (an empty list while loading or after a failure). */
  readonly onMarkers: (markers: readonly ChartMarker[]) => void;
}

export function AlertOverlay({ request, enabled, alertId, onMarkers }: AlertOverlayProps) {
  const query = useAlertMarkers(request, enabled);
  const markers = query.data?.markers ?? NO_MARKERS;
  useEffect(() => {
    onMarkers(markers);
  }, [markers, onMarkers]);
  return (
    <>
      {alertId === undefined ? null : (
        <AlertBanner
          eventId={alertId}
          event={findAlertEvent(query.data?.events, alertId)}
          loading={query.isPending && query.fetchStatus !== 'idle'}
          failed={query.isError}
        />
      )}
      {query.data?.truncated === true ? (
        <AlertMarkersTruncated count={query.data.events.length} />
      ) : null}
    </>
  );
}
