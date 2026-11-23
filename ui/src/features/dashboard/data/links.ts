// SPDX-License-Identifier: Apache-2.0
//
// Links out of the dashboard (05 §2.0-5, docs/15 §9.2): a chart point opens the XLog around that
// time (± range/60, at least 1 minute, computed by TimeSeriesChart's click menu) as an absolute
// range with live off, keeping the transaction kind and the environment (docs/15 §5 carried keys).
import type { TimeRange } from '../../../charts/chartModel';
import {
  AlertsQ,
  buildUrl,
  carriedState,
  defaultState,
  InstanceQ,
  XLogQ,
  type AlertsState,
  type CommonState,
} from '../../../state/urlState';

/** Ids the alert deep link accepts (docs/14 §6 `alert=`, CommonQ). */
const ALERT_ID = /^[0-9A-Za-z_-]{1,64}$/;

/**
 * The instance detail (WO-37) of an instance tile (05 §2.3-3), keeping the range, live, kind,
 * environment and kiosk (docs/15 §5 carried keys, 05 §2.0-1).
 */
export function instanceDetailUrl(
  appId: number,
  instanceId: number,
  current: Pick<CommonState, 'time' | 'live' | 'kind' | 'env' | 'kiosk'>,
): string {
  return buildUrl(`/apps/${String(appId)}/instances/${String(instanceId)}`, InstanceQ, {
    ...defaultState(InstanceQ),
    ...carriedState(current),
  });
}

/**
 * The alert history (WO-38) of this application and environment over the dashboard's range
 * (05 §2.3-5). With an alert event id it opens that alert (the `alert=` deep link, live off).
 */
export function alertHistoryUrl(
  appId: number,
  current: Pick<CommonState, 'time' | 'kind' | 'env'>,
  alertId: string | null = null,
): string {
  const state: AlertsState = {
    ...defaultState(AlertsQ),
    ...carriedState({ time: current.time, kind: current.kind, env: current.env }),
    app: appId,
  };
  const deepLink = alertId !== null && ALERT_ID.test(alertId);
  return buildUrl('/alerts', AlertsQ, deepLink ? { ...state, alert: alertId, live: false } : state);
}

/** The XLog of an application over `range` (absolute, live off), keeping kind and environment. */
export function xlogAtRange(
  appId: number,
  range: TimeRange,
  current: Pick<CommonState, 'kind' | 'env'>,
): string {
  return buildUrl(`/apps/${String(appId)}/xlog`, XLogQ, {
    ...defaultState(XLogQ),
    ...carriedState({ kind: current.kind, env: current.env }),
    time: { kind: 'absolute', from: Math.floor(range.from), to: Math.ceil(range.to) },
    live: false,
  });
}
