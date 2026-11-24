// SPDX-License-Identifier: Apache-2.0
//
// Registration requests waiting for approval (docs/14 §4.14, 05 §2.11-3, F-S30, WO-39): the queue,
// approve (creates the application and issues one agent token, returned in that response only)
// and reject. The approval answer carries the token once: callers keep it in component state, never
// in the query or mutation cache, browser storage or the URL (docs/16 §2.1, SEC-02).
import { useQuery } from '@tanstack/react-query';

import { api, expectNoContent, unwrap, type EnvelopeOf } from '../client';
import type { components } from '../generated/schema';

export type PendingRegistration = components['schemas']['PendingRegistration'];
export type ApprovedRegistration = components['schemas']['ApprovedRegistration'];
export type ApproveRegistrationRequest = components['schemas']['ApproveRegistrationRequest'];

export const adminPendingKeys = {
  list: () => ['admin', 'pending'] as const,
};

/** The queue, most recently attempted first (at most 1,000). */
export async function listPendingRegistrations() {
  return unwrap<EnvelopeOf<'/api/v1/admin/pending-registrations', 'get'>>(
    api().client.GET('/api/v1/admin/pending-registrations'),
  );
}

export function usePendingRegistrations(enabled = true) {
  return useQuery({
    queryKey: adminPendingKeys.list(),
    queryFn: listPendingRegistrations,
    staleTime: 5_000,
    enabled,
  });
}

/**
 * Approves a request: the application is created (group and Apdex T optional) and its token comes
 * back once. 409 invalid_state with `token: not_issued` when the application has 5 tokens already.
 */
export async function approveRegistration(
  id: number,
  body: ApproveRegistrationRequest,
): Promise<ApprovedRegistration> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/admin/pending-registrations/{id}/approve', 'post'>>(
      api().client.POST('/api/v1/admin/pending-registrations/{id}/approve', {
        params: { path: { id } },
        body,
      }),
    )
  ).data;
}

/** Rejects a request (the agent's next retry queues it again). */
export async function rejectRegistration(id: number): Promise<void> {
  await expectNoContent(
    api().client.POST('/api/v1/admin/pending-registrations/{id}/reject', {
      params: { path: { id } },
    }),
  );
}
