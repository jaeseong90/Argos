// SPDX-License-Identifier: Apache-2.0
//
// One configuration change with its per-instance results (docs/15 §9.10 "인스턴스별 결과 표(성공·
// 실패·거부 키·미접속, 10초 내 갱신)", 05 §2.11-5, T-ACPT-18): the change's state, kind, reason and
// times, then a row per target (applied, partial, rejected with the rejected keys, offline,
// pending). Updates come from the WebSocket `admin` topic (a push makes the screen re-read the
// change) and from 1-second polling while the change is applying, which stops when it is final or
// after 30 s (the server marks a push without answer offline then). "Revert to the previous
// configuration" asks once, with a reason, and opens the new change.
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
  changeRefetchInterval,
  configKeys,
  configProblem,
  getConfigChange,
  isFinalChange,
  revertConfigChange,
  type ConfigChange,
  type ConfigChangeResult,
} from '../../../api/admin/config';
import { errorInfo } from '../../../api/errors';
import type { Untrusted } from '../../../api/untrusted';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { SafeText } from '../../../components/SafeText';
import { useTopic } from '../../../realtime/hooks';
import { TimeCell } from '../common/cells';
import { ConfirmDialog } from '../common/ConfirmDialog';

import {
  changeKindLabel,
  changeStatusLabel,
  configProblemLabel,
  resultStatusLabel,
} from './labels';
import { reasonProblem } from './model';

export interface ChangeResultsProps {
  readonly changeId: string;
  readonly names: ReadonlyMap<number, Untrusted>;
  /** Another change to show (the revert just created). */
  readonly onOpenChange: (changeId: string) => void;
  /** The change's state moved (the history and the current values are re-read). */
  readonly onSettled: () => void;
}

/** The rejected keys of a result: the agent's refusals and the keys the server did not push. */
function rejectedKeys(result: ConfigChangeResult): string[] {
  const keys = new Set<string>(result.rejected_keys);
  for (const entry of result.rejected) {
    keys.add(entry.key);
  }
  return [...keys].sort();
}

export function ChangeResults({ changeId, names, onOpenChange, onSettled }: ChangeResultsProps) {
  const { t } = useTranslation(['common', 'admin']);
  const queryClient = useQueryClient();
  // Polling starts when this change is shown (the apply that preceded it or the history click);
  // the interval callback runs outside render, so it may read the clock.
  const tracking = useRef<{ id: string; since: number } | null>(null);
  useEffect(() => {
    tracking.current = { id: changeId, since: Date.now() };
  }, [changeId]);
  const query = useQuery({
    queryKey: configKeys.change(changeId),
    queryFn: ({ signal }) => getConfigChange(changeId, signal),
    refetchInterval: (q) => {
      const now = Date.now();
      const start = tracking.current;
      return changeRefetchInterval(q.state.data, start?.id === changeId ? start.since : now, now);
    },
    refetchIntervalInBackground: false,
  });
  const change = query.data;
  // A state change (applying → applied) re-reads the history and the current values.
  const status = change?.status;
  const lastStatus = useRef<string | undefined>(undefined);
  const settledRef = useRef(onSettled);
  useEffect(() => {
    settledRef.current = onSettled;
  });
  useEffect(() => {
    if (status !== undefined && lastStatus.current !== undefined && status !== lastStatus.current) {
      settledRef.current();
    }
    lastStatus.current = status;
  }, [status]);

  // The `admin` topic pushes every result of this change (14 §5.2): re-read it at once.
  useTopic(
    'admin',
    change !== undefined && isFinalChange(change) ? null : { change_id: changeId },
    {
      onData: () => {
        void queryClient.invalidateQueries({ queryKey: configKeys.change(changeId) });
      },
      onResync: () => {
        void queryClient.invalidateQueries({ queryKey: configKeys.change(changeId) });
      },
    },
  );

  const [reverting, setReverting] = useState(false);
  const [revertReason, setRevertReason] = useState('');
  const [revertPending, setRevertPending] = useState(false);
  const [revertError, setRevertError] = useState<unknown>(null);

  const runRevert = async (target: ConfigChange): Promise<void> => {
    setRevertPending(true);
    setRevertError(null);
    try {
      const created = await revertConfigChange(target.change_id, revertReason.trim());
      setReverting(false);
      setRevertReason('');
      queryClient.setQueryData(configKeys.change(created.change_id), created);
      onSettled();
      onOpenChange(created.change_id);
    } catch (error) {
      setRevertError(error);
    } finally {
      setRevertPending(false);
    }
  };

  if (query.isPending) {
    return <LoadingState variant="table" data-testid="admin-config-change-loading" />;
  }
  if (query.isError) {
    return (
      <ErrorState
        error={errorInfo(query.error)}
        onRetry={() => {
          void query.refetch();
        }}
        data-testid="admin-config-change-error"
      />
    );
  }
  const shown = query.data;
  const final = isFinalChange(shown);
  const polling = !final;

  const columns: DataTableColumn<ConfigChangeResult>[] = [
    {
      id: 'instance',
      header: t('admin:config.results.column.instance'),
      value: (row) => names.get(row.instance_id) ?? String(row.instance_id),
      width: 200,
      render: (row) => {
        const name = names.get(row.instance_id);
        return name === undefined ? (
          <span>#{row.instance_id}</span>
        ) : (
          <SafeText value={name} data-testid="admin-config-result-instance" />
        );
      },
    },
    {
      id: 'status',
      header: t('admin:config.results.column.status'),
      value: (row) => row.status,
      width: 130,
      render: (row) => (
        <span
          className={`admin-badge admin-badge--${
            row.status === 'applied'
              ? 'info'
              : row.status === 'rejected' || row.status === 'partial'
                ? 'error'
                : 'warn'
          }`}
          data-testid="admin-config-result-status"
          data-status={row.status}
        >
          {resultStatusLabel(t, row.status)}
        </span>
      ),
    },
    {
      id: 'rejected_keys',
      header: t('admin:config.results.column.rejectedKeys'),
      value: (row) => {
        const keys = rejectedKeys(row);
        return keys.length === 0 ? null : keys.join(', ');
      },
      width: 260,
      mono: true,
    },
    {
      id: 'applied_at',
      header: t('admin:config.results.column.appliedAt'),
      value: (row) => row.applied_at,
      width: 170,
      render: (row) => <TimeCell iso={row.applied_at} />,
    },
    {
      id: 'error',
      header: t('admin:config.results.column.error'),
      value: (row) => row.error,
      width: 180,
      defaultHidden: row0Error(shown.results),
    },
  ];

  const problem = revertError === null ? null : configProblem(revertError);
  const reasonIssue = reasonProblem(revertReason);
  return (
    <section
      className="admin-panel"
      aria-labelledby="admin-config-change-title"
      data-testid="admin-config-change"
      data-change-id={shown.change_id}
      data-status={shown.status}
      data-polling={polling ? 'true' : 'false'}
    >
      <h2 id="admin-config-change-title" className="admin-panel__title">
        {t('admin:config.results.title')}
      </h2>
      <dl className="admin-dl">
        <dt>{t('admin:config.results.status')}</dt>
        <dd data-testid="admin-config-change-status">{changeStatusLabel(t, shown.status)}</dd>
        <dt>{t('admin:config.results.kind')}</dt>
        <dd>{changeKindLabel(t, shown)}</dd>
        <dt>{t('admin:config.results.reason')}</dt>
        <dd>
          <SafeText value={shown.reason} maxLines={3} data-testid="admin-config-change-reason" />
        </dd>
        <dt>{t('admin:config.results.requestedAt')}</dt>
        <dd>
          <TimeCell iso={shown.requested_at} />
        </dd>
        <dt>{t('admin:config.results.appliedAt')}</dt>
        <dd>
          <TimeCell iso={shown.applied_at} />
        </dd>
      </dl>
      {polling ? (
        <p className="admin-muted" role="status" data-testid="admin-config-polling">
          {t('admin:config.results.waiting')}
        </p>
      ) : null}
      {shown.results.length === 0 ? (
        <p className="admin-muted" data-testid="admin-config-results-none">
          {shown.status === 'previewed'
            ? t('admin:config.results.notApplied')
            : t('admin:config.results.noTargets')}
        </p>
      ) : (
        <DataTable
          tableId="admin-config-results"
          caption={t('admin:config.results.caption')}
          columns={columns}
          rows={shown.results}
          rowKey={(row) => String(row.instance_id)}
          dataComplete
          density="dense"
          rowAttributes={(row) => ({ 'data-status': row.status })}
        />
      )}
      {shown.status === 'applied' ? (
        <div className="admin-actions">
          <button
            type="button"
            className="control-button"
            onClick={() => {
              setRevertError(null);
              setReverting(true);
            }}
            data-testid="admin-config-revert"
          >
            {t('admin:config.revert.action')}
          </button>
        </div>
      ) : null}
      <ConfirmDialog
        open={reverting}
        title={t('admin:config.revert.title')}
        impact={t('admin:config.revert.impact')}
        okLabel={t('admin:config.revert.ok')}
        okDisabled={reasonIssue !== null}
        pending={revertPending}
        onOk={() => {
          void runRevert(shown);
        }}
        onCancel={() => {
          setReverting(false);
          setRevertReason('');
        }}
      >
        <label className="admin-field">
          <span className="admin-field__label">{t('admin:config.reason.label')}</span>
          <textarea
            className="admin-input"
            rows={2}
            value={revertReason}
            onChange={(event) => {
              setRevertReason(event.target.value);
            }}
            aria-invalid={reasonIssue === 'tooLong' || undefined}
            data-testid="admin-config-revert-reason"
          />
        </label>
        {problem === null ? null : problem.kind === 'other' ? (
          <ErrorState
            compact
            error={errorInfo(revertError)}
            data-testid="admin-config-revert-error"
          />
        ) : (
          <p className="admin-error" role="alert" data-testid="admin-config-revert-error">
            {configProblemLabel(t, problem.kind, problem.key)}
          </p>
        )}
      </ConfirmDialog>
    </section>
  );
}

/** The error column is hidden unless a result has an error. */
function row0Error(results: readonly ConfigChangeResult[]): boolean {
  return !results.some((result) => result.error !== null);
}
