// SPDX-License-Identifier: Apache-2.0
//
// Data retention of the privacy screen's retention panel (docs/14 §4.14 `/admin/settings/
// retention`, docs/13 §8, WO-39 plan PM question 4): the effective values, their source (settings
// or the server configuration), the ClickHouse tables' TTL and the change. A shorter value needs
// `confirm: true` (data outside it is dropped); the server answers 400 confirm/required otherwise.
import { api, unwrap, type EnvelopeOf } from '../client';
import type { components } from '../generated/schema';

export type RetentionView = components['schemas']['RetentionView'];
export type RetentionValues = components['schemas']['RetentionValues'];
export type RetentionUpdated = components['schemas']['RetentionUpdated'];
export type RetentionTable = components['schemas']['RetentionTable'];

/** The retention fields in screen order (docs/13 §8). */
export const RETENTION_FIELDS = [
  'xlog_days',
  'profile_days',
  'stat_raw_days',
  'metric_raw_days',
  'rollup_1m_days',
  'rollup_1h_days',
] as const satisfies readonly (keyof RetentionValues)[];
export type RetentionField = (typeof RETENTION_FIELDS)[number];

/** Server bounds of every value (SettingsController: 1 to 36,500 days). */
export const RETENTION_MIN_DAYS = 1;
export const RETENTION_MAX_DAYS = 36_500;

export const retentionKeys = {
  view: () => ['admin', 'retention'] as const,
};

/** `GET /admin/settings/retention`. */
export async function getRetention(signal?: AbortSignal): Promise<RetentionView> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/admin/settings/retention', 'get'>>(
      api().client.GET('/api/v1/admin/settings/retention', signal === undefined ? {} : { signal }),
    )
  ).data;
}

/** `PUT /admin/settings/retention`: only the changed fields; `confirm` when one gets shorter. */
export async function updateRetention(
  changes: Partial<Record<RetentionField, number>>,
  confirm: boolean,
): Promise<RetentionUpdated> {
  const body: components['schemas']['RetentionUpdateRequest'] = { ...changes };
  if (confirm) {
    body.confirm = true;
  }
  return (
    await unwrap<EnvelopeOf<'/api/v1/admin/settings/retention', 'put'>>(
      api().client.PUT('/api/v1/admin/settings/retention', { body }),
    )
  ).data;
}

/** The changed fields of a draft against the effective values (unchanged ones left out). */
export function retentionChanges(
  current: RetentionValues,
  draft: Readonly<Record<RetentionField, number>>,
): Partial<Record<RetentionField, number>> {
  const out: Partial<Record<RetentionField, number>> = {};
  for (const field of RETENTION_FIELDS) {
    if (draft[field] !== current[field]) {
      out[field] = draft[field];
    }
  }
  return out;
}

/** Fields that become shorter (the confirmation names them). */
export function shortenedFields(
  current: RetentionValues,
  changes: Partial<Record<RetentionField, number>>,
): RetentionField[] {
  return RETENTION_FIELDS.filter((field) => {
    const next = changes[field];
    return next !== undefined && next < current[field];
  });
}

/** True for a whole number of days the server accepts. */
export function isValidRetentionDays(value: number): boolean {
  return Number.isInteger(value) && value >= RETENTION_MIN_DAYS && value <= RETENTION_MAX_DAYS;
}
