// SPDX-License-Identifier: Apache-2.0
//
// The generated API client (openapi-typescript types of docs/api/openapi.yaml + openapi-fetch) on
// top of the transport (CSRF, 401 refresh), with envelope unwrapping (docs/14 §2.5) and error
// normalisation (docs/14 §2.6). Screens call the functions of src/api, never fetch (03 §3.3).
import createClient, { type Client } from 'openapi-fetch';

import { ApiError, networkError, toApiError } from './errors';
import type { components, paths } from './generated/schema';
import { pageOrigin, Transport, type TransportOptions } from './transport';

/** Response meta (docs/14 §2.5, the single QueryMeta schema). */
export type ResponseMeta = components['schemas']['QueryMeta'];

/** An unwrapped success: the envelope's data and meta. */
export interface Enveloped<D> {
  readonly data: D;
  readonly meta: ResponseMeta;
}

export type ApiClient = Client<paths>;

export interface ApiRuntime {
  readonly transport: Transport;
  readonly client: ApiClient;
}

/** Creates a client and its transport; the app uses the shared `api` instance below. */
export function createApiRuntime(options: Partial<TransportOptions> = {}): ApiRuntime {
  const transport = new Transport({
    ...options,
    fetch: options.fetch ?? ((request) => globalThis.fetch(request)),
  });
  const client = createClient<paths>({
    baseUrl: options.origin ?? pageOrigin(),
    credentials: 'include',
    fetch: transport.fetch,
    headers: { Accept: 'application/json' },
  });
  return { transport, client };
}

let runtime: ApiRuntime | null = null;

/** The page-wide API runtime (created lazily so that tests can install their own first). */
export function api(): ApiRuntime {
  runtime ??= createApiRuntime();
  return runtime;
}

/** Replaces the page-wide runtime (tests and the app's bootstrap). */
export function setApiRuntime(next: ApiRuntime | null): void {
  runtime = next;
}

interface FetchResult {
  readonly data?: unknown;
  readonly error?: unknown;
  readonly response: Response;
}

type HttpMethod = 'get' | 'post' | 'put' | 'patch' | 'delete';
type JsonContent<R> = R extends { content: { 'application/json': infer J } } ? J : never;
type SuccessJson<Op> = Op extends { responses: infer R }
  ? R extends { 200: infer Ok }
    ? JsonContent<Ok>
    : R extends { 201: infer Created }
      ? JsonContent<Created>
      : never
  : never;

/**
 * The success envelope of an operation, straight from the generated `paths` (openapi-fetch's own
 * result type maps branded strings into objects, so the Untrusted brand would be lost).
 */
export type EnvelopeOf<P extends keyof paths, M extends HttpMethod> = M extends keyof paths[P]
  ? SuccessJson<paths[P][M]>
  : never;

/** Awaits a client call, mapping a rejected fetch (offline, aborted) to a network ApiError. */
async function settle<R extends FetchResult>(call: Promise<R>): Promise<R> {
  try {
    return await call;
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error;
    }
    throw networkError();
  }
}

/**
 * Unwraps an enveloped success response (`{data, meta}`) or throws an ApiError. Use for every
 * JSON operation of the contract, with its envelope type:
 * `unwrap<EnvelopeOf<'/api/v1/apps', 'get'>>(api().client.GET('/api/v1/apps'))`.
 */
export async function unwrap<E extends { data: unknown }>(
  call: Promise<FetchResult>,
): Promise<Enveloped<E['data']>> {
  const result = await settle(call);
  if (!result.response.ok) {
    throw toApiError(result.response, result.error);
  }
  const body = result.data;
  if (typeof body !== 'object' || body === null || !('data' in body) || !('meta' in body)) {
    throw new ApiError({
      kind: 'invalid_response',
      status: result.response.status,
      code: 'invalid_response',
      message: 'The response has no data/meta envelope',
    });
  }
  return body as Enveloped<E['data']>;
}

/** For operations that answer 204 (logout, password change): resolves or throws an ApiError. */
export async function expectNoContent(call: Promise<FetchResult>): Promise<void> {
  const result = await settle(call);
  if (!result.response.ok) {
    throw toApiError(result.response, result.error);
  }
}
