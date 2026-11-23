// SPDX-License-Identifier: Apache-2.0
//
// Links of the command screens (WO-37): the dumps tab showing one dump (from the commands tab and
// the active transactions screen's dump action) and the audit screen filtered to an instance
// (WO-28/WO-39 owns that screen). A module of its own so that the active screen does not load the
// commands tab to build a link.
import { untrusted } from '../../../api/untrusted';
import {
  buildUrl,
  carriedState,
  CommonQ,
  defaultState,
  InstanceQ,
  type CommonState,
} from '../../../state/urlState';

/**
 * The dumps tab of an instance showing one dump (`cmd`), keeping the range, live, kind,
 * environment and kiosk of the page it is opened from (docs/15 §5 carried keys).
 */
export function dumpTabHref(
  appId: number,
  instanceId: number,
  commandId: string,
  current: Partial<CommonState> = {},
): string {
  return buildUrl(`/apps/${String(appId)}/instances/${String(instanceId)}`, InstanceQ, {
    ...defaultState(InstanceQ),
    ...carriedState(current),
    tab: 'dumps',
    cmd: commandId,
  });
}

/** The audit screen filtered to an instance (`f.instance=<id>`). */
export function auditHref(instanceId: number): string {
  return buildUrl('/admin/audit', CommonQ, {
    ...defaultState(CommonQ),
    filters: [{ field: 'instance', op: 'eq', value: untrusted(String(instanceId)) }],
  });
}
