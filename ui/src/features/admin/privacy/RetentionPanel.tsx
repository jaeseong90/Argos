// SPDX-License-Identifier: Apache-2.0
//
// Data retention panel of `/admin/privacy` (docs/14 §4.14 `/admin/settings/retention`, docs/13 §8,
// WO-39 plan PM question 4): the effective days of every data kind with their source (settings
// or the server configuration) and the ClickHouse tables' TTL; a change sends only the edited
// values, and a shorter value asks for an explicit confirmation first (data outside the new
// retention is dropped). The result says whether the TTLs were applied now or at the next start
// and how many partitions are queued for dropping.
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
  getRetention,
  isValidRetentionDays,
  RETENTION_FIELDS,
  RETENTION_MAX_DAYS,
  RETENTION_MIN_DAYS,
  retentionChanges,
  retentionKeys,
  shortenedFields,
  updateRetention,
  type RetentionField,
  type RetentionUpdated,
  type RetentionValues,
} from '../../../api/admin/retention';
import { errorInfo } from '../../../api/errors';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { ConfirmDialog } from '../common/ConfirmDialog';

import { retentionFieldLabel } from './labels';

type Draft = Record<RetentionField, string>;

function draftOf(values: RetentionValues): Draft {
  const out = {} as Draft;
  for (const field of RETENTION_FIELDS) {
    out[field] = String(values[field]);
  }
  return out;
}

/** The draft as numbers, or null when a field is not a valid number of days. */
function parsedDraft(draft: Draft): Record<RetentionField, number> | null {
  const out = {} as Record<RetentionField, number>;
  for (const field of RETENTION_FIELDS) {
    const text = draft[field].trim();
    const value = /^\d{1,6}$/.test(text) ? Number(text) : Number.NaN;
    if (!isValidRetentionDays(value)) {
      return null;
    }
    out[field] = value;
  }
  return out;
}

export function RetentionPanel() {
  const { t } = useTranslation(['common', 'admin']);
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: retentionKeys.view(),
    queryFn: ({ signal }) => getRetention(signal),
  });
  const [draft, setDraft] = useState<Draft | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [understood, setUnderstood] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [result, setResult] = useState<RetentionUpdated | null>(null);

  if (query.isPending) {
    return <LoadingState variant="table" data-testid="admin-retention-loading" />;
  }
  if (query.isError) {
    return (
      <ErrorState
        error={errorInfo(query.error)}
        onRetry={() => {
          void query.refetch();
        }}
        data-testid="admin-retention-error"
      />
    );
  }
  const view = query.data;
  const shown = draft ?? draftOf(view.values);
  const parsed = parsedDraft(shown);
  const changes = parsed === null ? {} : retentionChanges(view.values, parsed);
  const shorter = shortenedFields(view.values, changes);
  const dirty = Object.keys(changes).length > 0;
  const sources: Readonly<Record<string, string>> = view.sources;

  const save = async (confirm: boolean): Promise<void> => {
    setPending(true);
    setError(null);
    try {
      const updated = await updateRetention(changes, confirm);
      setResult(updated);
      setDraft(null);
      setConfirming(false);
      setUnderstood(false);
      void queryClient.invalidateQueries({ queryKey: retentionKeys.view() });
    } catch (caught) {
      setError(caught);
    } finally {
      setPending(false);
    }
  };

  return (
    <section
      className="admin-panel"
      aria-labelledby="admin-retention-title"
      data-testid="admin-retention"
    >
      <h2 id="admin-retention-title" className="admin-panel__title">
        {t('admin:retention.title')}
      </h2>
      <p className="admin-note">{t('admin:retention.help')}</p>
      <div className="admin-toolbar">
        {RETENTION_FIELDS.map((field) => (
          <label key={field} className="admin-field admin-field--inline">
            <span className="admin-field__label">{retentionFieldLabel(t, field)}</span>
            <input
              className="admin-input"
              type="number"
              inputMode="numeric"
              min={RETENTION_MIN_DAYS}
              max={RETENTION_MAX_DAYS}
              step={1}
              value={shown[field]}
              onChange={(event) => {
                setResult(null);
                setDraft({ ...shown, [field]: event.target.value });
              }}
              aria-invalid={!isValidRetentionDays(Number(shown[field])) || undefined}
              data-testid={`admin-retention-field-${field}`}
              data-source={sources[field] ?? undefined}
            />
            <span className="admin-field__help">
              {sources[field] === 'settings'
                ? t('admin:retention.source.settings')
                : t('admin:retention.source.config')}
            </span>
          </label>
        ))}
      </div>
      {parsed === null ? (
        <p className="admin-error" role="alert" data-testid="admin-retention-invalid">
          {t('admin:retention.invalid', { min: RETENTION_MIN_DAYS, max: RETENTION_MAX_DAYS })}
        </p>
      ) : null}
      <div className="admin-actions">
        <button
          type="button"
          className="control-button control-button--primary"
          disabled={!dirty || parsed === null || pending}
          onClick={() => {
            if (shorter.length > 0) {
              setUnderstood(false);
              setConfirming(true);
            } else {
              void save(false);
            }
          }}
          data-testid="admin-retention-save"
        >
          {t('admin:retention.save')}
        </button>
        <button
          type="button"
          className="control-button"
          disabled={!dirty || pending}
          onClick={() => {
            setDraft(null);
            setError(null);
          }}
          data-testid="admin-retention-reset"
        >
          {t('admin:retention.reset')}
        </button>
      </div>
      {view.pending_drops > 0 ? (
        <p className="admin-muted" data-testid="admin-retention-pending-drops">
          {t('admin:retention.pendingDrops', { count: view.pending_drops })}
        </p>
      ) : null}
      {result === null ? null : (
        <p
          className="admin-note"
          role="status"
          data-testid="admin-retention-result"
          data-applied={String(result.applied)}
        >
          {result.applied
            ? t('admin:retention.applied', { count: result.queued_drops })
            : t('admin:retention.notApplied')}
        </p>
      )}
      {error === null || confirming ? null : (
        <ErrorState compact error={errorInfo(error)} data-testid="admin-retention-save-error" />
      )}
      <ConfirmDialog
        open={confirming}
        title={t('admin:retention.confirmTitle')}
        impact={t('admin:retention.confirmImpact', {
          fields: shorter.map((field) => retentionFieldLabel(t, field)).join(', '),
        })}
        okLabel={t('admin:retention.confirmOk')}
        okDisabled={!understood}
        pending={pending}
        error={error}
        onOk={() => {
          void save(true);
        }}
        onCancel={() => {
          setConfirming(false);
          setError(null);
        }}
      >
        <label className="admin-field">
          <span>
            <input
              type="checkbox"
              checked={understood}
              onChange={(event) => {
                setUnderstood(event.target.checked);
              }}
              data-testid="admin-retention-confirm"
            />{' '}
            {t('admin:retention.confirmCheck')}
          </span>
        </label>
      </ConfirmDialog>
    </section>
  );
}
