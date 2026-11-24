// SPDX-License-Identifier: Apache-2.0
//
// Remote configuration and the kill switch of the configuration screen `/admin/config` (docs/14
// §4.14, docs/15 §9.10, 05 §2.11-5/-6, F-S29, WO-39): an app's pushed configuration with the keys
// every instance allows remotely and the drift, the change history (cursor pages), a change preview
// (reason, values, targets), apply, the per-instance results, revert and the kill switch. Every
// call goes through the generated client (CSRF header on writes, docs/16 §7.3); errors come back
// as ApiError and configErrorKey() turns them into the screen's message keys.
//
// Values may be secret-like: the server masks them (****) in every answer; the UI shows what it
// gets and never stores a value outside the query cache of these keys.
import { api, unwrap, type Enveloped, type EnvelopeOf } from '../client';
import { isApiError } from '../errors';
import type { components } from '../generated/schema';

export type AppConfig = components['schemas']['AppConfig'];
export type ConfigDrift = components['schemas']['ConfigDrift'];
export type ConfigInstanceAllow = components['schemas']['ConfigInstanceAllow'];
export type ConfigChange = components['schemas']['ConfigChange'];
export type ConfigChangeResult = components['schemas']['ConfigChangeResult'];
export type ConfigDiffEntry = components['schemas']['ConfigDiffEntry'];
export type ConfigInstanceKeys = components['schemas']['ConfigInstanceKeys'];
export type ConfigRejectedKey = components['schemas']['ConfigRejectedKey'];

/** The change reason: required, at most 1 KiB of UTF-8 (docs/14 §4.14 ConfigChangeRequest). */
export const CONFIG_REASON_MAX_BYTES = 1024;
/** Most instances one change targets (docs/14 §4.14). */
export const CONFIG_TARGETS_MAX = 100;
/** Most keys of one change (docs/14 §4.14). */
export const CONFIG_KEYS_MAX = 64;
/** Rows of a history page (server default 50, at most 100). */
export const CONFIG_HISTORY_PAGE = 50;
/** Result polling while the change is applying (N-25: results within 10 s). */
export const CONFIG_POLL_MS = 1_000;
/** Polling stops after this long (the server marks a push without answer offline after 30 s). */
export const CONFIG_POLL_LIMIT_MS = 30_000;
/** The agent key of the kill switch (docs/agent-config.md `enabled`); only /kill-switch sets it. */
export const KILL_SWITCH_KEY = 'enabled';

/** Change states (docs/14 §4.14). */
export const CHANGE_STATUSES = ['previewed', 'applying', 'applied', 'superseded'] as const;
/** Per-target result states (docs/14 §4.14). */
export const RESULT_STATUSES = ['applied', 'partial', 'rejected', 'offline', 'pending'] as const;
export type ResultStatus = (typeof RESULT_STATUSES)[number];

/** Cache keys of the configuration screen (all start with ['admin', 'config']). */
export const configKeys = {
  all: () => ['admin', 'config'] as const,
  app: (appId: number) => ['admin', 'config', 'app', appId] as const,
  history: (appId: number) => ['admin', 'config', 'history', appId] as const,
  change: (changeId: string) => ['admin', 'config', 'change', changeId] as const,
};

/** Targets of a change: every instance, or the chosen ones (at most 100). */
export type ConfigTargetChoice =
  { readonly all: true } | { readonly all: false; readonly instanceIds: readonly number[] };

function withSignal(signal: AbortSignal | undefined): { signal?: AbortSignal } {
  return signal === undefined ? {} : { signal };
}

/** `GET /admin/apps/{appId}/config`: held values, remotely allowed keys and drift. */
export async function getAppConfig(appId: number, signal?: AbortSignal): Promise<AppConfig> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/admin/apps/{appId}/config', 'get'>>(
      api().client.GET('/api/v1/admin/apps/{appId}/config', {
        params: { path: { appId } },
        ...withSignal(signal),
      }),
    )
  ).data;
}

/** `GET /admin/apps/{appId}/config/changes`: newest first; the next page by meta.next_cursor. */
export async function listConfigChanges(
  appId: number,
  cursor: string | null,
  signal?: AbortSignal,
): Promise<Enveloped<ConfigChange[]>> {
  const query: { limit: number; cursor?: string } = { limit: CONFIG_HISTORY_PAGE };
  if (cursor !== null) {
    query.cursor = cursor;
  }
  return unwrap<EnvelopeOf<'/api/v1/admin/apps/{appId}/config/changes', 'get'>>(
    api().client.GET('/api/v1/admin/apps/{appId}/config/changes', {
      params: { path: { appId }, query },
      ...withSignal(signal),
    }),
  );
}

export interface ProposeChangeInput {
  readonly reason: string;
  /** key → new value (strings as typed; null removes the server-held value). */
  readonly changes: Readonly<Record<string, string | null>>;
  readonly targets: ConfigTargetChoice;
}

/** `POST /admin/apps/{appId}/config/changes` (201): stores a preview with its diff. */
export async function proposeConfigChange(
  appId: number,
  input: ProposeChangeInput,
): Promise<ConfigChange> {
  const body: components['schemas']['ConfigChangeRequest'] = {
    reason: input.reason,
    changes: { ...input.changes },
    targets: input.targets.all
      ? { all: true }
      : { all: false, instance_ids: [...input.targets.instanceIds] },
  };
  return (
    await unwrap<EnvelopeOf<'/api/v1/admin/apps/{appId}/config/changes', 'post'>>(
      api().client.POST('/api/v1/admin/apps/{appId}/config/changes', {
        params: { path: { appId } },
        body,
      }),
    )
  ).data;
}

/** `GET /admin/config/changes/{changeId}`: the change with its per-target results. */
export async function getConfigChange(
  changeId: string,
  signal?: AbortSignal,
): Promise<ConfigChange> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/admin/config/changes/{changeId}', 'get'>>(
      api().client.GET('/api/v1/admin/config/changes/{changeId}', {
        params: { path: { changeId } },
        ...withSignal(signal),
      }),
    )
  ).data;
}

/** `POST /admin/config/changes/{changeId}/apply` (202): pushes the preview. */
export async function applyConfigChange(changeId: string): Promise<ConfigChange> {
  // 202 Accepted: EnvelopeOf covers 200/201 only, so the envelope type is named here.
  return (
    await unwrap<components['schemas']['EnvelopeConfigChange']>(
      api().client.POST('/api/v1/admin/config/changes/{changeId}/apply', {
        params: { path: { changeId } },
      }),
    )
  ).data;
}

/** `POST /admin/config/changes/{changeId}/revert` (201): creates and applies the revert. */
export async function revertConfigChange(changeId: string, reason: string): Promise<ConfigChange> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/admin/config/changes/{changeId}/revert', 'post'>>(
      api().client.POST('/api/v1/admin/config/changes/{changeId}/revert', {
        params: { path: { changeId } },
        body: { reason },
      }),
    )
  ).data;
}

export interface KillSwitchInput {
  /** false: the kill switch is ON (instrumentation off); true: back on. */
  readonly enabled: boolean;
  readonly reason: string;
  /** Empty: every instance of the app. */
  readonly instanceIds: readonly number[];
}

/** `POST /admin/apps/{appId}/kill-switch` (202): a change of `enabled`, proposed and applied. */
export async function setKillSwitch(appId: number, input: KillSwitchInput): Promise<ConfigChange> {
  const body: components['schemas']['KillSwitchRequest'] = {
    enabled: input.enabled,
    reason: input.reason,
  };
  if (input.instanceIds.length > 0) {
    body.instance_ids = [...input.instanceIds];
  }
  return (
    await unwrap<components['schemas']['EnvelopeConfigChange']>(
      api().client.POST('/api/v1/admin/apps/{appId}/kill-switch', {
        params: { path: { appId } },
        body,
      }),
    )
  ).data;
}

/** True when the change no longer waits for results (applied or superseded; not applying). */
export function isFinalChange(change: Pick<ConfigChange, 'status'>): boolean {
  return change.status !== 'applying';
}

/**
 * Refetch interval of a tracked change: 1 s while it is applying and less than 30 s passed since
 * tracking started; false (stop) once it is final or the limit passed (docs/15 §9.10 "10초 내 갱신",
 * the server answers offline after 30 s).
 */
export function changeRefetchInterval(
  change: Pick<ConfigChange, 'status'> | undefined,
  startedAt: number,
  now: number = Date.now(),
): number | false {
  if (change !== undefined && isFinalChange(change)) {
    return false;
  }
  return now - startedAt < CONFIG_POLL_LIMIT_MS ? CONFIG_POLL_MS : false;
}

/** The known result state, or null for a value the UI does not know. */
export function resultStatus(value: string): ResultStatus | null {
  return (RESULT_STATUSES as readonly string[]).includes(value) ? (value as ResultStatus) : null;
}

/** Keys the change will not push to each instance (by server: outside its remote-config.allow). */
export function rejectedByInstance(change: Pick<ConfigChange, 'preview'>): Map<number, string[]> {
  const out = new Map<number, string[]>();
  for (const entry of change.preview.rejected_keys_by_instance) {
    out.set(entry.instance_id, [...entry.keys]);
  }
  return out;
}

/** Why a change request was refused, as a message key of `admin:config.error.*`. */
export type ConfigErrorKind =
  | 'keyNotAllowed'
  | 'unknownKey'
  | 'immutableKey'
  | 'invalidValue'
  | 'reduceMasking'
  | 'killSwitchKey'
  | 'invalidState'
  | 'rateLimited'
  | 'forbidden'
  | 'other';

export interface ConfigProblem {
  readonly kind: ConfigErrorKind;
  /** The key the server named (details[].field), when it named one. */
  readonly key: string | null;
}

/** Reason codes of the server's 400/422 details (ConfigChangeService, AgentKeySchema.Refusal). */
const REASON_KINDS: Readonly<Record<string, ConfigErrorKind>> = {
  not_allowed_by_targets: 'keyNotAllowed',
  unknown_key: 'unknownKey',
  immutable: 'immutableKey',
  invalid_value: 'invalidValue',
  reduce_masking: 'reduceMasking',
  use_kill_switch: 'killSwitchKey',
};

/** The agent key of a detail field (`changes.<key>` → `<key>`; other fields stay as they are). */
function detailKey(field: string | null): string | null {
  if (field === null) {
    return null;
  }
  return field.startsWith('changes.') ? field.slice('changes.'.length) : field;
}

/**
 * The screen message of a change, apply, revert or kill switch error (docs/14 §4.14): 422
 * remote_key_not_allowed names the key no target allows; 400 names the key and why (unknown,
 * immutable, invalid value, masking reduction, the kill switch key); 409 invalid_state (already
 * applied or superseded); 429. Anything else is 'other' (the generic error state).
 */
export function configProblem(error: unknown): ConfigProblem {
  if (!isApiError(error)) {
    return { kind: 'other', key: null };
  }
  if (error.status === 429) {
    return { kind: 'rateLimited', key: null };
  }
  if (error.status === 403) {
    return { kind: 'forbidden', key: null };
  }
  if (error.status === 409 && error.code === 'invalid_state') {
    return { kind: 'invalidState', key: null };
  }
  if (error.status === 422 && error.code === 'remote_key_not_allowed') {
    const detail =
      error.details.find((d) => d.reason === 'not_allowed_by_targets') ?? error.details[0];
    return { kind: 'keyNotAllowed', key: detailKey(detail?.field ?? null) };
  }
  if (error.status === 400) {
    for (const detail of error.details) {
      const kind = Object.prototype.hasOwnProperty.call(REASON_KINDS, detail.reason)
        ? REASON_KINDS[detail.reason]
        : undefined;
      if (kind !== undefined) {
        return { kind, key: detailKey(detail.field) };
      }
    }
    const first = error.details[0];
    return { kind: 'invalidValue', key: detailKey(first?.field ?? null) };
  }
  return { kind: 'other', key: null };
}
