// SPDX-License-Identifier: Apache-2.0
//
// Hooks shared by the commands tab, the latest-result panels, the dumps tab and the active
// transactions screen (WO-37): who may do what (docs/14 §7.1 permission keys, SEC-21, SEC-50),
// the newest result of a type the user may read, and running one command (request, then poll its
// state every second until it ends; ADR-0010 C-231 (3)).
import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useState } from 'react';

import {
  canReadResult,
  commandKeys,
  isTerminal,
  useCommand,
  useInstanceCommands,
  useRequestCommand,
  type CommandType,
  type CommandView,
} from '../../../api/commands';
import { useAuthStore } from '../../../state/authStore';
import { can, type Permission } from '../../../state/permissions';

/** The permission key of each command (docs/14 §7.1). */
export const COMMAND_PERMISSION: Readonly<Record<CommandType, Permission>> = {
  thread_dump: 'commands.thread-dump',
  thread_stack: 'commands.thread-stack',
  self_diagnosis: 'commands.self-diagnosis',
  reload_config: 'commands.config-reload',
  env_dump: 'commands.env-dump',
};

export interface CommandAccess {
  readonly userId: string | null;
  readonly isAdmin: boolean;
  /** Operator or above: may list the instance's commands (docs/14 §4.10). */
  readonly canList: boolean;
  readonly canRun: (type: CommandType) => boolean;
  readonly canRead: (view: Pick<CommandView, 'requested_by' | 'type'>) => boolean;
}

/** The signed-in user's command permissions. */
export function useCommandAccess(): CommandAccess {
  const role = useAuthStore((s) => s.role);
  const granted = useAuthStore((s) => s.permissions);
  const userId = useAuthStore((s) => s.user?.id ?? null);
  return useMemo(() => {
    const isAdmin = role === 'admin';
    return {
      userId,
      isAdmin,
      canList: can('commands.thread-dump', role, granted),
      canRun: (type: CommandType) => can(COMMAND_PERMISSION[type], role, granted),
      canRead: (view) => canReadResult(view, userId, isAdmin),
    };
  }, [role, granted, userId]);
}

export interface LatestCommand {
  /** The newest done command of the type that the user may read. */
  readonly latest: CommandView | null;
  /** A newer done command of the type that only its requester and administrators may read. */
  readonly newerUnreadable: CommandView | null;
  /** A command of the type that is still queued or sent. */
  readonly running: CommandView | null;
  readonly loading: boolean;
  readonly error: unknown;
  readonly refetch: () => void;
}

/** The latest readable result of a type in a command list (newest first). */
export function pickLatest(
  list: readonly CommandView[],
  type: CommandType,
  canRead: (view: CommandView) => boolean,
): Pick<LatestCommand, 'latest' | 'newerUnreadable' | 'running'> {
  let latest: CommandView | null = null;
  let newerUnreadable: CommandView | null = null;
  let running: CommandView | null = null;
  for (const view of list) {
    if (view.type !== type) {
      continue;
    }
    if (running === null && !isTerminal(view.status)) {
      running = view;
    }
    if (view.status !== 'done') {
      continue;
    }
    if (canRead(view)) {
      latest = view;
      break;
    }
    newerUnreadable ??= view;
  }
  return { latest, newerUnreadable, running };
}

/** The newest done command of a type the user may read, from the instance's command list. */
export function useLatestCommand(instanceId: number, type: CommandType): LatestCommand {
  const access = useCommandAccess();
  const list = useInstanceCommands(instanceId, undefined, { enabled: access.canList });
  const picked = useMemo(
    () => pickLatest(list.data ?? [], type, access.canRead),
    [list.data, type, access],
  );
  const { refetch } = list;
  return {
    ...picked,
    loading: access.canList && list.isPending,
    error: list.error,
    refetch: useCallback(() => {
      void refetch();
    }, [refetch]),
  };
}

export interface CommandRun {
  /** The command being run (or the last one), null before the first request. */
  readonly commandId: string | null;
  readonly view: CommandView | undefined;
  /** The request failed (ApiError: instance_offline, invalid_state, rate_limited …). */
  readonly requestError: unknown;
  /** The state could not be read. */
  readonly pollError: unknown;
  /** Requested and not ended yet. */
  readonly inFlight: boolean;
  readonly requesting: boolean;
  readonly run: (
    type: CommandType,
    args?: Readonly<Record<string, string>>,
  ) => Promise<string | null>;
  readonly reset: () => void;
}

/**
 * Runs commands of an instance one at a time: requests one, then follows its state (useCommand
 * polls every second). When it ends, the instance's command list is refreshed.
 */
export function useCommandRun(instanceId: number): CommandRun {
  const client = useQueryClient();
  const mutation = useRequestCommand(instanceId);
  const [commandId, setCommandId] = useState<string | null>(null);
  const command = useCommand(commandId);
  const view = command.data;
  const ended = view !== undefined && isTerminal(view.status);
  useEffect(() => {
    if (ended) {
      void client.invalidateQueries({ queryKey: commandKeys.instance(instanceId) });
    }
  }, [ended, commandId, client, instanceId]);
  const { mutateAsync, reset: resetMutation } = mutation;
  const run = useCallback(
    async (type: CommandType, args?: Readonly<Record<string, string>>): Promise<string | null> => {
      try {
        const queued = await mutateAsync(args === undefined ? { type } : { type, args });
        setCommandId(queued.command_id);
        return queued.command_id;
      } catch {
        // The error stays on the mutation (requestError).
        return null;
      }
    },
    [mutateAsync],
  );
  const reset = useCallback(() => {
    resetMutation();
    setCommandId(null);
  }, [resetMutation]);
  return {
    commandId,
    view,
    requestError: mutation.error,
    pollError: command.error,
    inFlight: commandId !== null && !ended && command.error === null,
    requesting: mutation.isPending,
    run,
    reset,
  };
}
