// SPDX-License-Identifier: Apache-2.0
//
// Remote commands of an instance (docs/14 §4.10, docs/16 §5, 05 §2.7-4, §2.6-4, WO-37) through the
// generated client: request a command, poll its state, read its result (format=json parses thread
// dumps and stacks into threads[]/deadlocks[], format=text returns the raw text) and list the
// commands of an instance. No hand-written requests (03 §3.3).
//
// The `command` WS topic is ADMIN-only (ADR-0010 C-231 (3)), so every role polls GET
// /commands/{id} every second until the command ends (done, failed, expired) or its deadline
// passes: the TTL of a queued command (expires_at, 60 s) plus the server's result timeout of a sent
// one (120 s) plus a margin. Results never change and every result read is audited
// (COMMAND_RESULT_VIEWED, SENSITIVE_VIEW), so a result is fetched once and kept (staleTime
// Infinity) instead of being refetched.
//
// Query keys live here under the `commands` prefix; a new command invalidates the instance's list.
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';

import { api, unwrap, type EnvelopeOf } from './client';
import { isApiError } from './errors';
import type { components } from './generated/schema';
import { untrusted, type Untrusted } from './untrusted';

export type CommandQueued = components['schemas']['CommandQueued'];
export type CommandView = components['schemas']['CommandView'];
export type CommandResultView = components['schemas']['CommandResultView'];
export type CommandThread = components['schemas']['CommandThread'];
export type CommandDeadlock = components['schemas']['CommandDeadlock'];
/** The instance a command tab shows (GET /instances/{id}). */
export type CommandInstance = components['schemas']['Instance'];

/** The closed command list (docs/14 §4.10, server CommandKind). */
export const COMMAND_TYPES = [
  'thread_dump',
  'thread_stack',
  'self_diagnosis',
  'reload_config',
  'env_dump',
] as const;
export type CommandType = (typeof COMMAND_TYPES)[number];

/** Commands an operator may request (SEC-21); the others need an administrator. */
export const OPERATOR_COMMANDS: readonly CommandType[] = [
  'thread_dump',
  'thread_stack',
  'self_diagnosis',
];
/** Commands only administrators request, and whose results only administrators read (SEC-21). */
export const ADMIN_COMMANDS: readonly CommandType[] = ['reload_config', 'env_dump'];

/** Command states (docs/14 §4.10). */
export const COMMAND_STATUSES = ['queued', 'sent', 'done', 'failed', 'expired'] as const;
export type CommandStatus = (typeof COMMAND_STATUSES)[number];

/** States after which a command never changes. */
const TERMINAL: ReadonlySet<string> = new Set(['done', 'failed', 'expired']);

/** Why a command failed or expired (server CommandRouter `error`, ADR-0010 C-231 (3)). */
export const COMMAND_END_REASONS = [
  'ttl',
  'timeout',
  'session_ended',
  'rejected',
  'cooldown',
  'agent_failed',
  'agent_expired',
  'invalid_result',
] as const;
export type CommandEndReason = (typeof COMMAND_END_REASONS)[number] | 'unknown';

/** Poll interval of a running command (C-231 (3): operators have no WS topic). */
export const COMMAND_POLL_MS = 1_000;
/** The server's result timeout of a sent command (argos.commands.result-timeout, 120 s). */
export const COMMAND_RESULT_TIMEOUT_MS = 120_000;
/** Polling continues this long past the last server deadline (tick and clock skew). */
export const COMMAND_POLL_MARGIN_MS = 5_000;
/** Commands of an instance listed at most (docs/14 §4.10 `limit`). */
export const COMMAND_LIST_MAX = 100;

export function isCommandType(value: string): value is CommandType {
  return (COMMAND_TYPES as readonly string[]).includes(value);
}

export function isTerminal(status: string): boolean {
  return TERMINAL.has(status);
}

/** The end reason of a failed or expired command (`unknown` for a code this UI does not know). */
export function endReason(error: string | null): CommandEndReason | null {
  if (error === null) {
    return null;
  }
  return (COMMAND_END_REASONS as readonly string[]).includes(error)
    ? (error as CommandEndReason)
    : 'unknown';
}

/**
 * The last moment worth polling: a queued command expires at `expires_at` (TTL) unless sent, and a
 * sent one gets the server's result timeout from then (sent before `expires_at`), plus a margin.
 */
export function pollDeadline(view: Pick<CommandView, 'expires_at'>): number {
  const expires = Date.parse(view.expires_at);
  return (
    (Number.isNaN(expires) ? Date.now() : expires) +
    COMMAND_RESULT_TIMEOUT_MS +
    COMMAND_POLL_MARGIN_MS
  );
}

/**
 * The refetch interval of a command's state: every second while it runs, none once it ended or
 * its deadline passed (the screen then says the agent did not answer).
 */
export function commandRefetchInterval(
  view: CommandView | undefined,
  now: number = Date.now(),
): number | false {
  if (view === undefined) {
    return COMMAND_POLL_MS;
  }
  if (isTerminal(view.status) || now > pollDeadline(view)) {
    return false;
  }
  return COMMAND_POLL_MS;
}

/**
 * The requester's user name (`requested_by_name`, WO-37: a deleted user's anonymised name), or
 * null when the server does not send it. User names are untrusted input (docs/16 §9.1).
 */
export function requesterName(view: CommandView): Untrusted | null {
  const name: unknown = (view as { readonly requested_by_name?: unknown }).requested_by_name;
  return typeof name === 'string' && name !== '' ? untrusted(name) : null;
}

/**
 * Whether the signed-in user may read a command's result (docs/14 §4.10, SEC-50): the requester
 * or an administrator, and only administrators for administrator commands.
 */
export function canReadResult(
  view: Pick<CommandView, 'requested_by' | 'type'>,
  userId: string | null,
  isAdmin: boolean,
): boolean {
  if (isAdmin) {
    return true;
  }
  if ((ADMIN_COMMANDS as readonly string[]).includes(view.type)) {
    return false;
  }
  return userId !== null && view.requested_by === userId;
}

/** What a failed request means for the user (docs/14 §4.10, §2.6). */
export type CommandErrorKind =
  | 'instance_offline'
  | 'busy'
  | 'rate_limited'
  | 'secret_unavailable'
  | 'forbidden'
  | 'not_owner'
  | 'not_found'
  | 'no_result'
  | 'network'
  | 'other';

/** The kind of a command call's error. */
export function commandErrorKind(error: unknown): CommandErrorKind {
  if (!isApiError(error)) {
    return 'other';
  }
  if (error.kind === 'network') {
    return 'network';
  }
  switch (error.code) {
    case 'instance_offline':
      return 'instance_offline';
    case 'rate_limited':
      return 'rate_limited';
    case 'secret_unavailable':
      return 'secret_unavailable';
    case 'not_owner':
      return 'not_owner';
    case 'invalid_state':
      // POST: another command of the instance runs; GET result: the command has no result.
      return error.status === 409 ? 'busy' : 'other';
    default:
      break;
  }
  if (error.status === 429) {
    return 'rate_limited';
  }
  if (error.status === 403) {
    return 'forbidden';
  }
  if (error.status === 404) {
    return 'not_found';
  }
  return 'other';
}

// ---------------------------------------------------------------------------------------------
// Calls
// ---------------------------------------------------------------------------------------------

export const commandKeys = {
  all: () => ['commands'] as const,
  instance: (instanceId: number) => ['commands', 'instance', instanceId] as const,
  list: (instanceId: number, limit: number) => ['commands', 'instance', instanceId, limit] as const,
  command: (commandId: string) => ['commands', 'command', commandId] as const,
  result: (commandId: string, format: 'json' | 'text') =>
    ['commands', 'result', commandId, format] as const,
} as const;

/**
 * `POST /instances/{instanceId}/commands` (202): queues a command. thread_stack takes exactly one
 * of `thread_id`, `tx_id` (digits); the other commands take no arguments.
 */
export async function requestCommand(
  instanceId: number,
  type: CommandType,
  args?: Readonly<Record<string, string>>,
): Promise<CommandQueued> {
  const body: components['schemas']['CommandRequest'] =
    args === undefined ? { type } : { type, args: { ...args } };
  // 202 Accepted: EnvelopeOf covers 200/201 only, so the envelope type is named here.
  const result = await unwrap<components['schemas']['EnvelopeCommandQueued']>(
    api().client.POST('/api/v1/instances/{instanceId}/commands', {
      params: { path: { instanceId } },
      body,
    }),
  );
  return result.data;
}

/** `GET /commands/{commandId}`: the requester's own command or any for an administrator. */
export async function getCommand(commandId: string, signal?: AbortSignal): Promise<CommandView> {
  const result = await unwrap<EnvelopeOf<'/api/v1/commands/{commandId}', 'get'>>(
    api().client.GET('/api/v1/commands/{commandId}', {
      params: { path: { commandId } },
      ...(signal === undefined ? {} : { signal }),
    }),
  );
  return result.data;
}

/** `GET /commands/{commandId}/result?format=json|text` (audited on the server). */
export async function getCommandResult(
  commandId: string,
  format: 'json' | 'text',
  signal?: AbortSignal,
): Promise<CommandResultView> {
  const result = await unwrap<EnvelopeOf<'/api/v1/commands/{commandId}/result', 'get'>>(
    api().client.GET('/api/v1/commands/{commandId}/result', {
      params: { path: { commandId }, query: { format } },
      ...(signal === undefined ? {} : { signal }),
    }),
  );
  return result.data;
}

/** `GET /instances/{instanceId}/commands?limit=` (operator): the newest commands, every requester. */
export async function listInstanceCommands(
  instanceId: number,
  limit: number,
  signal?: AbortSignal,
): Promise<readonly CommandView[]> {
  const result = await unwrap<EnvelopeOf<'/api/v1/instances/{instanceId}/commands', 'get'>>(
    api().client.GET('/api/v1/instances/{instanceId}/commands', {
      params: { path: { instanceId }, query: { limit: clampLimit(limit) } },
      ...(signal === undefined ? {} : { signal }),
    }),
  );
  return result.data;
}

function clampLimit(limit: number): number {
  return Math.max(1, Math.min(COMMAND_LIST_MAX, Math.trunc(limit)));
}

// ---------------------------------------------------------------------------------------------
// Hooks
// ---------------------------------------------------------------------------------------------

/** No retry of a 4xx (not owner, not found, no result): only network errors and 502/503 once. */
function retryOnce(failureCount: number, error: Error): boolean {
  return failureCount < 1 && isApiError(error) && (error.kind === 'network' || error.status >= 502);
}

/**
 * A command's state, polled every second until it ends or its deadline passes. `commandId` null
 * disables the query.
 */
export function useCommand(commandId: string | null): UseQueryResult<CommandView> {
  return useQuery({
    queryKey: commandKeys.command(commandId ?? ''),
    queryFn: ({ signal }) => {
      if (commandId === null) {
        throw new Error('no command');
      }
      return getCommand(commandId, signal);
    },
    enabled: commandId !== null,
    refetchInterval: (query) => commandRefetchInterval(query.state.data),
    // A running command is short-lived (at most about three minutes); keep polling in a hidden
    // tab so the result is there when the user comes back.
    refetchIntervalInBackground: true,
    retry: retryOnce,
    staleTime: 0,
  });
}

/**
 * A done command's result. Fetched once and kept: results never change and every read is audited.
 * `commandId` null (or `enabled` false) disables the query.
 */
export function useCommandResult(
  commandId: string | null,
  format: 'json' | 'text' = 'json',
  enabled = true,
): UseQueryResult<CommandResultView> {
  return useQuery({
    queryKey: commandKeys.result(commandId ?? '', format),
    queryFn: ({ signal }) => {
      if (commandId === null) {
        throw new Error('no command');
      }
      return getCommandResult(commandId, format, signal);
    },
    enabled: commandId !== null && enabled,
    staleTime: Infinity,
    retry: retryOnce,
  });
}

/**
 * The newest commands of an instance (operator and above; the server answers 403 to viewers, so
 * pass `enabled` false for them). `refetchMs` refreshes the list while a command runs.
 */
export function useInstanceCommands(
  instanceId: number,
  limit: number = COMMAND_LIST_MAX,
  options: { readonly enabled?: boolean; readonly refetchMs?: number | false } = {},
): UseQueryResult<readonly CommandView[]> {
  const n = clampLimit(limit);
  return useQuery({
    queryKey: commandKeys.list(instanceId, n),
    queryFn: ({ signal }) => listInstanceCommands(instanceId, n, signal),
    enabled: options.enabled ?? true,
    refetchInterval: options.refetchMs ?? false,
    retry: retryOnce,
  });
}

export interface CommandRequestInput {
  readonly type: CommandType;
  readonly args?: Readonly<Record<string, string>>;
}

/** Requests a command of an instance; a success refreshes the instance's command list. */
export function useRequestCommand(
  instanceId: number,
): UseMutationResult<CommandQueued, unknown, CommandRequestInput> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: CommandRequestInput) => requestCommand(instanceId, input.type, input.args),
    onSettled: () => client.invalidateQueries({ queryKey: commandKeys.instance(instanceId) }),
  });
}
