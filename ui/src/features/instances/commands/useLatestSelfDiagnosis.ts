// SPDX-License-Identifier: Apache-2.0
//
// The newest self diagnosis report the signed-in user may read (WO-37, PM question 3 of the plan):
// the environment tab merges its effective configuration (with each key's source: file,
// environment variable, server push …) and module states into the server's effective-config view,
// which knows only the values the server holds. Only the requester and administrators read a
// command's result (docs/14 §4.10, SEC-50); viewers cannot list commands, so for them (and while
// there is no readable result) the report is null. Reading the result is audited on the server
// (COMMAND_RESULT_VIEWED), so it is fetched only after the user asked to see it (`load`, ADR-0010
// C-234 (3): opening the tab never reads it) and then kept for the page.
import { useMemo } from 'react';

import { useCommandResult } from '../../../api/commands';
import type { Untrusted } from '../../../api/untrusted';

import { parseSelfDiagnosis } from './selfDiagnosis';
import { useLatestCommand } from './useCommands';

export interface SelfDiagnosisSummary {
  readonly effectiveConfig: readonly {
    readonly key: Untrusted;
    readonly value: Untrusted;
    /** As the agent names it (file, env, server …); null when the report gives none. */
    readonly source: Untrusted | null;
  }[];
  readonly modules: readonly {
    readonly name: Untrusted;
    readonly state: Untrusted | null;
    /** Counted errors; null when the report has none (not 0). */
    readonly errors: number | null;
    /** Why the module does not run (C-234 (1)); null when enabled. */
    readonly reason: Untrusted | null;
  }[];
}

export interface LatestSelfDiagnosis {
  readonly report: SelfDiagnosisSummary | null;
  /** When the command finished (ISO-8601), null without a report. */
  readonly at: string | null;
  /** A readable self diagnosis exists (its result is not read until `load`). */
  readonly available: boolean;
  /** The result is being read. */
  readonly loading: boolean;
}

/**
 * The latest readable self diagnosis of `instanceId`. Its result (audited) is read only while
 * `load` is true; until then `report` is null and `available` says whether there is one to show.
 */
export function useLatestSelfDiagnosis(instanceId: number, load: boolean): LatestSelfDiagnosis {
  const { latest } = useLatestCommand(instanceId, 'self_diagnosis');
  const result = useCommandResult(load ? (latest?.command_id ?? null) : null, 'json');
  const text = result.data?.text ?? null;
  const available = latest !== null;
  const loading = load && available && result.isPending;
  return useMemo(() => {
    if (!load || latest === null || result.data === undefined) {
      return { report: null, at: null, available, loading };
    }
    const parsed = parseSelfDiagnosis(text);
    if (parsed.kind === 'text') {
      return { report: null, at: latest.finished_at, available, loading };
    }
    return {
      report: {
        effectiveConfig: parsed.report.effectiveConfig,
        modules: parsed.report.modules.map((m) => ({
          name: m.name,
          state: m.state,
          errors: m.errors,
          reason: m.reason,
        })),
      },
      at: latest.finished_at,
      available,
      loading,
    };
  }, [load, latest, result.data, text, available, loading]);
}
