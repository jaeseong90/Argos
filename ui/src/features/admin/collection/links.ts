// SPDX-License-Identifier: Apache-2.0
//
// Link into the collection health ranking (ADR-0010 C-211 (2)): the dashboard's "N excluded" note
// opens `/admin/instances?tab=collection&app=<id>` with the same time range, kind and environment
// (live and kiosk are not carried: the administration screens have neither).
import { AdminInstancesQ, buildUrl, defaultState, type CommonState } from '../../../state/urlState';

export function collectionRankingUrl(
  appId: number,
  current: Pick<CommonState, 'time' | 'kind' | 'env'>,
): string {
  return buildUrl('/admin/instances', AdminInstancesQ, {
    ...defaultState(AdminInstancesQ),
    time: current.time,
    kind: current.kind,
    env: current.env,
    app: appId,
    tab: 'collection',
    sort: 'excluded:desc',
  });
}
