// SPDX-License-Identifier: Apache-2.0
//
// Error normalisation (docs/14 §2.6, DSN-14-05, docs/15 §6.7, §11). Every failed call becomes an
// ApiError with the server's code, message, details and request id, the HTTP status and, for
// 429, the Retry-After seconds. The error body is not part of the OpenAPI contract (WO-23: the
// lint covers 2xx only), so it is validated here with zod; anything else is "invalid_response".
import { z } from '../app/zod';

/** CSRF failure codes: `csrf_invalid` (docs/14, server) and `csrf_failed` (docs/16 §7.6). */
export const CSRF_ERROR_CODES: ReadonlySet<string> = new Set(['csrf_invalid', 'csrf_failed']);

/** docs/14 §2.6 error body (server ErrorBody.java). */
export const ErrorBody = z.object({
  error: z.object({
    code: z.string().min(1).max(64),
    message: z.string().max(1024),
    details: z
      .array(z.object({ field: z.string().max(256).nullish(), reason: z.string().max(256) }))
      .max(20)
      .nullish(),
    request_id: z.string().max(128).nullish(),
  }),
});

export interface ErrorDetail {
  readonly field: string | null;
  readonly reason: string;
}

/** What kind of failure: an HTTP error response, no response at all, or an unreadable one. */
export type ApiErrorKind = 'http' | 'network' | 'invalid_response';

export interface ApiErrorInit {
  readonly kind: ApiErrorKind;
  readonly status: number;
  readonly code: string;
  readonly message: string;
  readonly details?: readonly ErrorDetail[];
  readonly requestId?: string | null;
  readonly retryAfterSeconds?: number | null;
}

/**
 * A failed API call. `message` is the server's English message (fixed for 5xx); screens show
 * their own i18n text chosen by `status` and `code` (errorHint) together with `requestId`.
 */
export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status: number;
  readonly code: string;
  readonly details: readonly ErrorDetail[];
  readonly requestId: string | null;
  readonly retryAfterSeconds: number | null;

  constructor(init: ApiErrorInit) {
    super(init.message);
    this.name = 'ApiError';
    this.kind = init.kind;
    this.status = init.status;
    this.code = init.code;
    this.details = init.details ?? [];
    this.requestId = init.requestId ?? null;
    this.retryAfterSeconds = init.retryAfterSeconds ?? null;
  }

  /** True when a detail has this reason (and field, if given). */
  hasDetail(reason: string, field?: string): boolean {
    return this.details.some(
      (detail) => detail.reason === reason && (field === undefined || detail.field === field),
    );
  }
}

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError;
}

/** Retry-After as seconds: delta seconds or an HTTP date (RFC 9110 §10.2.3). */
export function parseRetryAfter(header: string | null, now: number = Date.now()): number | null {
  if (header === null || header.trim() === '') {
    return null;
  }
  const trimmed = header.trim();
  if (/^\d{1,7}$/.test(trimmed)) {
    return Number(trimmed);
  }
  const date = Date.parse(trimmed);
  return Number.isNaN(date) ? null : Math.max(0, Math.ceil((date - now) / 1000));
}

/** Builds an ApiError from a non-2xx response and its parsed body (JSON object or text). */
export function toApiError(response: Response, body: unknown): ApiError {
  const retryAfterSeconds = parseRetryAfter(response.headers.get('Retry-After'));
  const parsed = ErrorBody.safeParse(body);
  if (!parsed.success) {
    return new ApiError({
      kind: 'invalid_response',
      status: response.status,
      code: response.status >= 500 ? 'internal' : 'invalid_response',
      message: `HTTP ${String(response.status)}`,
      retryAfterSeconds,
    });
  }
  const { code, message, details, request_id: requestId } = parsed.data.error;
  return new ApiError({
    kind: 'http',
    status: response.status,
    code,
    message,
    details: (details ?? []).map((d) => ({ field: d.field ?? null, reason: d.reason })),
    requestId: requestId ?? null,
    retryAfterSeconds,
  });
}

/** An ApiError for a request that got no response (offline, DNS, aborted). */
export function networkError(): ApiError {
  return new ApiError({ kind: 'network', status: 0, code: 'network', message: 'Network error' });
}

/**
 * What the shared ErrorState component needs (status, code, request id, Retry-After; docs/15
 * §6.7). The component chooses the translated message and the next action from it; the server's
 * English message is not shown (5xx messages are fixed, C-132). A network failure has status 0.
 */
export interface ErrorInfo {
  readonly status?: number;
  readonly code?: string;
  readonly requestId?: string;
  readonly retryAfterSeconds?: number;
}

/** ErrorInfo of any thrown value (ApiError or something unexpected). */
export function errorInfo(error: unknown): ErrorInfo {
  if (!isApiError(error)) {
    return { status: 0 };
  }
  const info: { -readonly [K in keyof ErrorInfo]: ErrorInfo[K] } = { status: error.status };
  if (error.kind !== 'network') {
    info.code = error.code;
  }
  if (error.requestId !== null) {
    info.requestId = error.requestId;
  }
  if (error.retryAfterSeconds !== null) {
    info.retryAfterSeconds = error.retryAfterSeconds;
  }
  return info;
}

/** No automatic retry for client errors and rate limits (C-176: no retry loop on 429). */
export function isRetryable(error: unknown): boolean {
  if (!isApiError(error)) {
    return false;
  }
  return error.kind === 'network' || error.status === 502 || error.status === 503;
}
