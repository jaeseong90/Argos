// SPDX-License-Identifier: Apache-2.0
//
// Agent tokens (docs/14 §4.14, docs/16 §2.1, 05 §2.11-4, docs/15 §9.10 `/admin/tokens`, WO-39): the
// tokens of an application (prefix only), issue with an expiry, rotate with a grace period and
// revoke with a reason. Issue and rotate answer with the token once (no-store): callers keep it in
// component state only, never in the query or mutation cache, browser storage, logs or the URL
// (SEC-02). That is why these calls are plain functions, not mutations.
import { useQuery } from '@tanstack/react-query';

import { api, expectNoContent, unwrap, type EnvelopeOf } from '../client';
import type { components } from '../generated/schema';

export type AgentToken = components['schemas']['AgentTokenView'];
export type IssuedToken = components['schemas']['IssuedTokenResponse'];

/** Expiry of a new token in days: server default 365 (docs/14 §4.14), at least 1. */
export const TOKEN_EXPIRY_DEFAULT_DAYS = 365;
export const TOKEN_EXPIRY_MAX_DAYS = 3650;
/** Days the rotated token keeps working (server default 7, 05 §2.11-4). */
export const TOKEN_ROTATE_GRACE_DAYS = 7;
/** Longest revoke reason (characters). */
export const TOKEN_REVOKE_REASON_MAX = 512;

/**
 * Days before expiry from which a token carries the "expires soon" badge: the warn threshold of
 * the server's default alert template `cert_token_expiry` (DefaultTemplates.java, part "Agent
 * token expiry": `agent_token_expiry_days <= 30` warns, `<= 7` is critical).
 */
export const TOKEN_EXPIRY_WARN_DAYS = 30;
const DAY_MS = 86_400_000;

export const adminTokenKeys = {
  list: (appId: number | null) => ['admin', 'tokens', appId] as const,
};

export async function listTokens(appId: number) {
  return unwrap<EnvelopeOf<'/api/v1/admin/apps/{appId}/tokens', 'get'>>(
    api().client.GET('/api/v1/admin/apps/{appId}/tokens', { params: { path: { appId } } }),
  );
}

export function useTokens(appId: number | null) {
  return useQuery({
    queryKey: adminTokenKeys.list(appId),
    queryFn: () => listTokens(appId ?? 0),
    enabled: appId !== null,
    staleTime: 5_000,
  });
}

/** Issues a token (201) that expires after `expiresInDays`; the answer carries it once. */
export async function issueToken(appId: number, expiresInDays: number): Promise<IssuedToken> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/admin/apps/{appId}/tokens', 'post'>>(
      api().client.POST('/api/v1/admin/apps/{appId}/tokens', {
        params: { path: { appId } },
        body: { expires_in_days: expiresInDays },
      }),
    )
  ).data;
}

/** Rotates a token: a new one comes back once, the old one works for `graceDays` more days. */
export async function rotateToken(
  tokenId: string,
  graceDays: number = TOKEN_ROTATE_GRACE_DAYS,
): Promise<IssuedToken> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/admin/tokens/{tokenId}/rotate', 'post'>>(
      api().client.POST('/api/v1/admin/tokens/{tokenId}/rotate', {
        params: { path: { tokenId } },
        body: { grace_days: graceDays },
      }),
    )
  ).data;
}

/** Revokes a token: its live agent sessions end within 5 s (docs/16 §2.1). */
export async function revokeToken(tokenId: string, reason: string): Promise<void> {
  await expectNoContent(
    api().client.POST('/api/v1/admin/tokens/{tokenId}/revoke', {
      params: { path: { tokenId } },
      body: { reason },
    }),
  );
}

/**
 * True when a live (active or rotating) token expires within TOKEN_EXPIRY_WARN_DAYS days from
 * `now` (the boundary itself included); false for tokens without expiry, already expired or
 * revoked.
 */
export function expiresSoon(token: AgentToken, now: number): boolean {
  if (token.state !== 'active' && token.state !== 'rotating') {
    return false;
  }
  if (token.expires_at === null) {
    return false;
  }
  const at = Date.parse(token.expires_at);
  if (!Number.isFinite(at) || at <= now) {
    return false;
  }
  return at - now <= TOKEN_EXPIRY_WARN_DAYS * DAY_MS;
}
