// SPDX-License-Identifier: Apache-2.0
//
// Server calls of the alert history and channel screens (docs/14 §4.13, docs/15 §9.9, 05 §2.9,
// WO-38) through the generated client: the event history (cursor pages, every server filter),
// the acknowledgement, one-off mutes, the per-rule statistics, the notification channels (CRUD,
// test send, webhook signing-key rotation) and the active alerts of every application
// (`GET /overview/alerts`, the pinned panel). No hand-written requests (03 §3.3); errors come back
// as ApiError (docs/14 §2.6), and errorInfo/alertErrorCode turn them into screen messages.
//
// Secrets (SEC-52, docs/16 §8): a channel's URL or recipients are sent once and never come back
// (`target_masked` only); a generated or rotated signing secret is returned once by the server and
// handed to the caller as is. Nothing here caches or stores either: the screens keep them in
// component state only while the one-time dialog is open.
import { api, expectNoContent, unwrap, type Enveloped, type EnvelopeOf } from './client';
import { isApiError } from './errors';
import type { components } from './generated/schema';

export type AlertEvent = components['schemas']['AlertEvent'];
export type AlertEventItem = components['schemas']['AlertEventItem'];
export type AlertRuleRef = components['schemas']['AlertRuleRef'];
export type AlertTarget = components['schemas']['AlertTarget'];
export type AlertDelivery = components['schemas']['AlertDelivery'];
export type AlertAck = components['schemas']['AlertAck'];
export type AlertMute = components['schemas']['AlertMute'];
export type AlertMuteRequest = components['schemas']['AlertMuteRequest'];
export type AlertRuleStats = components['schemas']['AlertRuleStats'];
export type AlertChannel = components['schemas']['ChannelView'];
export type CreatedChannel = components['schemas']['CreatedChannelView'];
export type CreateChannelRequest = components['schemas']['CreateChannelRequest'];
export type UpdateChannelRequest = components['schemas']['UpdateChannelRequest'];
export type RotatedKey = components['schemas']['RotatedKeyView'];
export type ChannelTestResult = components['schemas']['TestResultView'];
export type AlertRuleSummary = components['schemas']['AlertRule'];

/** Channel types (docs/14 §4.13). */
export const CHANNEL_TYPES = ['webhook', 'slack', 'email'] as const;
export type ChannelType = (typeof CHANNEL_TYPES)[number];

/** Rows of an event page: the server default 100, at most 500 (docs/14 §4.13). */
export const EVENTS_PAGE_SIZE = 100;
export const EVENTS_PAGE_MAX = 500;
/** ACK note: at most 512 characters (ADR-0010 C-226 (7); the server also caps 1 KB of UTF-8). */
export const ACK_NOTE_MAX_CHARS = 512;
/** Mute reason: at most 512 characters (docs/14 §4.13 AlertMuteRequest). */
export const MUTE_REASON_MAX_CHARS = 512;
/** A one-off mute lasts at most 30 days (docs/14 §4.13). */
export const MUTE_MAX_MS = 30 * 24 * 60 * 60 * 1000;

/**
 * Reasons of a state transition (ADR-0010 C-227 (3), `alert_events.reason`): the closed list the
 * server records. Rows written before the column exist carry null ('—' on screen).
 */
export type AlertReason = NonNullable<AlertEvent['reason']>;

/** Every reason of the generated enum (a missing or extra key fails the type check). */
const REASONS: Readonly<Record<AlertReason, true>> = {
  condition: true,
  cleared: true,
  escalated: true,
  acked: true,
  resolved: true,
  target_gone: true,
  rule_disabled: true,
  rule_deleted: true,
  no_data: true,
  data_returned: true,
};
export const ALERT_REASONS = Object.keys(REASONS) as readonly AlertReason[];

/** The reason of an event (null for old rows and for a value outside the known list). */
export function eventReason(event: { readonly reason?: unknown }): AlertReason | null {
  const reason = event.reason;
  return typeof reason === 'string' && (ALERT_REASONS as readonly string[]).includes(reason)
    ? (reason as AlertReason)
    : null;
}

/**
 * The unit of a rule reference (`rule.unit`, ADR-0010 C-227 (4) additive field): the raw string
 * for AlertValue, or null while the server does not send it.
 */
export function ruleUnit(rule: { readonly unit?: unknown }): string | null {
  return typeof rule.unit === 'string' ? rule.unit : null;
}

/** API states of the history filter (URL `state`, lower case). */
const STATE_PARAM = {
  firing: 'FIRING',
  acked: 'ACKED',
  resolved: 'RESOLVED',
  nodata: 'NO_DATA',
  pending: 'PENDING',
  ok: 'OK',
} as const;

export type AlertStateFilter = keyof typeof STATE_PARAM | 'all';

/** Filters and page of an event request (docs/14 §4.13 `GET /alerts/events`). */
export interface AlertEventsRequest {
  /** Absolute range (epoch ms), at most 366 days. */
  readonly from: number;
  readonly to: number;
  /** One application, or null for every application (05 §2.9-6). */
  readonly appId: number | null;
  readonly env: string | null;
  readonly severity: 'warn' | 'critical' | 'all';
  readonly state: AlertStateFilter;
  readonly ruleId: string | null;
  /** Only events with a failed delivery (`delivery=failed`). */
  readonly failedOnly: boolean;
  /** Rows per page (clamped to 1..500). */
  readonly limit?: number;
}

export interface AlertEventsQuery {
  from: string;
  to: string;
  app?: number;
  env?: string;
  severity?: string;
  state?: string;
  rule?: string;
  delivery?: string;
  cursor?: string;
  limit?: number;
}

/** The query of an event request as the generated client types it (defaults left out). */
export function alertEventsQuery(
  request: AlertEventsRequest,
  cursor: string | null = null,
): AlertEventsQuery {
  const query: AlertEventsQuery = {
    from: new Date(request.from).toISOString(),
    to: new Date(request.to).toISOString(),
  };
  if (request.appId !== null) {
    query.app = request.appId;
  }
  if (request.env !== null && request.env !== '') {
    query.env = request.env;
  }
  if (request.severity !== 'all') {
    query.severity = request.severity;
  }
  if (request.state !== 'all') {
    query.state = STATE_PARAM[request.state];
  }
  if (request.ruleId !== null) {
    query.rule = request.ruleId;
  }
  if (request.failedOnly) {
    query.delivery = 'failed';
  }
  if (cursor !== null) {
    query.cursor = cursor;
  }
  const limit = Math.min(
    EVENTS_PAGE_MAX,
    Math.max(1, Math.trunc(request.limit ?? EVENTS_PAGE_SIZE)),
  );
  if (limit !== EVENTS_PAGE_SIZE) {
    query.limit = limit;
  }
  return query;
}

function withSignal(signal: AbortSignal | undefined): { signal?: AbortSignal } {
  return signal === undefined ? {} : { signal };
}

type EventsEnvelope = EnvelopeOf<'/api/v1/alerts/events', 'get'>;
type AckEnvelope = EnvelopeOf<'/api/v1/alerts/events/{eventId}/ack', 'post'>;
type MutesEnvelope = EnvelopeOf<'/api/v1/alerts/mutes', 'get'>;
type MuteEnvelope = EnvelopeOf<'/api/v1/alerts/mutes', 'post'>;
type StatsEnvelope = EnvelopeOf<'/api/v1/alerts/stats', 'get'>;
type ChannelsEnvelope = EnvelopeOf<'/api/v1/alerts/channels', 'get'>;
type ChannelCreateEnvelope = EnvelopeOf<'/api/v1/alerts/channels', 'post'>;
type ChannelUpdateEnvelope = EnvelopeOf<'/api/v1/alerts/channels/{id}', 'put'>;
type ChannelTestEnvelope = EnvelopeOf<'/api/v1/alerts/channels/{id}/test', 'post'>;
type RotateEnvelope = EnvelopeOf<'/api/v1/alerts/channels/{id}/signing-key/rotate', 'post'>;
type OverviewAlertsEnvelope = EnvelopeOf<'/api/v1/overview/alerts', 'get'>;
type RulesEnvelope = EnvelopeOf<'/api/v1/alerts/rules', 'get'>;

/** One page of the event history, newest first; `meta.next_cursor` continues it. */
export async function listAlertEvents(
  request: AlertEventsRequest,
  cursor: string | null = null,
  signal?: AbortSignal,
): Promise<Enveloped<AlertEvent[]>> {
  return unwrap<EventsEnvelope>(
    api().client.GET('/api/v1/alerts/events', {
      params: { query: alertEventsQuery(request, cursor) },
      ...withSignal(signal),
    }),
  );
}

/**
 * Acknowledges a firing alert (`eventId` = the FIRING event of the current firing). 409
 * invalid_state when it is no longer FIRING (already acknowledged or resolved).
 */
export async function ackAlert(eventId: string, note: string): Promise<AlertAck> {
  const trimmed = note.trim();
  const result = await unwrap<AckEnvelope>(
    api().client.POST('/api/v1/alerts/events/{eventId}/ack', {
      params: { path: { eventId } },
      // The dialog keeps the note within 512 characters and 1 KB; the server checks both again.
      body: trimmed === '' ? {} : { note: trimmed },
    }),
  );
  return result.data;
}

/** The one-off mutes that have not ended (operator and above). */
export async function listAlertMutes(signal?: AbortSignal): Promise<Enveloped<AlertMute[]>> {
  return unwrap<MutesEnvelope>(api().client.GET('/api/v1/alerts/mutes', withSignal(signal)));
}

/** Creates a one-off mute (at most 30 days, ending in the future). */
export async function createAlertMute(request: AlertMuteRequest): Promise<AlertMute> {
  const result = await unwrap<MuteEnvelope>(
    api().client.POST('/api/v1/alerts/mutes', { body: request }),
  );
  return result.data;
}

/** Ends a mute now. */
export async function deleteAlertMute(muteId: string): Promise<void> {
  await expectNoContent(
    api().client.DELETE('/api/v1/alerts/mutes/{muteId}', { params: { path: { muteId } } }),
  );
}

/** Per-rule statistics of the last 7 or 30 days (one application or every application). */
export async function getAlertStats(
  request: { readonly days: 7 | 30; readonly appId: number | null },
  signal?: AbortSignal,
): Promise<Enveloped<AlertRuleStats[]>> {
  const query: { days?: number; app?: number } = {};
  if (request.days !== 7) {
    query.days = request.days;
  }
  if (request.appId !== null) {
    query.app = request.appId;
  }
  return unwrap<StatsEnvelope>(
    api().client.GET('/api/v1/alerts/stats', { params: { query }, ...withSignal(signal) }),
  );
}

/** The rules of the history's rule filter (names and ids; one application or all). */
export async function listAlertRuleOptions(
  appId: number | null,
  signal?: AbortSignal,
): Promise<Enveloped<AlertRuleSummary[]>> {
  const query: { app?: number } = {};
  if (appId !== null) {
    query.app = appId;
  }
  return unwrap<RulesEnvelope>(
    api().client.GET('/api/v1/alerts/rules', { params: { query }, ...withSignal(signal) }),
  );
}

/** The active (FIRING and ACKED) alerts of every application (the pinned panel). */
export async function listActiveAlerts(
  env: string | null,
  signal?: AbortSignal,
): Promise<Enveloped<AlertEventItem[]>> {
  const query: { env?: string } = {};
  if (env !== null && env !== '') {
    query.env = env;
  }
  return unwrap<OverviewAlertsEnvelope>(
    api().client.GET('/api/v1/overview/alerts', { params: { query }, ...withSignal(signal) }),
  );
}

/** The notification channels, by name; targets masked. */
export async function listChannels(signal?: AbortSignal): Promise<Enveloped<AlertChannel[]>> {
  return unwrap<ChannelsEnvelope>(api().client.GET('/api/v1/alerts/channels', withSignal(signal)));
}

/**
 * Creates a channel (administrator). A webhook without a given secret gets a generated one,
 * returned once in `signing_key`.
 */
export async function createChannel(request: CreateChannelRequest): Promise<CreatedChannel> {
  const result = await unwrap<ChannelCreateEnvelope>(
    api().client.POST('/api/v1/alerts/channels', { body: request }),
  );
  return result.data;
}

/** Changes the re-entered fields of a channel (administrator). */
export async function updateChannel(
  channelId: string,
  request: UpdateChannelRequest,
): Promise<AlertChannel> {
  const result = await unwrap<ChannelUpdateEnvelope>(
    api().client.PUT('/api/v1/alerts/channels/{id}', {
      params: { path: { id: channelId } },
      body: request,
    }),
  );
  return result.data;
}

/** Deletes a channel; 409 invalid_state while a rule refers to it. */
export async function deleteChannel(channelId: string): Promise<void> {
  await expectNoContent(
    api().client.DELETE('/api/v1/alerts/channels/{id}', { params: { path: { id: channelId } } }),
  );
}

/** Sends a test message through the delivery path (delivered, status code, duration only). */
export async function testChannel(channelId: string): Promise<ChannelTestResult> {
  const result = await unwrap<ChannelTestEnvelope>(
    api().client.POST('/api/v1/alerts/channels/{id}/test', {
      params: { path: { id: channelId } },
    }),
  );
  return result.data;
}

/** Rotates a webhook signing key: the new secret is returned once. */
export async function rotateSigningKey(channelId: string): Promise<RotatedKey> {
  const result = await unwrap<RotateEnvelope>(
    api().client.POST('/api/v1/alerts/channels/{id}/signing-key/rotate', {
      params: { path: { id: channelId } },
    }),
  );
  return result.data;
}

/** Reasons of a refused channel destination (422 webhook_destination_blocked, docs/16 §8.1). */
export const DESTINATION_REASONS = [
  'scheme',
  'userinfo',
  'invalid_url',
  'dns',
  'own_address',
  'loopback',
  'private',
  'link_local',
  'unique_local',
  'shared_address',
  'multicast',
  'broadcast',
  'reserved',
  'unspecified',
  'this_network',
  'documentation',
  'benchmarking',
  'protocol_assignment',
] as const;
export type DestinationReason = (typeof DESTINATION_REASONS)[number];

/**
 * The destination refusal of a channel request: the reason of a 422 webhook_destination_blocked
 * ('blocked' for a reason outside the known list), or null for any other error.
 */
export function destinationBlockedReason(error: unknown): DestinationReason | 'blocked' | null {
  if (!isApiError(error) || error.status !== 422 || error.code !== 'webhook_destination_blocked') {
    return null;
  }
  const reason = error.details.find((detail) => detail.field === 'target')?.reason;
  return reason !== undefined && (DESTINATION_REASONS as readonly string[]).includes(reason)
    ? (reason as DestinationReason)
    : 'blocked';
}

/** The `{field, reason}` of the first detail of a 400 invalid_request, if any. */
export function invalidField(error: unknown): { field: string; reason: string } | null {
  if (!isApiError(error) || error.status !== 400) {
    return null;
  }
  const detail = error.details.find((d) => d.field !== null);
  return detail === undefined || detail.field === null
    ? null
    : { field: detail.field, reason: detail.reason };
}

/** True for a 409 (ACK of an alert that is no longer firing, a channel still in use). */
export function isConflict(error: unknown): boolean {
  return isApiError(error) && error.status === 409;
}
