// SPDX-License-Identifier: Apache-2.0
//
// Audit log `/admin/audit` (docs/15 §9.10, docs/16 §9.6, §11, WO-39): the records of the global
// range with exact actor, action, target type and target id filters (URL keys, at most 256
// characters each), newest first in cursor pages of 500, the before/after of a record as JSON text
// (CodeBlock) and the server's CSV of the same question (at most 10 exports a minute: a 429 says
// when to retry). Actor names, reasons, request ids and target names are text (SEC-60). For
// administrators (`audit.read`).
import { useInfiniteQuery, type InfiniteData } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
  auditKeys,
  exportAudit,
  listAudit,
  type AuditFilter,
  type AuditRecord,
} from '../../../api/admin/audit';
import type { Enveloped } from '../../../api/client';
import { errorInfo, isApiError } from '../../../api/errors';
import { untrusted } from '../../../api/untrusted';
import { CodeBlock } from '../../../components/CodeBlock';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { ErrorState } from '../../../components/ErrorState';
import { SafeText } from '../../../components/SafeText';
import { AdminAuditQ } from '../../../state/urlState';
import { useTimeRange } from '../../../state/useTimeRange';
import { useUrlState } from '../../../state/useUrlState';
import { MISSING_DASH } from '../../../utils/missing';
import { TimeCell } from '../common/cells';

import { auditResultLabel, actorTypeLabel } from './labels';

import '../common/admin.css';

type ScreenState = 'loading' | 'empty' | 'error' | 'ready';

const FILTER_FIELDS = ['actor', 'action', 'target_type', 'target_id'] as const;
type FilterField = (typeof FILTER_FIELDS)[number];

/** A record's before/after as indented JSON text (null when absent). */
function jsonText(value: unknown): string | null {
  if (value === null || value === undefined) {
    return null;
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return null;
  }
}

export function AdminAuditPage() {
  const { t } = useTranslation(['common', 'admin']);
  const { state, set } = useUrlState(AdminAuditQ);
  const time = useTimeRange(AdminAuditQ);
  const range = time.queryRange;
  const filter: AuditFilter = useMemo(
    () => ({
      from: range.from,
      to: range.to,
      actor: state.actor,
      action: state.action,
      targetType: state.target_type,
      targetId: state.target_id,
    }),
    [range.from, range.to, state.actor, state.action, state.target_type, state.target_id],
  );
  const [draft, setDraft] = useState<Record<FilterField, string> | null>(null);
  const shownDraft: Record<FilterField, string> = draft ?? {
    actor: state.actor ?? '',
    action: state.action ?? '',
    target_type: state.target_type ?? '',
    target_id: state.target_id ?? '',
  };
  const [opened, setOpened] = useState<string | null>(null);
  const [csvBusy, setCsvBusy] = useState(false);
  const [csvError, setCsvError] = useState<unknown>(null);

  const query = useInfiniteQuery<
    Enveloped<AuditRecord[]>,
    Error,
    InfiniteData<Enveloped<AuditRecord[]>, string | null>,
    ReturnType<typeof auditKeys.list>,
    string | null
  >({
    queryKey: auditKeys.list(filter),
    queryFn: ({ pageParam, signal }) => listAudit(filter, pageParam, signal),
    initialPageParam: null,
    getNextPageParam: (last) =>
      last.meta.next_cursor === null || last.meta.next_cursor === undefined
        ? undefined
        : last.meta.next_cursor,
    placeholderData: (previous) => previous,
  });
  const rows = useMemo(() => query.data?.pages.flatMap((page) => page.data) ?? [], [query.data]);
  const openedRecord = rows.find((row) => row.id === opened) ?? null;

  const applyFilters = (): void => {
    const value = (text: string) => (text.trim() === '' ? undefined : untrusted(text.trim()));
    if (
      set({
        actor: value(shownDraft.actor),
        action: value(shownDraft.action),
        target_type: value(shownDraft.target_type),
        target_id: value(shownDraft.target_id),
      })
    ) {
      setDraft(null);
    }
  };

  const runExport = async (): Promise<void> => {
    setCsvBusy(true);
    setCsvError(null);
    try {
      await exportAudit(filter);
    } catch (error) {
      setCsvError(error);
    } finally {
      setCsvBusy(false);
    }
  };

  const columns: DataTableColumn<AuditRecord>[] = [
    {
      id: 'ts',
      header: t('admin:audit.column.ts'),
      value: (row) => row.ts,
      width: 170,
      render: (row) => <TimeCell iso={row.ts} />,
    },
    {
      id: 'actor',
      header: t('admin:audit.column.actor'),
      value: (row) => row.actor.name ?? row.actor.id,
      width: 180,
      render: (row) => (
        <span data-actor-type={row.actor.type}>
          <span className="admin-muted">{actorTypeLabel(t, row.actor.type)} </span>
          {row.actor.name !== null ? (
            <SafeText value={row.actor.name} data-testid="admin-audit-actor" />
          ) : row.actor.id !== null ? (
            <span className="admin-input--mono">{row.actor.id}</span>
          ) : null}
        </span>
      ),
    },
    {
      id: 'ip',
      header: t('admin:audit.column.ip'),
      value: (row) => row.ip,
      width: 130,
      mono: true,
    },
    {
      id: 'action',
      header: t('admin:audit.column.action'),
      value: (row) => row.action,
      width: 200,
      mono: true,
    },
    {
      id: 'target',
      header: t('admin:audit.column.target'),
      value: (row) => row.target.name ?? row.target.id,
      width: 220,
      render: (row) =>
        row.target.type === null && row.target.id === null && row.target.name === null ? (
          <span className="admin-muted">{MISSING_DASH}</span>
        ) : (
          <span>
            {row.target.type === null ? null : (
              <span className="admin-muted">{row.target.type} </span>
            )}
            {row.target.name !== null ? (
              <SafeText value={row.target.name} data-testid="admin-audit-target" />
            ) : (
              <span className="admin-input--mono">{row.target.id}</span>
            )}
          </span>
        ),
    },
    {
      id: 'result',
      header: t('admin:audit.column.result'),
      value: (row) => row.result,
      width: 100,
      render: (row) => <span data-result={row.result}>{auditResultLabel(t, row.result)}</span>,
    },
    {
      id: 'request_id',
      header: t('admin:audit.column.requestId'),
      value: (row) => row.request_id,
      width: 160,
      render: (row) =>
        row.request_id === null ? (
          <span className="admin-muted">{MISSING_DASH}</span>
        ) : (
          <SafeText value={row.request_id} mono />
        ),
    },
    {
      id: 'details',
      header: t('admin:audit.column.details'),
      value: () => null,
      width: 100,
      hideable: false,
      render: (row) => (
        <button
          type="button"
          className="control-button"
          aria-expanded={opened === row.id}
          onClick={() => {
            setOpened(opened === row.id ? null : row.id);
          }}
          data-testid="admin-audit-open"
        >
          {opened === row.id ? t('admin:audit.hide') : t('admin:audit.show')}
        </button>
      ),
    },
  ];

  let screenState: ScreenState;
  if (query.isPending) {
    screenState = 'loading';
  } else if (query.isError) {
    screenState = 'error';
  } else if (rows.length === 0) {
    screenState = 'empty';
  } else {
    screenState = 'ready';
  }
  const filtered = FILTER_FIELDS.some((field) => state[field] !== undefined);
  const csvRetry =
    isApiError(csvError) && csvError.status === 429 ? (csvError.retryAfterSeconds ?? null) : null;

  return (
    <section
      className="admin-page"
      aria-labelledby="admin-audit-title"
      aria-busy={screenState === 'loading' || undefined}
      data-testid="admin-audit"
      data-state={screenState}
    >
      <header className="admin-page__header">
        <h1 id="admin-audit-title" className="admin-page__title">
          {t('admin:audit.title')}
        </h1>
      </header>
      <div className="admin-toolbar" role="group" aria-label={t('admin:audit.filters')}>
        {FILTER_FIELDS.map((field) => (
          <label key={field} className="admin-field admin-field--inline">
            <span className="admin-field__label">
              {field === 'actor'
                ? t('admin:audit.filter.actor')
                : field === 'action'
                  ? t('admin:audit.filter.action')
                  : field === 'target_type'
                    ? t('admin:audit.filter.targetType')
                    : t('admin:audit.filter.targetId')}
            </span>
            <input
              className="admin-input"
              type="text"
              maxLength={256}
              value={shownDraft[field]}
              onChange={(event) => {
                setDraft({ ...shownDraft, [field]: event.target.value });
              }}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  event.preventDefault();
                  applyFilters();
                }
              }}
              data-testid={`admin-audit-filter-${field}`}
            />
          </label>
        ))}
        <button
          type="button"
          className="control-button control-button--primary"
          onClick={applyFilters}
          data-testid="admin-audit-apply"
        >
          {t('admin:audit.apply')}
        </button>
        {filtered ? (
          <button
            type="button"
            className="control-button"
            onClick={() => {
              setDraft(null);
              set({
                actor: undefined,
                action: undefined,
                target_type: undefined,
                target_id: undefined,
              });
            }}
            data-testid="admin-audit-clear"
          >
            {t('admin:audit.clear')}
          </button>
        ) : null}
      </div>
      {csvError === null ? null : csvRetry !== null ||
        (isApiError(csvError) && csvError.status === 429) ? (
        <p className="admin-error" role="alert" data-testid="admin-audit-export-error">
          {csvRetry === null
            ? t('admin:audit.exportRateLimited')
            : t('admin:audit.exportRetry', { seconds: csvRetry })}
        </p>
      ) : (
        <ErrorState compact error={errorInfo(csvError)} data-testid="admin-audit-export-error" />
      )}
      {screenState === 'error' ? (
        <ErrorState
          error={errorInfo(query.error)}
          onRetry={() => {
            void query.refetch();
          }}
          data-testid="admin-audit-error"
        />
      ) : (
        <DataTable
          tableId="admin-audit"
          caption={t('admin:audit.caption')}
          columns={columns}
          rows={rows}
          rowKey={(row) => row.id}
          dataComplete={!query.hasNextPage}
          density="dense"
          loading={screenState === 'loading'}
          toolbar={
            <button
              type="button"
              className="control-button"
              disabled={csvBusy}
              onClick={() => {
                void runExport();
              }}
              data-testid="admin-audit-export"
            >
              {csvBusy ? t('admin:audit.exporting') : t('admin:audit.export')}
            </button>
          }
          empty={{
            title: t('admin:audit.emptyTitle'),
            description: filtered
              ? t('admin:audit.emptyFiltered')
              : t('admin:audit.emptyDescription'),
          }}
          rowAttributes={(row) => ({
            'data-action': row.action,
            'data-result': row.result,
          })}
        />
      )}
      {query.hasNextPage ? (
        <div className="admin-actions">
          <button
            type="button"
            className="control-button"
            disabled={query.isFetchingNextPage}
            onClick={() => {
              void query.fetchNextPage();
            }}
            data-testid="admin-audit-more"
          >
            {t('admin:audit.more')}
          </button>
        </div>
      ) : null}
      {openedRecord === null ? null : (
        <section
          className="admin-panel"
          aria-label={t('admin:audit.detailTitle')}
          data-testid="admin-audit-detail"
          data-record-id={openedRecord.id}
        >
          <h2 className="admin-panel__title">{t('admin:audit.detailTitle')}</h2>
          {openedRecord.reason === null ? null : (
            <p>
              <span className="admin-muted">{t('admin:audit.reason')} </span>
              <SafeText value={openedRecord.reason} maxLines={4} />
            </p>
          )}
          <CodeBlock
            value={jsonText(openedRecord.before)}
            language="text"
            label={t('admin:audit.before')}
            data-testid="admin-audit-before"
          />
          <CodeBlock
            value={jsonText(openedRecord.after)}
            language="text"
            label={t('admin:audit.after')}
            data-testid="admin-audit-after"
          />
        </section>
      )}
    </section>
  );
}
