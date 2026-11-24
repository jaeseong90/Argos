// SPDX-License-Identifier: Apache-2.0
//
// Privacy administration of `/admin/privacy` (docs/14 §4.14, docs/16 §6.4, 05 §2.11-9, WO-39): the
// server's default IP mode (GET/PUT /admin/settings/privacy), deletion requests for a user id or a
// client address (POST /admin/privacy/deletions, 202 + job) and the job state
// (GET /admin/privacy/jobs/{jobId}).
//
// The deletion subject (SEC-33, ADR-0010 C-241 (3)): the value is sent once, in the JSON body of the
// POST request (never in the URL, which proxies and the server's access log would keep), and kept
// nowhere on the client: requestDeletion() is called from an event
// handler (no mutation cache, whose variables would hold it), the query keys carry the job id only
// and nothing is logged. The server answers with the value's SHA-256 only.
import { api, unwrap, type EnvelopeOf } from '../client';
import { isApiError } from '../errors';
import type { components } from '../generated/schema';

export type PrivacySettings = components['schemas']['PrivacySettingsView'];
export type IpMode = PrivacySettings['default_ip_mode'];
export type DeletionAccepted = components['schemas']['DeletionAccepted'];
export type PrivacyJob = components['schemas']['PrivacyJobView'];
export type PrivacyJobTable = components['schemas']['JobTable'];

export const IP_MODES: readonly IpMode[] = ['full', 'masked', 'off'];
/** A deletion covers at most 30 days (docs/14 §4.14 `to`). */
export const DELETION_RANGE_MAX_MS = 30 * 24 * 60 * 60 * 1000;
/** The longest user id the server accepts (docs/14 §7.2 free text: 1 KB of UTF-8). */
export const DELETION_VALUE_MAX_BYTES = 1024;
/** Job polling while it is queued or running (05 §2.11-9 "상태"). */
export const PRIVACY_JOB_POLL_MS = 2_000;

/** Cache keys of the privacy screen: never a deletion subject, the job id only. */
export const privacyKeys = {
  all: () => ['admin', 'privacy'] as const,
  settings: () => ['admin', 'privacy', 'settings'] as const,
  job: (jobId: string) => ['admin', 'privacy', 'job', jobId] as const,
};

/** `GET /admin/settings/privacy`. */
export async function getPrivacySettings(signal?: AbortSignal): Promise<PrivacySettings> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/admin/settings/privacy', 'get'>>(
      api().client.GET('/api/v1/admin/settings/privacy', signal === undefined ? {} : { signal }),
    )
  ).data;
}

/** `PUT /admin/settings/privacy`: the default IP mode of agents without a local setting. */
export async function updatePrivacySettings(mode: IpMode): Promise<PrivacySettings> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/admin/settings/privacy', 'put'>>(
      api().client.PUT('/api/v1/admin/settings/privacy', { body: { default_ip_mode: mode } }),
    )
  ).data;
}

export interface DeletionInput {
  readonly kind: 'user_id' | 'ip';
  readonly value: string;
  /** Epoch ms, [from, to), at most 30 days. */
  readonly from: number;
  readonly to: number;
  /** One application, or null for every application. */
  readonly appId: number | null;
}

/**
 * `POST /admin/privacy/deletions` (202): queues the deletion job. The value goes to the server in
 * the request body only, never in the URL; the caller must clear its form afterwards (SEC-33).
 */
export async function requestDeletion(input: DeletionInput): Promise<DeletionAccepted> {
  const body: {
    from: string;
    to: string;
    user_id?: string;
    ip?: string;
    app_id?: number;
  } = { from: new Date(input.from).toISOString(), to: new Date(input.to).toISOString() };
  if (input.kind === 'user_id') {
    body.user_id = input.value;
  } else {
    body.ip = input.value;
  }
  if (input.appId !== null) {
    body.app_id = input.appId;
  }
  // 202 Accepted: EnvelopeOf covers 200/201 only, so the envelope type is named here.
  return (
    await unwrap<components['schemas']['EnvelopeDeletionAccepted']>(
      api().client.POST('/api/v1/admin/privacy/deletions', { body }),
    )
  ).data;
}

/** `GET /admin/privacy/jobs/{jobId}`. */
export async function getPrivacyJob(jobId: string, signal?: AbortSignal): Promise<PrivacyJob> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/admin/privacy/jobs/{jobId}', 'get'>>(
      api().client.GET('/api/v1/admin/privacy/jobs/{jobId}', {
        params: { path: { jobId } },
        ...(signal === undefined ? {} : { signal }),
      }),
    )
  ).data;
}

/** True while the job may still change (queued, running). */
export function isJobActive(job: Pick<PrivacyJob, 'status'> | undefined): boolean {
  return job === undefined || job.status === 'queued' || job.status === 'running';
}

/** Why the deletion form cannot be sent (checked before any request), or null. */
export type DeletionFormProblem =
  'valueRequired' | 'valueTooLong' | 'ipInvalid' | 'rangeRequired' | 'rangeOrder' | 'rangeTooLong';

const IPV4 = /^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$/;
const IPV6 = /^[0-9A-Fa-f:.]{2,45}$/;

/** True for an IPv4 or IPv6 literal (the server re-checks; this only spares a round trip). */
export function isIpLiteral(value: string): boolean {
  return IPV4.test(value) || (value.includes(':') && IPV6.test(value));
}

/** The first problem of a deletion form, or null when it can be sent. */
export function deletionFormProblem(
  input: Omit<DeletionInput, 'from' | 'to'> & {
    readonly from: number | null;
    readonly to: number | null;
  },
): DeletionFormProblem | null {
  const value = input.value.trim();
  if (value === '') {
    return 'valueRequired';
  }
  if (new TextEncoder().encode(value).length > DELETION_VALUE_MAX_BYTES) {
    return 'valueTooLong';
  }
  if (input.kind === 'ip' && !isIpLiteral(value)) {
    return 'ipInvalid';
  }
  if (input.from === null || input.to === null) {
    return 'rangeRequired';
  }
  if (input.to <= input.from) {
    return 'rangeOrder';
  }
  if (input.to - input.from > DELETION_RANGE_MAX_MS) {
    return 'rangeTooLong';
  }
  return null;
}

/** The screen message of a refused deletion request (docs/14 §4.14). */
export type DeletionErrorKind =
  | 'rateLimited'
  | 'valueInvalid'
  | 'rangeTooLong'
  | 'rangeOrder'
  | 'appNotFound'
  | 'queueFull'
  | 'other';

export function deletionErrorKind(error: unknown): DeletionErrorKind {
  if (!isApiError(error)) {
    return 'other';
  }
  if (error.status === 429) {
    return 'rateLimited';
  }
  if (error.status === 404) {
    return 'appNotFound';
  }
  if (error.status === 503) {
    return 'queueFull';
  }
  if (error.status === 400 || error.status === 422) {
    if (error.details.some((d) => d.field === 'to' && d.reason === 'too_long')) {
      return 'rangeTooLong';
    }
    if (error.details.some((d) => d.field === 'to' && d.reason === 'before_from')) {
      return 'rangeOrder';
    }
    return 'valueInvalid';
  }
  return 'other';
}
