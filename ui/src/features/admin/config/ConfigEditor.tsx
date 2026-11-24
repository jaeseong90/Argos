// SPDX-License-Identifier: Apache-2.0
//
// Current configuration and the change form of `/admin/config` (docs/15 §9.10, 05 §2.11-5, F-S29,
// T-ACPT-18): the effective configuration of a reference instance (value, source, the instances
// whose state of the server-held value is not applied), a new value only for the keys every
// instance allows remotely (the others are locked: "Change it in the local argos-agent.yml"), the
// reason (required, at most 1 KiB), the targets (every instance or chosen ones), the preview
// (POST changes) shown as a diff with the keys each instance will not get, and apply.
import { useQuery } from '@tanstack/react-query';
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
  applyConfigChange,
  configProblem,
  proposeConfigChange,
  rejectedByInstance,
  type AppConfig,
  type ConfigChange,
  type ConfigTargetChoice,
} from '../../../api/admin/config';
import { errorInfo } from '../../../api/errors';
import { getEffectiveConfig, instanceKeys } from '../../../api/instances';
import type { Untrusted } from '../../../api/untrusted';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { ErrorState } from '../../../components/ErrorState';
import { SafeText } from '../../../components/SafeText';
import { MISSING_DASH } from '../../../utils/missing';
import { AlertDialog } from '../../alerts/common/AlertDialog';

import { configProblemLabel, driftReasonLabel, previewProblemLabel } from './labels';
import {
  configRows,
  draftChanges,
  hashDriftInstances,
  instanceOptionText,
  previewProblem,
  referenceInstance,
  type ConfigDraft,
  type ConfigRow,
} from './model';

export interface ConfigEditorProps {
  readonly appId: number;
  readonly config: AppConfig;
  readonly names: ReadonlyMap<number, Untrusted>;
  /** The change was applied: show its results. */
  readonly onApplied: (change: ConfigChange) => void;
}

export function ConfigEditor({ appId, config, names, onApplied }: ConfigEditorProps) {
  const { t } = useTranslation(['common', 'admin']);
  const reasonId = useId();
  const [reference, setReference] = useState<number | null>(() => referenceInstance(config));
  const [draft, setDraft] = useState<ConfigDraft>({});
  const [reason, setReason] = useState('');
  const [targets, setTargets] = useState<ConfigTargetChoice>({ all: true });
  const [preview, setPreview] = useState<ConfigChange | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const effective = useQuery({
    queryKey: instanceKeys.effectiveConfig(reference),
    queryFn: ({ signal }) => getEffectiveConfig(reference ?? 0, signal),
    enabled: reference !== null,
  });
  const rows = configRows(config, effective.data?.data ?? null);
  const hashDrift = hashDriftInstances(config);
  const problem = previewProblem(draft, reason, targets);
  const instances = config.remote_allowed_by_instance;

  const setValue = (key: string, value: string | null): void => {
    setDraft((previous) => ({ ...previous, [key]: value }));
  };

  const runPreview = async (): Promise<void> => {
    setPending(true);
    setError(null);
    try {
      const created = await proposeConfigChange(appId, {
        reason: reason.trim(),
        changes: draftChanges(draft),
        targets,
      });
      setPreview(created);
    } catch (caught) {
      setError(caught);
    } finally {
      setPending(false);
    }
  };

  const runApply = async (change: ConfigChange): Promise<void> => {
    setPending(true);
    setError(null);
    try {
      const applied = await applyConfigChange(change.change_id);
      setPreview(null);
      setDraft({});
      setReason('');
      onApplied(applied);
    } catch (caught) {
      setError(caught);
    } finally {
      setPending(false);
    }
  };

  const columns: DataTableColumn<ConfigRow>[] = [
    {
      id: 'key',
      header: t('admin:config.current.column.key'),
      value: (row) => row.key,
      width: 260,
      mono: true,
    },
    {
      id: 'value',
      header: t('admin:config.current.column.value'),
      value: (row) => row.value ?? row.held,
      width: 200,
      render: (row) =>
        row.value !== null ? (
          <SafeText value={row.value} mono data-testid="admin-config-value" />
        ) : (
          <span className="admin-muted" title={t('admin:config.current.localHint')}>
            {MISSING_DASH}
          </span>
        ),
    },
    {
      id: 'source',
      header: t('admin:config.current.column.source'),
      value: (row) => row.source,
      width: 130,
      render: (row) =>
        row.source === 'server'
          ? t('admin:config.current.source.server')
          : t('admin:config.current.source.local'),
    },
    {
      id: 'drift',
      header: t('admin:config.current.column.drift'),
      value: (row) => (row.drift.length === 0 ? null : row.drift.length),
      width: 200,
      render: (row) =>
        row.drift.length === 0 ? (
          <span className="admin-muted">{MISSING_DASH}</span>
        ) : (
          <span className="admin-drift" data-drift="true" data-testid="admin-config-drift">
            {row.drift.map((entry, index) => (
              <span key={`${String(entry.instanceId)}-${entry.reason}`}>
                {index === 0 ? null : ', '}
                {names.has(entry.instanceId) ? (
                  <SafeText value={names.get(entry.instanceId)} />
                ) : (
                  `#${String(entry.instanceId)}`
                )}{' '}
                ({driftReasonLabel(t, entry.reason)})
              </span>
            ))}
          </span>
        ),
    },
    {
      id: 'change',
      header: t('admin:config.current.column.change'),
      value: () => null,
      width: 260,
      hideable: false,
      render: (row) =>
        row.lock === null ? (
          <span className="admin-actions">
            <input
              className="admin-input admin-input--mono"
              aria-label={t('admin:config.current.newValue', { key: row.key })}
              value={draft[row.key] ?? ''}
              placeholder={
                draft[row.key] === null ? t('admin:config.current.unsetPlaceholder') : ''
              }
              disabled={draft[row.key] === null}
              onChange={(event) => {
                setValue(row.key, event.target.value);
              }}
              data-testid={`admin-config-field-${row.key}`}
            />
            {row.held !== null ? (
              <button
                type="button"
                className="control-button"
                aria-pressed={draft[row.key] === null}
                onClick={() => {
                  setValue(row.key, draft[row.key] === null ? '' : null);
                }}
                data-testid={`admin-config-unset-${row.key}`}
              >
                {t('admin:config.current.unset')}
              </button>
            ) : null}
          </span>
        ) : (
          <span
            className="admin-muted"
            data-testid="admin-config-locked"
            data-key={row.key}
            data-lock={row.lock}
          >
            <span aria-hidden="true">🔒 </span>
            {row.lock === 'killSwitch'
              ? t('admin:config.current.lockedKillSwitch')
              : t('admin:config.current.lockedLocal')}
          </span>
        ),
    },
  ];

  const editProblem = error === null ? null : configProblem(error);
  const rejected = preview === null ? new Map<number, string[]>() : rejectedByInstance(preview);

  return (
    <section className="admin-panel" aria-labelledby="admin-config-current-title">
      <h2 id="admin-config-current-title" className="admin-panel__title">
        {t('admin:config.current.title')}
      </h2>
      <div className="admin-toolbar">
        <label className="admin-field admin-field--inline">
          <span className="admin-field__label">{t('admin:config.current.reference')}</span>
          <select
            className="admin-select"
            value={reference === null ? '' : String(reference)}
            onChange={(event) => {
              const next = Number(event.target.value);
              setReference(Number.isInteger(next) && next > 0 ? next : null);
            }}
            data-testid="admin-config-instance"
          >
            {instances.map((instance) => (
              <option key={instance.instance_id} value={String(instance.instance_id)}>
                {instanceOptionText(
                  instance,
                  instance.online ? '' : ` (${t('admin:config.current.offline')})`,
                )}
              </option>
            ))}
          </select>
        </label>
      </div>
      {hashDrift.length === 0 ? null : (
        <p className="admin-warning" role="status" data-testid="admin-config-hash-drift">
          {t('admin:config.current.hashDrift', { count: hashDrift.length })}{' '}
          {hashDrift.map((id, index) => (
            <span key={id}>
              {index === 0 ? null : ', '}
              {names.has(id) ? <SafeText value={names.get(id)} /> : `#${String(id)}`}
            </span>
          ))}
        </p>
      )}
      {effective.isError ? (
        <ErrorState
          compact
          error={errorInfo(effective.error)}
          onRetry={() => {
            void effective.refetch();
          }}
          data-testid="admin-config-effective-error"
        />
      ) : null}
      <DataTable
        tableId="admin-config-current"
        caption={t('admin:config.current.caption')}
        columns={columns}
        rows={rows}
        rowKey={(row) => row.key}
        dataComplete
        density="dense"
        loading={reference !== null && effective.isPending}
        rowAttributes={(row) => ({
          'data-editable': row.lock === null ? 'true' : 'false',
          'data-drift': row.drift.length > 0 ? 'true' : undefined,
        })}
      />
      <p className="admin-field__help">{t('admin:config.current.lockedHelp')}</p>

      <div className="admin-field">
        <label className="admin-field__label" htmlFor={reasonId}>
          {t('admin:config.reason.label')}
        </label>
        <textarea
          id={reasonId}
          className="admin-input"
          rows={2}
          value={reason}
          onChange={(event) => {
            setReason(event.target.value);
          }}
          required
          data-testid="admin-config-reason"
        />
        <span className="admin-field__help">{t('admin:config.reason.help')}</span>
      </div>

      <fieldset className="admin-field" data-testid="admin-config-targets">
        <legend className="admin-field__label">{t('admin:config.targets.label')}</legend>
        <label>
          <input
            type="radio"
            name="admin-config-targets"
            checked={targets.all}
            onChange={() => {
              setTargets({ all: true });
            }}
            data-testid="admin-config-targets-all"
          />{' '}
          {t('admin:config.targets.all', { count: instances.length })}
        </label>
        <label>
          <input
            type="radio"
            name="admin-config-targets"
            checked={!targets.all}
            onChange={() => {
              setTargets({ all: false, instanceIds: [] });
            }}
            data-testid="admin-config-targets-some"
          />{' '}
          {t('admin:config.targets.some')}
        </label>
        {targets.all ? null : (
          <span className="admin-checklist">
            {instances.map((instance) => (
              <label key={instance.instance_id}>
                <input
                  type="checkbox"
                  checked={targets.instanceIds.includes(instance.instance_id)}
                  onChange={(event) => {
                    const others = targets.instanceIds.filter((id) => id !== instance.instance_id);
                    setTargets({
                      all: false,
                      instanceIds: event.target.checked
                        ? [...others, instance.instance_id]
                        : others,
                    });
                  }}
                  data-testid="admin-config-target"
                  data-instance-id={String(instance.instance_id)}
                />{' '}
                <SafeText value={instance.name} />
              </label>
            ))}
          </span>
        )}
      </fieldset>

      {editProblem === null || preview !== null ? null : editProblem.kind === 'other' ? (
        <ErrorState compact error={errorInfo(error)} data-testid="admin-config-error" />
      ) : (
        <p
          className="admin-error"
          role="alert"
          data-testid="admin-config-error"
          data-kind={editProblem.kind}
          data-key={editProblem.key ?? undefined}
        >
          {configProblemLabel(t, editProblem.kind, editProblem.key)}
        </p>
      )}
      <div className="admin-actions">
        <button
          type="button"
          className="control-button control-button--primary"
          disabled={problem !== null || pending}
          onClick={() => {
            void runPreview();
          }}
          data-testid="admin-config-preview"
        >
          {t('admin:config.preview')}
        </button>
        {problem === null ? null : (
          <span className="admin-muted" data-testid="admin-config-problem" data-problem={problem}>
            {previewProblemLabel(t, problem)}
          </span>
        )}
      </div>

      <AlertDialog
        open={preview !== null}
        title={t('admin:config.diff.title')}
        onClose={() => {
          setPreview(null);
        }}
        data-testid="admin-config-diff"
        footer={
          <>
            <button
              type="button"
              className="control-button"
              onClick={() => {
                setPreview(null);
              }}
              data-testid="admin-config-diff-cancel"
            >
              {t('admin:common.cancel')}
            </button>
            <button
              type="button"
              className="control-button control-button--primary"
              disabled={pending || preview === null}
              onClick={() => {
                if (preview !== null) {
                  void runApply(preview);
                }
              }}
              data-testid="admin-config-apply"
            >
              {t('admin:config.diff.apply')}
            </button>
          </>
        }
      >
        {preview === null ? null : (
          <>
            <table className="admin-diff">
              <caption className="visually-hidden">{t('admin:config.diff.caption')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('admin:config.diff.key')}</th>
                  <th scope="col">{t('admin:config.diff.before')}</th>
                  <th scope="col">{t('admin:config.diff.after')}</th>
                </tr>
              </thead>
              <tbody>
                {preview.preview.diff.map((entry) => (
                  <tr key={entry.key} data-testid="admin-config-diff-row" data-key={entry.key}>
                    <th scope="row" className="admin-input--mono">
                      {entry.key}
                    </th>
                    <td data-testid="admin-config-diff-before">
                      {entry.before === null ? (
                        <span className="admin-muted">{t('admin:config.diff.none')}</span>
                      ) : (
                        <SafeText value={entry.before} mono />
                      )}
                    </td>
                    <td data-testid="admin-config-diff-after">
                      {entry.after === null ? (
                        <span className="admin-muted">{t('admin:config.diff.removed')}</span>
                      ) : (
                        <SafeText value={entry.after} mono />
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="admin-field__help">
              {preview.targets.all
                ? t('admin:config.diff.targetsAll')
                : t('admin:config.diff.targetsSome', {
                    count: preview.targets.instance_ids.length,
                  })}
            </p>
            {rejected.size === 0 ? null : (
              <div className="admin-warning" data-testid="admin-config-diff-rejected">
                <p>{t('admin:config.diff.rejected')}</p>
                <ul>
                  {[...rejected.entries()].map(([instanceId, keys]) => (
                    <li key={instanceId} data-instance-id={String(instanceId)}>
                      {names.has(instanceId) ? (
                        <SafeText value={names.get(instanceId)} />
                      ) : (
                        `#${String(instanceId)}`
                      )}
                      : <span className="admin-input--mono">{keys.join(', ')}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {editProblem === null ? null : editProblem.kind === 'other' ? (
              <ErrorState compact error={errorInfo(error)} data-testid="admin-config-apply-error" />
            ) : (
              <p className="admin-error" role="alert" data-testid="admin-config-apply-error">
                {configProblemLabel(t, editProblem.kind, editProblem.key)}
              </p>
            )}
          </>
        )}
      </AlertDialog>
    </section>
  );
}
