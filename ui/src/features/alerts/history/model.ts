// SPDX-License-Identifier: Apache-2.0
//
// Pure helpers of the alert history (05 §2.9, WO-38): the pinned panel rows and severity counts,
// the in-app form of a server deep link, the mute target of an event, and the mute end limits.
import {
  ACK_NOTE_MAX_CHARS,
  MUTE_MAX_MS,
  type AlertEvent,
  type AlertEventItem,
  type AlertMuteRequest,
} from '../../../api/alerts';
import { safeHref } from '../../../components/safeHref';
import { utf8ByteLength } from '../../../state/urlState';

/** Pinned rows: the active alerts of every application, or of one when it is selected. */
export function pinnedRows(
  items: readonly AlertEventItem[],
  appId: number | null,
): AlertEventItem[] {
  const rows = appId === null ? [...items] : items.filter((item) => item.app?.id === appId);
  // FIRING before ACKED, critical before warn, newest first (the server sends newest first).
  const rank = (item: AlertEventItem): number =>
    (item.state === 'FIRING' ? 0 : 2) + (item.severity === 'critical' ? 0 : 1);
  return rows
    .map((item, index) => ({ item, index }))
    .sort((a, b) => rank(a.item) - rank(b.item) || a.index - b.index)
    .map(({ item }) => item);
}

export interface SeverityCounts {
  readonly critical: number;
  readonly warn: number;
}

/** Counts of the pinned rows by severity. */
export function severityCounts(rows: readonly AlertEventItem[]): SeverityCounts {
  let critical = 0;
  let warn = 0;
  for (const row of rows) {
    if (row.severity === 'critical') {
      critical += 1;
    } else {
      warn += 1;
    }
  }
  return { critical, warn };
}

/**
 * The link of a server deep link (docs/14 §6, absolute ±15 minutes, no live): a same-origin URL
 * becomes its path so the router opens it in the tab; any other accepted URL stays external;
 * null when safeHref refuses it (the action is then disabled).
 */
export function deepLinkTarget(
  deeplink: string | null | undefined,
  origin: string,
): { readonly internal: boolean; readonly href: string } | null {
  const safe = safeHref(deeplink);
  if (safe === undefined) {
    return null;
  }
  if (safe.startsWith('/')) {
    return { internal: true, href: safe };
  }
  try {
    const url = new URL(safe);
    if (url.origin === origin) {
      return { internal: true, href: `${url.pathname}${url.search}${url.hash}` };
    }
  } catch {
    return null;
  }
  return { internal: false, href: safe };
}

/** The instance id of an instance target (`target.id` as a positive int), else undefined. */
function instanceOf(event: Pick<AlertEvent, 'target'>): number | undefined {
  if (event.target.type !== 'instance' || event.target.id === null) {
    return undefined;
  }
  return /^[1-9]\d{0,9}$/.test(event.target.id) && Number(event.target.id) <= 2_147_483_647
    ? Number(event.target.id)
    : undefined;
}

/** The fields the mute dialog shows and sends for "mute this target" (05 §2.9-4). */
export interface MuteTarget {
  readonly app?: number;
  readonly env?: string;
  readonly instance?: number;
  readonly rule?: string;
}

/** The target of an event: its application, environment, instance (if any) and rule. */
export function muteTargetOf(
  event: Pick<AlertEvent, 'app' | 'env' | 'target' | 'rule'>,
): MuteTarget {
  const target: { app?: number; env?: string; instance?: number; rule?: string } = {};
  if (event.app !== null) {
    target.app = event.app.id;
  }
  if (event.env !== null && event.env !== '') {
    target.env = event.env;
  }
  const instance = instanceOf(event);
  if (instance !== undefined) {
    target.instance = instance;
  }
  if (event.rule.id !== '') {
    target.rule = event.rule.id;
  }
  return target;
}

/** Mute durations offered by the dialog (hours), up to the 30-day maximum. */
export const MUTE_DURATIONS_H = [1, 4, 24, 72, 168, 720] as const;

/**
 * The body of a mute request lasting `hours` from `now`: refused (null) when it is not a positive
 * duration of at most 30 days.
 */
export function muteRequest(
  target: MuteTarget,
  hours: number,
  reason: string,
  now: number,
): AlertMuteRequest | null {
  const length = hours * 60 * 60 * 1000;
  if (!Number.isFinite(length) || length <= 0 || length > MUTE_MAX_MS) {
    return null;
  }
  const request: AlertMuteRequest = {
    from: new Date(now).toISOString(),
    to: new Date(now + length).toISOString(),
    target: { ...target },
  };
  const trimmed = reason.trim();
  if (trimmed !== '') {
    request.reason = trimmed;
  }
  return request;
}

/** UTF-8 bytes of an ACK note or mute reason at most (the server's 1 KB check). */
export const ALERT_TEXT_MAX_BYTES = 1024;

/**
 * True when an ACK note or mute reason fits the server limits: at most 512 characters (UTF-16
 * units, the server's String.length and the textarea's maxLength) and 1 KB of UTF-8.
 */
export function alertTextFits(text: string): boolean {
  return text.length <= ACK_NOTE_MAX_CHARS && utf8ByteLength(text) <= ALERT_TEXT_MAX_BYTES;
}
