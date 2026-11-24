// SPDX-License-Identifier: Apache-2.0
//
// Application administration (docs/14 §4.3, 05 §2.11-1, docs/15 §9.10 `/admin/apps`, WO-39): the
// list with its instance and token counts, create, change (Apdex T, group, registration policy),
// delete with the name as confirmation, and the recommended alert rules (docs/14 §4.13
// `/alerts/templates/apply`). Generated client only; the cache keys start with 'admin'.
import { useQuery } from '@tanstack/react-query';

import { listApps, type App } from '../apps';
import { api, unwrap, type Enveloped, type EnvelopeOf } from '../client';
import type { components } from '../generated/schema';

export type { App } from '../apps';
export type AppDeleted = components['schemas']['AppDeleted'];
export type AppCreateRequest = components['schemas']['AppCreateRequest'];
export type AppPatchRequest = components['schemas']['AppPatchRequest'];
export type AlertTemplatesApplied = components['schemas']['AlertTemplatesApplied'];

/** Registration policies an administrator can set (docs/14 §4.3). */
export const REGISTRATION_POLICIES = ['auto', 'approval'] as const;
export type RegistrationPolicy = (typeof REGISTRATION_POLICIES)[number];

/** Server limits of an application (AppInputs, docs/14 §4.3). */
export const APP_LIMITS = {
  nameMax: 128,
  envMax: 32,
  groupMax: 64,
  apdexMin: 1,
  apdexMax: 65_535,
  apdexDefault: 500,
} as const;

/** Cache keys of the administration application queries. */
export const adminAppKeys = {
  all: () => ['admin', 'apps'] as const,
  list: () => ['admin', 'apps', 'list'] as const,
};

/** Every application (the administration list: no environment filter, at most 500). */
export function useAdminApps() {
  return useQuery({
    queryKey: adminAppKeys.list(),
    queryFn: () => listApps(),
    staleTime: 10_000,
  });
}

/**
 * The number of active and rotating tokens of an application (`tokens_active`, WO-39 C1); null
 * while the server does not send it yet.
 */
export function tokensActive(app: App): number | null {
  const value = (app as App & { readonly tokens_active?: number | null }).tokens_active;
  return typeof value === 'number' ? value : null;
}

/** Creates an application (admin, 201; 409 already_exists). */
export async function createAdminApp(body: AppCreateRequest): Promise<App> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/apps', 'post'>>(api().client.POST('/api/v1/apps', { body }))
  ).data;
}

/** Changes an application (admin; 400 when nothing changes). */
export async function patchApp(appId: number, body: AppPatchRequest): Promise<App> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/apps/{appId}', 'patch'>>(
      api().client.PATCH('/api/v1/apps/{appId}', { params: { path: { appId } }, body }),
    )
  ).data;
}

/**
 * Deletes an application (admin, 202): `confirm` must be its name. Its tokens are revoked, its
 * sessions end and its instances are archived (docs/14 §4.3).
 */
export async function deleteApp(appId: number, confirm: string): Promise<AppDeleted> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/apps/{appId}', 'delete'>>(
      api().client.DELETE('/api/v1/apps/{appId}', {
        params: { path: { appId }, query: { confirm } },
      }),
    )
  ).data;
}

/** Applies the recommended alert rules (every application template) to an application. */
export async function applyRecommendedRules(appId: number): Promise<AlertTemplatesApplied> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/alerts/templates/apply', 'post'>>(
      api().client.POST('/api/v1/alerts/templates/apply', { body: { app: appId } }),
    )
  ).data;
}

/** The list answer type (for tests and the screen). */
export type AdminAppList = Enveloped<App[]>;
