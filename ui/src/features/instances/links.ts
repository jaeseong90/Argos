// SPDX-License-Identifier: Apache-2.0
//
// Links out of the instance detail (05 §2.7-6, docs/15 §5 carried keys): this instance's XLog
// (filtered by `f.instance`, points coloured by instance, the T-ACPT-14 switch), its active
// transactions (`f.instance`), the alert history of the application (the alert API has no target
// filter, docs/14 §4.13) and the configuration change history of the application
// (`/admin/config?app=`, WO-39). Time range, live, kind, environment and kiosk are carried.
import { untrusted } from '../../api/untrusted';
import {
  AlertsQ,
  buildUrl,
  carriedQuery,
  carriedState,
  CommonQ,
  defaultState,
  InstanceQ,
  XLogQ,
  type CommonState,
  type Filter,
} from '../../state/urlState';

export type CarriedState = Pick<CommonState, 'time' | 'live' | 'kind' | 'env' | 'kiosk'>;

function instanceFilter(instanceId: number): Filter {
  return { field: 'instance', op: 'eq', value: untrusted(String(instanceId)) };
}

/** The XLog of this instance: `f.instance=<id>&color=instance`. */
export function instanceXLogUrl(appId: number, instanceId: number, current: CarriedState): string {
  return buildUrl(`/apps/${String(appId)}/xlog`, XLogQ, {
    ...defaultState(XLogQ),
    ...carriedState(current),
    filters: [instanceFilter(instanceId)],
    color: 'instance',
  });
}

/** The running transactions of this instance: `/apps/<app>/active?f.instance=<id>`. */
export function instanceActiveUrl(
  appId: number,
  instanceId: number,
  current: CarriedState,
): string {
  return buildUrl(`/apps/${String(appId)}/active`, CommonQ, {
    ...defaultState(CommonQ),
    ...carriedState(current),
    filters: [instanceFilter(instanceId)],
  });
}

/** The alert history of the application over the same range (`/alerts?app=<id>`). */
export function instanceAlertsUrl(appId: number, current: CarriedState): string {
  return buildUrl('/alerts', AlertsQ, {
    ...defaultState(AlertsQ),
    ...carriedState({ time: current.time, kind: current.kind, env: current.env }),
    app: appId,
  });
}

/** The configuration change history of the application (`/admin/config?app=<id>`, WO-39). */
export function configHistoryUrl(appId: number, current: CarriedState): string {
  const carried = carriedQuery(current);
  const app = `app=${String(appId)}`;
  return `/admin/config?${carried === '' ? app : `${carried}&${app}`}`;
}

/** The instance detail itself (another application id keeps the rest of the query). */
export function instanceUrl(
  appId: number,
  instanceId: number,
  current: CarriedState,
  tab: 'metrics' | 'env' | 'commands' | 'dumps' = 'metrics',
): string {
  return buildUrl(`/apps/${String(appId)}/instances/${String(instanceId)}`, InstanceQ, {
    ...defaultState(InstanceQ),
    ...carriedState(current),
    tab,
  });
}
